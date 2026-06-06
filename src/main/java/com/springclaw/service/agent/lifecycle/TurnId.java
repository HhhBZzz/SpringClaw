package com.springclaw.service.agent.lifecycle;

public record TurnId(String sessionKey, String requestId) {
    public TurnId {
        sessionKey = safe(sessionKey);
        requestId = safe(requestId);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
