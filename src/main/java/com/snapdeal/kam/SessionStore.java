package com.snapdeal.kam;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionStore {
    private static final int MAX_SESSIONS = 500;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper om;

    public SessionStore(ObjectMapper om) {
        this.om = om;
    }

    public Session get(String id) {
        if (sessions.size() > MAX_SESSIONS) {
            sessions.clear(); // demo-grade cleanup; a real service would expire by age
        }
        return sessions.computeIfAbsent(id, k -> new Session(k, om));
    }
}
