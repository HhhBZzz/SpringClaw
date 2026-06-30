package com.springclaw.service.memory.evaluation;

import java.util.List;

public record RuntimeEvaluationGateReport(
        String gateStatus,
        boolean gatePassed,
        String gateReason,
        String trend,
        String trendReason,
        RuntimeEvaluationStatusSummary health,
        List<RuntimeEvaluationRun> redlineRecent,
        List<RuntimeEvaluationRun> providerRecent
) {
    public RuntimeEvaluationGateReport {
        gateStatus = requireText(gateStatus, "gateStatus");
        gateReason = requireText(gateReason, "gateReason");
        trend = requireText(trend, "trend");
        trendReason = requireText(trendReason, "trendReason");
        if (health == null) {
            throw new IllegalArgumentException("health must not be null");
        }
        redlineRecent = redlineRecent == null ? List.of() : List.copyOf(redlineRecent);
        providerRecent = providerRecent == null ? List.of() : List.copyOf(providerRecent);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
