package com.springclaw.service.agent.lifecycle;

import java.util.List;

public record IntentDecision(String intent,
                             String executionPath,
                             List<String> capabilities,
                             double confidence,
                             String reason) {
    public IntentDecision {
        intent = safe(intent, "unknown");
        executionPath = safe(executionPath, "");
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        reason = safe(reason, "");
    }

    private static String safe(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isEmpty() ? fallback : text;
    }
}
