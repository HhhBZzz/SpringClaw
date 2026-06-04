package com.springclaw.service.agent;

import java.util.List;

/**
 * One normalized routing decision for a user request.
 */
public record AgentDecision(String intent,
                            String executionPath,
                            List<String> selectedCapabilities,
                            String riskLevel,
                            boolean requiresConfirmation,
                            String reason) {

    public AgentDecision {
        intent = normalize(intent, "general");
        executionPath = normalize(executionPath, "basic_model");
        selectedCapabilities = selectedCapabilities == null ? List.of() : List.copyOf(selectedCapabilities);
        riskLevel = normalize(riskLevel, "read");
        reason = reason == null ? "" : reason;
    }

    public static AgentDecision general(String reason) {
        return new AgentDecision("general", "basic_model", List.of(), "read", false, reason);
    }

    public static AgentDecision clarify(String reason) {
        return new AgentDecision("unknown", "ask_clarification", List.of(), "read", false, reason);
    }

    public boolean isGeneral() {
        return "general".equalsIgnoreCase(intent);
    }

    public boolean isTaskDraft() {
        return "task_draft".equalsIgnoreCase(executionPath);
    }

    public boolean isDangerous() {
        return "dangerous".equalsIgnoreCase(riskLevel);
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim().toLowerCase().replace('-', '_');
    }
}
