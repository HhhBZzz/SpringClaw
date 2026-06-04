package com.springclaw.service.agent;

/**
 * Result of one backend-executed Agent capability.
 */
public record CapabilityResult(String capabilityId,
                               String toolset,
                               String status,
                               String summary,
                               String payload,
                               long durationMs,
                               String riskLevel) {

    public CapabilityResult {
        capabilityId = safe(capabilityId);
        toolset = safe(toolset);
        status = safe(status, "success");
        summary = safe(summary);
        payload = safe(payload);
        durationMs = Math.max(0L, durationMs);
        riskLevel = safe(riskLevel, "read");
    }

    public boolean successful() {
        return "success".equalsIgnoreCase(status);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
