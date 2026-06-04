package com.springclaw.service.agent;

import java.util.List;

/**
 * Concrete toolset plan derived from an Agent decision.
 */
public record CapabilityPlan(String intent,
                             String executionPath,
                             List<String> selectedCapabilities,
                             List<String> toolsets,
                             String riskLevel,
                             boolean requiresConfirmation,
                             String reason) {

    public CapabilityPlan {
        intent = safe(intent, "unknown");
        executionPath = safe(executionPath, "ask_clarification");
        selectedCapabilities = selectedCapabilities == null ? List.of() : List.copyOf(selectedCapabilities);
        toolsets = toolsets == null ? List.of() : List.copyOf(toolsets);
        riskLevel = safe(riskLevel, "read");
        reason = safe(reason, "");
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
