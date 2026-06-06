package com.springclaw.service.agent.lifecycle;

public record TurnRequest(String sessionKey,
                          String channel,
                          String userId,
                          String requestId,
                          String message,
                          String responseMode) {
    public TurnRequest {
        sessionKey = safe(sessionKey);
        channel = defaultValue(channel, "api");
        userId = safe(userId);
        requestId = safe(requestId);
        message = safe(message);
        responseMode = defaultValue(responseMode, "agent");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultValue(String value, String fallback) {
        String text = safe(value);
        return text.isEmpty() ? fallback : text;
    }
}
