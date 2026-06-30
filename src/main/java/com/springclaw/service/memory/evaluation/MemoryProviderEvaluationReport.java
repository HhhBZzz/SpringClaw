package com.springclaw.service.memory.evaluation;

import java.time.Instant;
import java.util.List;

public record MemoryProviderEvaluationReport(
        String schema,
        boolean enabled,
        int total,
        int passed,
        int failed,
        int skipped,
        List<MemoryProviderEvaluationCase> cases,
        Instant evaluatedAt
) {
    public MemoryProviderEvaluationReport {
        if (schema == null || schema.isBlank()) {
            throw new IllegalArgumentException("schema must not be blank");
        }
        if (total < 0 || passed < 0 || failed < 0 || skipped < 0) {
            throw new IllegalArgumentException("counts must not be negative");
        }
        cases = cases == null ? List.of() : List.copyOf(cases);
        evaluatedAt = evaluatedAt == null ? Instant.now() : evaluatedAt;
    }
}
