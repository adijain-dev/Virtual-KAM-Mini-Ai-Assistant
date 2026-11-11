package com.snapdeal.kam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The agent's "hands". Each method is a tool Gemini can call, and every tool reads the seller
 * selected for the call. Analysis is done here in plain Java so the numbers are exact; the LLM
 * only decides which tool to call and explains the result to the seller in their language.
 */
@Service
public class ToolService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH);

    private final ObjectMapper om;
    private final SellerData data;

    public ToolService(ObjectMapper om, SellerData data) {
        this.om = om;
        this.data = data;
    }

    public ObjectNode call(String name, JsonNode args, Session s) {
        JsonNode seller = data.seller(s.sellerId);
        try {
            return switch (name) {
                case "get_seller_metrics" -> sellerMetrics(seller);
                case "diagnose_sales_drop" -> diagnoseSalesDrop(seller);
                case "get_cancellation_breakdown" -> cancellationBreakdown(seller);
                case "get_out_of_stock_products" -> outOfStock(seller, args.path("limit").asInt(5));
                case "get_traffic_and_visibility" -> trafficAndVisibility(seller);
                case "get_pricing_insights" -> pricingInsights(seller);
                case "create_action_plan" -> createActionPlan(args, s);
                case "schedule_followup" -> scheduleFollowup(args, s);
                case "send_summary" -> sendSummary(args, s);
                default -> error("Unknown tool: " + name);
            };
        } catch (RuntimeException e) {
            return error("Tool " + name + " failed: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- read tools

    private ObjectNode sellerMetrics(JsonNode seller) {
        JsonNode lm = seller.path("metrics").path("lastMonth");
        JsonNode tm = seller.path("metrics").path("thisMonth");
        ObjectNode out = om.createObjectNode();
        out.set("seller", data.profile(seller));
        out.set("lastMonth", lm);
        out.set("thisMonth", tm);
        ObjectNode ch = out.putObject("change");
        ch.put("salesPct", pct(lm, tm, "sales"));
        ch.put("ordersPct", pct(lm, tm, "orders"));
        ch.put("avgSellingPricePct", pct(lm, tm, "avgSellingPrice"));
        ch.put("cancellationRatePointsChange", round1(tm.path("cancellationRate").asDouble() - lm.path("cancellationRate").asDouble()));
        ch.put("outOfStockProductsChange", tm.path("outOfStockProducts").asInt() - lm.path("outOfStockProducts").asInt());
        out.set("assumptions", seller.path("assumptions"));
        return out;
    }

    /**
     * Revenue bridge plus cause finding.
     * Net sales change = volume effect + cancellation effect + price effect.
     * Order change is further split into a traffic effect and a conversion effect when impressions exist.
     * The cause is whichever of stock / visibility / pricing explains most of the order decline.
     */
    private ObjectNode diagnoseSalesDrop(JsonNode seller) {
        JsonNode lm = seller.path("metrics").path("lastMonth");
        JsonNode tm = seller.path("metrics").path("thisMonth");

        double lo = lm.path("orders").asDouble();
        double to = tm.path("orders").asDouble();
        double lc = lm.path("cancellationRate").asDouble() / 100.0;
        double tc = tm.path("cancellationRate").asDouble() / 100.0;
        double lp = lm.path("avgSellingPrice").asDouble();
        double tp = tm.path("avgSellingPrice").asDouble();

        double lastNetOrders = lo * (1 - lc);
        double thisNetOrders = to * (1 - tc);
        double netChange = thisNetOrders * tp - lastNetOrders * lp;

        double volume = (to - lo) * (1 - lc) * lp;
        double cancellation = to * (lc - tc) * lp;
        double price = thisNetOrders * (tp - lp);

        List<ObjectNode> drivers = new ArrayList<>();
        drivers.add(driver("Fewer orders coming in (volume)", volume, netChange));
        drivers.add(driver("Change in cancellation rate", cancellation, netChange));
        drivers.add(driver("Change in selling price", price, netChange));
        drivers.sort(Comparator.comparingDouble(d -> d.path("impactRupees").asDouble()));

        double orderChange = to - lo;                 // negative when orders fell
        double orderDecline = Math.max(0, lo - to);

        // --- stock signal
        double lostOrders = 0;
        for (JsonNode p : seller.path("outOfStockProducts")) {
            lostOrders += p.path("estLostOrders").asDouble();
        }
        double oosShare = orderDecline > 0 ? Math.min(100.0, lostOrders / orderDecline * 100.0) : 0;

        // --- traffic and conversion signal (only when the seller has impressions data)
        JsonNode tr = seller.path("traffic");
        boolean hasTraffic = tr.has("lastMonth") && tr.has("thisMonth");
        double li = tr.path("lastMonth").path("impressions").asDouble();
        double ti = tr.path("thisMonth").path("impressions").asDouble();
        double lcr = hasTraffic && li > 0 ? lo / li : 0;
        double tcr = hasTraffic && ti > 0 ? to / ti : 0;
        double impChg = hasTraffic && li > 0 ? (ti - li) / li * 100.0 : 0;
        double convChg = hasTraffic && lcr > 0 ? (tcr - lcr) / lcr * 100.0 : 0;
        double trafficEffect = hasTraffic ? (ti - li) * lcr : 0;
        double convEffect = hasTraffic ? ti * (tcr - lcr) : 0;
        double trafficShare = hasTraffic && orderChange < 0 ? trafficEffect / orderChange * 100.0 : 0;
        double convShare = hasTraffic && orderChange < 0 ? convEffect / orderChange * 100.0 : 0;

        // --- price signal
        double priceChg = lp > 0 ? (tp - lp) / lp * 100.0 : 0;
        double compNow = seller.path("pricing").path("competitorAvgPrice").path("thisMonth").asDouble(0);
        double gapPct = compNow > 0 ? (tp - compNow) / compNow * 100.0 : Double.NaN;

        // --- score each candidate cause by the share of the order decline it explains
        double stockScore = oosShare;
        double visScore = (hasTraffic && impChg <= -20) ? Math.min(100.0, Math.max(0, trafficShare)) : 0;
        double priceScore = (hasTraffic && convChg <= -20 && priceChg >= 5) ? Math.min(100.0, Math.max(0, convShare)) : 0;

        double best = Math.max(stockScore, Math.max(visScore, priceScore));
        String cause;
        if (best < 30) cause = "unclear";
        else if (best == stockScore) cause = "stock";
        else if (best == visScore) cause = "visibility";
        else cause = "pricing";

        ObjectNode out = om.createObjectNode();
        ObjectNode head = out.putObject("headline");
        head.put("grossSalesChangePct", pct(lm, tm, "sales"));
        head.put("ordersChangePct", pct(lm, tm, "orders"));
        head.put("grossOrdersLost", (int) orderDecline);
        head.put("netFulfilledOrdersLastMonth", round1(lastNetOrders));
        head.put("netFulfilledOrdersThisMonth", round1(thisNetOrders));
        head.put("netSalesChangeRupees", Math.round(netChange));

        ArrayNode dr = out.putArray("drivers");
        drivers.forEach(dr::add);

        ObjectNode oos = out.putObject("outOfStock");
        oos.put("productsOutOfStockNow", tm.path("outOfStockProducts").asInt());
        oos.put("productsOutOfStockLastMonth", lm.path("outOfStockProducts").asInt());
        oos.put("estimatedLostOrders", (int) lostOrders);
        oos.put("estimatedLostRevenueRupees", Math.round(lostOrders * tp));
        oos.put("shareOfOrderDeclinePct", round1(oosShare));
        oos.put("note", "Estimate based on mock per-product demand, not measured data.");

        if (hasTraffic) {
            ObjectNode t = out.putObject("trafficAnalysis");
            t.put("impressionsLastMonth", Math.round(li));
            t.put("impressionsThisMonth", Math.round(ti));
            t.put("impressionsChangePct", round1(impChg));
            t.put("conversionRateLastMonthPct", round2(lcr * 100));
            t.put("conversionRateThisMonthPct", round2(tcr * 100));
            t.put("conversionChangePct", round1(convChg));
            t.put("ordersChangeFromTraffic", Math.round(trafficEffect));
            t.put("ordersChangeFromConversion", Math.round(convEffect));
        }

        ObjectNode scores = out.putObject("causeScoresPct");
        scores.put("stock", round1(stockScore));
        scores.put("visibility", round1(visScore));
        scores.put("pricing", round1(priceScore));

        ArrayNode ruledOut = out.putArray("ruledOut");
        if (Math.abs(tp - lp) < 0.5) {
            ruledOut.add("Pricing: average selling price is unchanged, so a price change is not the cause.");
        }
        if (hasTraffic && impChg > -10) {
            ruledOut.add("Visibility: impressions are roughly steady, so the drop is not from fewer people seeing the products.");
        }
        if (oosShare < 15) {
            ruledOut.add("Stock: out-of-stock products explain only a small part of the order decline.");
        }

        ArrayNode gaps = out.putArray("dataGaps");
        if (!hasTraffic) gaps.add("Traffic and impressions");
        gaps.add("Ratings and returns");
        if (!seller.has("visibility")) gaps.add("Ad spend, search rank and listing quality");
        if (compNow <= 0) gaps.add("Competitor prices");

        out.put("primaryCause", cause);
        out.put("confidence", switch (cause) {
            case "unclear" -> "low";
            case "stock" -> hasTraffic ? "high" : "medium";
            default -> "high";
        });
        out.put("rootCauseHypothesis", hypothesis(cause, seller, to, lo, orderDecline, lostOrders,
                tm.path("outOfStockProducts").asInt(), lm.path("outOfStockProducts").asInt(),
                lc, tc, impChg, lcr, tcr, priceChg, gapPct, hasTraffic));
        return out;
    }

    private String hypothesis(String cause, JsonNode seller, double to, double lo, double orderDecline, double lostOrders,
                              int oosNow, int oosBefore, double lc, double tc, double impChg, double lcr, double tcr,
                              double priceChg, double gapPct, boolean hasTraffic) {
        double ordersPct = lo > 0 ? (to - lo) / lo * 100.0 : 0;
        switch (cause) {
            case "stock":
                return String.format(Locale.ENGLISH,
                        "Mostly a volume problem: orders fell %.0f%% and out-of-stock products rose from %d to %d, which explains an "
                                + "estimated %.0f of the %.0f lost orders. Cancellations went from %.0f%% to %.0f%%, and many are seller-side "
                                + "stock-outs. Stock availability looks like the root cause.%s",
                        Math.abs(ordersPct), oosBefore, oosNow, lostOrders, orderDecline, lc * 100, tc * 100,
                        hasTraffic ? "" : " Traffic data is not available, so visibility cannot be ruled out.");
            case "pricing": {
                String gap = Double.isNaN(gapPct) ? ""
                        : String.format(Locale.ENGLISH, ", and the average price is now about %.0f%% above the competitor average", gapPct);
                int repriced = seller.path("pricing").path("listingsRepriced").asInt(0);
                String rep = repriced > 0 ? String.format(Locale.ENGLISH, " %d listings were repriced.", repriced) : "";
                return String.format(Locale.ENGLISH,
                        "Mostly a conversion problem caused by pricing: impressions changed only %.0f%%, but conversion fell from %.2f%% "
                                + "to %.2f%% after the average selling price rose %.0f%%%s.%s Customers are likely choosing cheaper options.",
                        impChg, lcr * 100, tcr * 100, priceChg, gap, rep);
            }
            case "visibility": {
                JsonNode v0 = seller.path("visibility").path("lastMonth");
                JsonNode v1 = seller.path("visibility").path("thisMonth");
                StringBuilder detail = new StringBuilder();
                if (v1.has("avgSearchRank")) {
                    detail.append(String.format(Locale.ENGLISH, " Average search rank slipped from %d to %d.",
                            v0.path("avgSearchRank").asInt(), v1.path("avgSearchRank").asInt()));
                }
                if (v0.path("adsActive").asBoolean(false) && !v1.path("adsActive").asBoolean(true)) {
                    detail.append(" Ads were switched off.");
                }
                if (v1.has("lowQualityListings")) {
                    detail.append(String.format(Locale.ENGLISH, " Low-quality listings went from %d to %d.",
                            v0.path("lowQualityListings").asInt(), v1.path("lowQualityListings").asInt()));
                }
                return String.format(Locale.ENGLISH,
                        "Mostly a visibility problem: impressions fell %.0f%% while conversion held steady (%.2f%% to %.2f%%), so fewer "
                                + "people are seeing the products.%s",
                        Math.abs(impChg), lcr * 100, tcr * 100, detail);
            }
            default:
                return "The data does not point to one clear cause. Ask the seller about stock, pricing and visibility before recommending.";
        }
    }

    private ObjectNode cancellationBreakdown(JsonNode seller) {
        JsonNode tm = seller.path("metrics").path("thisMonth");
        double cancelled = tm.path("orders").asDouble() * tm.path("cancellationRate").asDouble() / 100.0;
        ObjectNode out = om.createObjectNode();
        out.put("cancellationRatePct", tm.path("cancellationRate").asDouble());
        out.put("cancelledOrdersThisMonth", Math.round(cancelled));
        double controllable = 0;
        ArrayNode reasons = out.putArray("reasons");
        for (JsonNode r : seller.path("cancellationReasonsThisMonth")) {
            ObjectNode item = reasons.addObject();
            item.put("reason", r.path("reason").asText());
            item.put("sharePct", r.path("sharePct").asInt());
            item.put("approxOrders", Math.round(cancelled * r.path("sharePct").asDouble() / 100.0));
            item.put("sellerControllable", r.path("sellerControllable").asBoolean());
            if (r.path("sellerControllable").asBoolean()) {
                controllable += r.path("sharePct").asDouble();
            }
        }
        out.put("sellerControllableSharePct", round1(controllable));
        return out;
    }

    private ObjectNode outOfStock(JsonNode seller, int limit) {
        int n = Math.max(1, Math.min(limit, 18));
        List<JsonNode> all = new ArrayList<>();
        seller.path("outOfStockProducts").forEach(all::add);
        all.sort((a, b) -> Integer.compare(b.path("estLostOrders").asInt(), a.path("estLostOrders").asInt()));

        double total = 0;
        for (JsonNode p : all) {
            total += p.path("estLostOrders").asDouble();
        }
        double topSum = 0;
        ArrayNode items = om.createArrayNode();
        for (int i = 0; i < Math.min(n, all.size()); i++) {
            items.add(all.get(i));
            topSum += all.get(i).path("estLostOrders").asDouble();
        }
        ObjectNode out = om.createObjectNode();
        out.put("totalOutOfStock", all.size());
        out.put("totalEstimatedLostOrders", (int) total);
        out.put("topNShareOfLostOrdersPct", total > 0 ? round1(topSum / total * 100.0) : 0);
        out.set("products", items);
        return out;
    }

    private ObjectNode trafficAndVisibility(JsonNode seller) {
        ObjectNode out = om.createObjectNode();
        JsonNode tr = seller.path("traffic");
        if (!tr.has("lastMonth") || !tr.has("thisMonth")) {
            out.put("available", false);
            out.put("note", "No traffic or visibility data is available for this seller. Ask the seller about recent changes to ads, "
                    + "listing photos and titles, or search ranking.");
            return out;
        }
        JsonNode lm = seller.path("metrics").path("lastMonth");
        JsonNode tm = seller.path("metrics").path("thisMonth");
        double li = tr.path("lastMonth").path("impressions").asDouble();
        double ti = tr.path("thisMonth").path("impressions").asDouble();
        out.put("available", true);
        out.put("impressionsLastMonth", Math.round(li));
        out.put("impressionsThisMonth", Math.round(ti));
        out.put("impressionsChangePct", li > 0 ? round1((ti - li) / li * 100.0) : 0);
        out.put("conversionRateLastMonthPct", li > 0 ? round2(lm.path("orders").asDouble() / li * 100.0) : 0);
        out.put("conversionRateThisMonthPct", ti > 0 ? round2(tm.path("orders").asDouble() / ti * 100.0) : 0);
        JsonNode v = seller.path("visibility");
        if (v.has("lastMonth") && v.has("thisMonth")) {
            ObjectNode vo = out.putObject("visibility");
            vo.put("avgSearchRankLastMonth", v.path("lastMonth").path("avgSearchRank").asInt());
            vo.put("avgSearchRankThisMonth", v.path("thisMonth").path("avgSearchRank").asInt());
            vo.put("adsActiveLastMonth", v.path("lastMonth").path("adsActive").asBoolean());
            vo.put("adsActiveThisMonth", v.path("thisMonth").path("adsActive").asBoolean());
            vo.put("lowQualityListingsLastMonth", v.path("lastMonth").path("lowQualityListings").asInt());
            vo.put("lowQualityListingsThisMonth", v.path("thisMonth").path("lowQualityListings").asInt());
        }
        return out;
    }

    private ObjectNode pricingInsights(JsonNode seller) {
        JsonNode lm = seller.path("metrics").path("lastMonth");
        JsonNode tm = seller.path("metrics").path("thisMonth");
        double lp = lm.path("avgSellingPrice").asDouble();
        double tp = tm.path("avgSellingPrice").asDouble();
        ObjectNode out = om.createObjectNode();
        out.put("avgSellingPriceLastMonth", Math.round(lp));
        out.put("avgSellingPriceThisMonth", Math.round(tp));
        out.put("avgSellingPriceChangePct", lp > 0 ? round1((tp - lp) / lp * 100.0) : 0);
        if (seller.path("pricing").has("listingsRepriced")) {
            out.put("listingsRepriced", seller.path("pricing").path("listingsRepriced").asInt());
        }
        JsonNode comp = seller.path("pricing").path("competitorAvgPrice");
        if (comp.has("thisMonth")) {
            double c = comp.path("thisMonth").asDouble();
            out.put("competitorAvgPriceThisMonth", Math.round(c));
            out.put("priceVsCompetitorPct", c > 0 ? round1((tp - c) / c * 100.0) : 0);
        } else {
            out.put("competitorPrice", "not available");
        }
        return out;
    }

    // ---------------------------------------------------------------- action tools

    private ObjectNode createActionPlan(JsonNode args, Session s) {
        ObjectNode plan = om.createObjectNode();
        plan.put("planId", "PLAN-" + (1000 + ThreadLocalRandom.current().nextInt(9000)));
        plan.put("createdOn", LocalDate.now().format(DATE_FMT));
        plan.set("issues", args.has("issues") ? args.get("issues") : om.createArrayNode());
        plan.set("actions", args.has("actions") ? args.get("actions") : om.createArrayNode());
        s.plan = plan;

        ObjectNode out = om.createObjectNode();
        out.put("status", "saved");
        out.put("planId", plan.path("planId").asText());
        out.put("actionCount", plan.path("actions").size());
        return out;
    }

    private ObjectNode scheduleFollowup(JsonNode args, Session s) {
        int days = Math.max(1, Math.min(args.path("inDays").asInt(3), 14));
        ObjectNode f = om.createObjectNode();
        f.put("date", LocalDate.now().plusDays(days).format(DATE_FMT));
        f.put("inDays", days);
        f.put("reason", args.path("reason").asText("Check progress on the action plan"));
        s.followUp = f;

        ObjectNode out = om.createObjectNode();
        out.put("status", "scheduled");
        out.set("followUp", f);
        return out;
    }

    private ObjectNode sendSummary(JsonNode args, Session s) {
        if (s.plan == null) {
            return error("No action plan saved yet. Call create_action_plan first.");
        }
        String channel = args.path("channel").asText("whatsapp");
        StringBuilder sb = new StringBuilder();
        sb.append("Snapdeal Virtual KAM: your action plan (").append(s.plan.path("planId").asText()).append(")\n\n");
        sb.append("What we found:\n");
        for (JsonNode i : s.plan.path("issues")) {
            sb.append("- ").append(i.asText()).append('\n');
        }
        sb.append("\nWhat to do:\n");
        int n = 1;
        for (JsonNode a : s.plan.path("actions")) {
            sb.append(n++).append(". ").append(a.path("title").asText())
                    .append(" (").append(a.path("priority").asText("medium")).append(" priority, within ")
                    .append(a.path("dueInDays").asInt(3)).append(" days)\n   ")
                    .append(a.path("detail").asText()).append('\n');
        }
        if (s.followUp != null) {
            sb.append("\nFollow-up call: ").append(s.followUp.path("date").asText());
        }

        ObjectNode summary = om.createObjectNode();
        summary.put("channel", channel);
        summary.put("text", sb.toString());
        s.summary = summary;

        ObjectNode out = om.createObjectNode();
        out.put("status", "sent (mock)");
        out.put("channel", channel);
        return out;
    }

    // ---------------------------------------------------------------- helpers

    public ObjectNode state(Session s) {
        ObjectNode st = om.createObjectNode();
        if (s.plan != null) st.set("plan", s.plan);
        if (s.followUp != null) st.set("followUp", s.followUp);
        if (s.summary != null) st.set("summary", s.summary);
        return st;
    }

    private ObjectNode driver(String name, double impact, double total) {
        ObjectNode d = om.createObjectNode();
        d.put("name", name);
        d.put("impactRupees", Math.round(impact));
        d.put("direction", impact < 0 ? "hurt sales" : (impact > 0 ? "helped sales (offset part of the drop)" : "no effect"));
        d.put("shareOfChangePct", total != 0 ? round1(impact / total * 100.0) : 0);
        return d;
    }

    private ObjectNode error(String msg) {
        ObjectNode e = om.createObjectNode();
        e.put("error", msg);
        return e;
    }

    private static double pct(JsonNode a, JsonNode b, String field) {
        double l = a.path(field).asDouble();
        double t = b.path(field).asDouble();
        return l == 0 ? 0 : round1((t - l) / l * 100.0);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
