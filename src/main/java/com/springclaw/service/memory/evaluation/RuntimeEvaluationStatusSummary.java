package com.springclaw.service.memory.evaluation;

public record RuntimeEvaluationStatusSummary(
        String status,
        String summary,
        RuntimeEvaluationRun redlineLatest,
        RuntimeEvaluationRun providerLatest
) {
    public RuntimeEvaluationStatusSummary {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank");
        }
        status = status.trim();
        summary = summary.trim();
    }
}
