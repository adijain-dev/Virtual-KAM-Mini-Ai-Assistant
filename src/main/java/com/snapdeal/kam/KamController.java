package com.snapdeal.kam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class KamController {

    public record ChatRequest(String sessionId, String message, String sellerId) {}

    public record TtsRequest(String text) {}

    private static final int MAX_MESSAGE_CHARS = 1000;
    private static final int MAX_AUDIO_BYTES = 3_000_000;

    private final KamAgent agent;
    private final SessionStore sessions;
    private final GeminiClient gemini;
    private final SellerData data;
    private final ObjectMapper om;

    public KamController(KamAgent agent, SessionStore sessions, GeminiClient gemini, SellerData data, ObjectMapper om) {
        this.agent = agent;
        this.sessions = sessions;
        this.gemini = gemini;
        this.data = data;
        this.om = om;
    }

    /** One conversational turn. The message "__START__" begins the call and makes Disha speak first. */
    @PostMapping("/chat")
    public JsonNode chat(@RequestBody ChatRequest req) {
        if (req.message() == null || req.message().isBlank()) {
            throw new KamException("Empty message");
        }
        String id = (req.sessionId() == null || req.sessionId().isBlank()) ? "default" : req.sessionId();
        String text = req.message().trim();
        if (text.length() > MAX_MESSAGE_CHARS) {
            text = text.substring(0, MAX_MESSAGE_CHARS);
        }
        Session s = sessions.get(id);
        synchronized (s) {
            if (text.equals("__START__")) {
                s.sellerId = req.sellerId();
                return agent.start(s);
            }
            return agent.chat(s, text);
        }
    }

    /** Speech to text. The browser uploads one utterance as WAV; the language is detected automatically. */
    @PostMapping(value = "/stt", consumes = {"audio/wav", "application/octet-stream"})
    public Map<String, String> stt(@RequestBody byte[] audio) {
        if (audio == null || audio.length < 1000) {
            return Map.of("text", "");
        }
        if (audio.length > MAX_AUDIO_BYTES) {
            throw new KamException("Audio too long");
        }
        return Map.of("text", gemini.transcribe(audio));
    }

    /** Gemini text-to-speech. The browser falls back to its own voice if this fails. */
    @PostMapping("/tts")
    public ResponseEntity<byte[]> tts(@RequestBody TtsRequest req) {
        if (req.text() == null || req.text().isBlank()) {
            throw new KamException("Empty text");
        }
        byte[] audio = gemini.tts(req.text());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(audio);
    }

    /** The sellers available in the dropdown. */
    @GetMapping("/sellers")
    public JsonNode sellers() {
        ArrayNode list = om.createArrayNode();
        for (JsonNode s : data.all()) {
            ObjectNode o = data.profile(s);
            o.put("isDefault", s.path("id").asText().equals(data.defaultId()));
            list.add(o);
        }
        return list;
    }

    /** Seller profile and metrics for the snapshot card in the UI. */
    @GetMapping("/seller")
    public JsonNode seller(@RequestParam(required = false) String id) {
        JsonNode s = data.seller(id);
        ObjectNode out = om.createObjectNode();
        out.set("seller", data.profile(s));
        out.set("metrics", s.path("metrics"));
        if (s.has("traffic")) {
            out.set("traffic", s.path("traffic"));
        }
        return out;
    }

    @ExceptionHandler(KamException.class)
    public ResponseEntity<Map<String, String>> handle(KamException e) {
        return ResponseEntity.status(502).body(Map.of("error", e.getMessage()));
    }
}
