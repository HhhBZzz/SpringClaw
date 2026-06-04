package com.springclaw.service.agent;

/**
 * Result of one backend-executed Agent capability.
 */
public record AgentCapabilityResult(String capabilityId,
                                    String status,
                                    String summary,
                                    String payload) {

    public AgentCapabilityResult {
        capabilityId = safe(capabilityId);
        status = safe(status).isBlank() ? "success" : safe(status);
        summary = safe(summary);
        payload = safe(payload);
    }

    public boolean success() {
        return "success".equalsIgnoreCase(status);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
