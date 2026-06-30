package com.springclaw.service.memory.evaluation;

import java.util.List;

public record MemoryProviderEvaluationCase(
        String caseId,
        String title,
        String status,
        String summary,
        List<String> evidence
) {
    public MemoryProviderEvaluationCase {
        caseId = requireText(caseId, "caseId");
        title = requireText(title, "title");
        status = requireText(status, "status");
        summary = requireText(summary, "summary");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
