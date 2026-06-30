package com.springclaw.service.memory.evaluation;

import java.util.List;
import java.util.Objects;

public record MemoryEffectivenessRedlineReportCase(
        String caseId,
        String title,
        boolean passed,
        String summary,
        List<String> evidence
) {
    public MemoryEffectivenessRedlineReportCase {
        caseId = requireText(caseId, "caseId");
        title = requireText(title, "title");
        summary = requireText(summary, "summary");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return Objects.toString(value).trim();
    }
}
