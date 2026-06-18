package com.springclaw.runtime.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Sole declaration of whether a run may finish, retry, degrade, fail, or wait.
 *
 * <p>Produced only by {@code CompletionVerifier}. No other component may infer
 * completion from {@code finishReason}, non-empty text, missing tool calls, or
 * {@code TASK_COMPLETE} alone — see unified-runtime architecture spec § 7.7.
 */
public record CompletionDecision(
        String runId,
        Outcome outcome,
        String reasonCode,
        String summary,
        List<String> evidenceRefs,
        List<String> missingEvidence,
        boolean retryAllowed,
        int nextAttempt,
        double quality,
        Instant decidedAt
) {
    public enum Outcome {
        COMPLETE, RETRY, DEGRADE, FAIL, WAIT_FOR_CONFIRMATION
    }

    public CompletionDecision {
        runId = requireText(runId, "runId");
        outcome = Objects.requireNonNull(outcome, "outcome");
        reasonCode = requireText(reasonCode, "reasonCode");
        summary = summary == null ? "" : summary;
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
        if (quality < 0.0 || quality > 1.0) {
            throw new IllegalArgumentException("quality must be between 0 and 1");
        }
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
        if (outcome == Outcome.RETRY) {
            if (!retryAllowed) {
                throw new IllegalArgumentException("retryAllowed must be true for RETRY outcome");
            }
            if (nextAttempt < 2) {
                throw new IllegalArgumentException("nextAttempt must be >= 2 for RETRY outcome");
            }
        } else {
            // Non-retry outcomes normalize nextAttempt to 0.
            nextAttempt = 0;
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
