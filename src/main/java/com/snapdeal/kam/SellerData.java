package com.snapdeal.kam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loads the mock sellers once at startup. The first seller is the one from the assignment brief. */
@Component
public class SellerData {
    private final ObjectMapper om;
    private final Map<String, JsonNode> sellers = new LinkedHashMap<>();
    private final String defaultId;

    public SellerData(ObjectMapper om) throws IOException {
        this.om = om;
        JsonNode root;
        try (InputStream in = new ClassPathResource("seller.json").getInputStream()) {
            root = om.readTree(in);
        }
        this.defaultId = root.path("defaultSellerId").asText();
        for (JsonNode s : root.path("sellers")) {
            sellers.put(s.path("id").asText(), s);
        }
    }

    /** Returns the requested seller, or the brief's seller if the id is missing or unknown. */
    public JsonNode seller(String id) {
        JsonNode s = id == null ? null : sellers.get(id);
        return s != null ? s : sellers.get(defaultId);
    }

    public Collection<JsonNode> all() {
        return sellers.values();
    }

    public String defaultId() {
        return defaultId;
    }

    /** Public profile fields only (no metrics, no internal notes). */
    public ObjectNode profile(JsonNode seller) {
        ObjectNode p = om.createObjectNode();
        for (String f : new String[]{"id", "name", "business", "category", "activeListings", "sellingOnSnapdealSince"}) {
            p.set(f, seller.path(f));
        }
        return p;
    }
}
