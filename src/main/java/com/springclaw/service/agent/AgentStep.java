package com.springclaw.service.agent;

/**
 * Product-facing Agent run step used by trace and inspector views.
 */
public record AgentStep(String stepName,
                        String type,
                        String status,
                        String detail,
                        long durationMs) {

    public AgentStep {
        stepName = safe(stepName);
        type = safe(type, "agent");
        status = safe(status, "success");
        detail = safe(detail);
        durationMs = Math.max(0L, durationMs);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
