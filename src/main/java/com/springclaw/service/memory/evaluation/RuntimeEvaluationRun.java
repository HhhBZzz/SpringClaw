package com.springclaw.service.memory.evaluation;

import java.time.Instant;

public record RuntimeEvaluationRun(
        Long id,
        String evaluationType,
        String schemaVersion,
        boolean enabled,
        int total,
        int passed,
        int failed,
        int skipped,
        String resultJson,
        Instant createdAt
) {
    public RuntimeEvaluationRun {
        evaluationType = requireText(evaluationType, "evaluationType");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        if (total < 0 || passed < 0 || failed < 0 || skipped < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        resultJson = requireText(resultJson, "resultJson");
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
