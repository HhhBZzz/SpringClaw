package com.springclaw.service.agent.lifecycle;

public record RequestIdentity(String channel, String userId, String responseMode) {
    public RequestIdentity {
        channel = safe(channel, "api");
        userId = safe(userId, "");
        responseMode = safe(responseMode, "agent");
    }

    private static String safe(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? fallback : text;
    }
}
