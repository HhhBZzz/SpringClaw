package com.springclaw.runtime.contract;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable terminal result projected to every transport.
 *
 * <p>Produced by {@code AnswerComposer} for {@link RunStatus#COMPLETED} and
 * {@link RunStatus#DEGRADED}, or by {@code FailureResultFactory} for
 * {@link RunStatus#FAILED}. Once persisted it must not be replaced — see
 * unified-runtime architecture spec § 7.8.
 */
public record RunResult(
        String runId,
        RunStatus status,
        String answer,
        AnswerKind answerKind,
        String modelProvider,
        String modelId,
        List<String> evidenceRefs,
        List<String> toolInvocationIds,
        double quality,
        Map<String, Long> usage,
        String failureCode,
        String failureMessage,
        Instant completedAt
) {
    public enum AnswerKind {
        FINAL, DEGRADED, FAILURE
    }

    public RunResult {
        runId = requireText(runId, "runId");
        status = Objects.requireNonNull(status, "status");
        if (!status.isTerminal()) {
            throw new IllegalArgumentException("RunResult status must be terminal");
        }
        if (status == RunStatus.FAILED && (failureCode == null || failureCode.isBlank())) {
            throw new IllegalArgumentException("failureCode is required for FAILED result");
        }
        if (status != RunStatus.FAILED && answerKind == AnswerKind.FAILURE) {
            throw new IllegalArgumentException("FAILURE answerKind requires FAILED status");
        }
        answerKind = Objects.requireNonNull(answerKind, "answerKind");
        answer = answer == null ? "" : answer;
        modelProvider = modelProvider == null ? "" : modelProvider;
        modelId = modelId == null ? "" : modelId;
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        toolInvocationIds = toolInvocationIds == null ? List.of() : List.copyOf(toolInvocationIds);
        usage = usage == null ? Map.of() : Map.copyOf(usage);
        failureCode = failureCode == null ? "" : failureCode;
        failureMessage = failureMessage == null ? "" : failureMessage;
        if (quality < 0.0 || quality > 1.0) {
            throw new IllegalArgumentException("quality must be between 0 and 1");
        }
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
