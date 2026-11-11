package com.snapdeal.kam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** One phone call: the Gemini conversation history plus what the agent has committed to. */
public class Session {
    public final String id;
    /** Gemini "contents" array, kept exactly as returned so tool-call turns stay valid. */
    public final ArrayNode contents;
    /** Which mock seller is on this call. Null means the brief's default seller. */
    public String sellerId;
    public ObjectNode plan;
    public ObjectNode followUp;
    public ObjectNode summary;

    public Session(String id, ObjectMapper om) {
        this.id = id;
        this.contents = om.createArrayNode();
    }
}
