package com.snapdeal.kam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The Virtual KAM agent loop:
 * user turn -> Gemini -> (tool calls -> tool results -> Gemini)* -> spoken
 * reply.
 */
@Service
public class KamAgent {
    private static final int MAX_TOOL_ROUNDS = 6;

    private final ObjectMapper om;
    private final GeminiClient gemini;
    private final ToolService tools;
    private final String systemPrompt;
    private final JsonNode toolDeclarations;

    @Value("${kam.gemini.thinking-budget:0}")
    private int thinkingBudget;

    public KamAgent(ObjectMapper om, GeminiClient gemini, ToolService tools) throws IOException {
        this.om = om;
        this.gemini = gemini;
        this.tools = tools;
        this.systemPrompt = read("system-prompt.txt");
        try (InputStream in = new ClassPathResource("tools.json").getInputStream()) {
            this.toolDeclarations = om.readTree(in);
        }
    }

    /**
     * Spoken as soon as the call connects. Bilingual because the seller's language
     * is not known yet.
     */
    public static final String GREETING = "नमस्ते, मैं Disha बोल रही हूँ, Snapdeal से। बताइए, मैं आपकी क्या मदद कर सकती हूँ? "
            + "Hello, this is Disha from Snapdeal. How can I help you today?";

    /**
     * Starts the call: records the greeting in the conversation and returns it.
     * No model call, so the seller hears Disha immediately and she then waits for
     * the seller to speak.
     */
    public ObjectNode start(Session s) {
        s.contents.removeAll();
        s.contents.add(
                textTurn("user", "(System event, not spoken by the seller) The seller has connected to the call."));
        s.contents.add(textTurn("model", GREETING));

        ObjectNode out = om.createObjectNode();
        out.put("reply", GREETING);
        out.putArray("toolCalls");
        out.set("state", tools.state(s));
        return out;
    }

    public ObjectNode chat(Session s, String userText) {
        s.contents.add(textTurn("user", userText));

        ArrayNode trace = om.createArrayNode();
        if (s.contents.size() <= 3 && s.sellerId != null && !s.sellerId.isBlank()) {
            try {
                ObjectNode t1 = trace.addObject();
                t1.put("name", "get_seller_metrics");
                t1.putNull("args");
                t1.set("result", tools.call("get_seller_metrics", null, s));

                ObjectNode t2 = trace.addObject();
                t2.put("name", "diagnose_sales_drop");
                t2.putNull("args");
                t2.set("result", tools.call("diagnose_sales_drop", null, s));
            } catch (Exception ignored) {}
        }
        String reply = null;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            JsonNode resp = gemini.generate(buildRequest(s));
            JsonNode content = resp.path("candidates").path(0).path("content");

            if (!content.has("parts")) {
                break; // blocked or empty answer: fall through to the fallback reply
            }

            // Keep Gemini's turn exactly as returned (tool-call turns may carry
            // signatures).
            ObjectNode modelTurn = content.deepCopy();
            modelTurn.put("role", "model");
            s.contents.add(modelTurn);

            ArrayNode toolResults = om.createArrayNode();
            StringBuilder text = new StringBuilder();

            for (JsonNode part : content.path("parts")) {
                if (part.has("functionCall")) {
                    String name = part.path("functionCall").path("name").asText();
                    JsonNode args = part.path("functionCall").path("args");
                    ObjectNode result = tools.call(name, args, s);

                    ObjectNode t = trace.addObject();
                    t.put("name", name);
                    t.set("args", args);
                    t.set("result", result);

                    ObjectNode fr = toolResults.addObject().putObject("functionResponse");
                    fr.put("name", name);
                    fr.putObject("response").set("result", result);
                } else if (part.has("text") && !part.path("thought").asBoolean(false)) {
                    text.append(part.path("text").asText());
                }
            }

            if (toolResults.isEmpty()) {
                reply = text.toString().trim();
                break;
            }
            ObjectNode resultTurn = om.createObjectNode();
            resultTurn.put("role", "user");
            resultTurn.set("parts", toolResults);
            s.contents.add(resultTurn);
        }

        if (reply == null || reply.isEmpty()) {
            reply = "Sorry, I did not catch that. Could you say it once more?";
        }

        ObjectNode out = om.createObjectNode();
        out.put("reply", reply);
        out.set("toolCalls", trace);
        out.set("state", tools.state(s));
        return out;
    }

    private ObjectNode buildRequest(Session s) {
        ObjectNode body = om.createObjectNode();
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH));
        String prompt = systemPrompt.replace("{{TODAY}}", today);

        if (s.sellerId != null && !s.sellerId.isBlank()) {
            try {
                ObjectNode metrics = tools.call("get_seller_metrics", null, s);
                ObjectNode diag = tools.call("diagnose_sales_drop", null, s);
                prompt += "\n\n[PRE-LOADED SELLER DATA FOR INSTANT REASONING]\n"
                        + "Seller Profile & Metrics: " + metrics.toString() + "\n"
                        + "Sales Drop Diagnosis: " + diag.toString() + "\n"
                        + "SPEED RULE: You already have the verified seller metrics and sales drop diagnosis above. "
                        + "Do NOT call get_seller_metrics or diagnose_sales_drop. Answer the seller directly using this data in your response. "
                        + "Only call other tools when taking actions like get_out_of_stock_products, create_action_plan, schedule_followup, or send_summary.";
            } catch (Exception ignored) {}
        }

        body.putObject("systemInstruction").putArray("parts").addObject()
                .put("text", prompt);
        body.set("contents", s.contents);
        body.set("tools", toolDeclarations);

        ObjectNode gc = body.putObject("generationConfig");
        gc.put("temperature", 0.4);
        gc.put("maxOutputTokens", 512);
        if (thinkingBudget >= 0) {
            gc.putObject("thinkingConfig").put("thinkingBudget", thinkingBudget);
        }
        return body;
    }

    private ObjectNode textTurn(String role, String text) {
        ObjectNode turn = om.createObjectNode();
        turn.put("role", role);
        turn.putArray("parts").addObject().put("text", text);
        return turn;
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
