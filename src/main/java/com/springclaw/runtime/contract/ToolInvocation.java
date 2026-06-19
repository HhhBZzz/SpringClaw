package com.springclaw.runtime.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Immutable description and lifecycle reference for one actual capability operation.
 *
 * <p>Frozen by {@code ToolGateway} at the concrete invocation boundary: tool name,
 * canonical arguments, argument hash, target paths, risk, user, request, and Git
 * baseline are all captured and must not mutate after proposal creation. See
 * unified-runtime architecture spec § 7.6.
 *
 * <p>{@code riskLevel} here is the ACTUAL invocation risk classified at the
 * boundary, not the predicted {@link ExecutionDecision#riskSummary()}. Only this
 * risk authorizes a side effect.
 */
public record ToolInvocation(
        String invocationId,
        String runId,
        int attempt,
        String capabilityId,
        String operationId,
        String toolName,
        String toolsetId,
        String canonicalArgumentsJson,
        String argumentsHash,
        RiskLevel riskLevel,
        List<String> targetPaths,
        List<String> expectedEvidence,
        String idempotencyKey,
        Status status,
        String proposalId,
        Instant startedAt,
        Instant finishedAt,
        Outcome outcome
) {
    public enum RiskLevel {
        READ, WRITE, SIDE_EFFECT, DANGEROUS
    }

    public enum Status {
        REQUESTED, WAITING_CONFIRMATION, APPROVED, RUNNING, SUCCEEDED, FAILED, DENIED
    }

    public record Outcome(
            boolean success,
            String code,
            String summary,
            List<String> evidenceRefs,
            Instant completedAt
    ) {
        public Outcome {
            code = code == null ? "" : code;
            summary = summary == null ? "" : summary;
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            completedAt = Objects.requireNonNull(completedAt, "completedAt");
        }
    }

    public ToolInvocation {
        invocationId = requireText(invocationId, "invocationId");
        runId = requireText(runId, "runId");
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        capabilityId = requireText(capabilityId, "capabilityId");
        operationId = requireText(operationId, "operationId");
        toolName = requireText(toolName, "toolName");
        toolsetId = requireText(toolsetId, "toolsetId");
        canonicalArgumentsJson = requireText(canonicalArgumentsJson, "canonicalArgumentsJson");
        argumentsHash = requireText(argumentsHash, "argumentsHash");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
        targetPaths = targetPaths == null ? List.of() : List.copyOf(targetPaths);
        expectedEvidence = expectedEvidence == null ? List.of() : List.copyOf(expectedEvidence);
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        status = Objects.requireNonNull(status, "status");
        if (status == Status.WAITING_CONFIRMATION && (proposalId == null || proposalId.isBlank())) {
            throw new IllegalArgumentException("proposalId is required when status is WAITING_CONFIRMATION");
        }
        proposalId = proposalId == null ? "" : proposalId;

        switch (status) {
            case REQUESTED, WAITING_CONFIRMATION, APPROVED -> {
                requireNoCompletionData(status, finishedAt, outcome);
            }
            case RUNNING -> {
                if (startedAt == null) {
                    throw new IllegalArgumentException("startedAt is required for RUNNING");
                }
                requireNoCompletionData(status, finishedAt, outcome);
            }
            case SUCCEEDED -> {
                if (startedAt == null) {
                    throw new IllegalArgumentException("startedAt is required for SUCCEEDED");
                }
                requireFinishedOutcome(status, finishedAt, outcome);
                if (!outcome.success()) {
                    throw new IllegalArgumentException("SUCCEEDED requires outcome.success=true");
                }
            }
            case FAILED, DENIED -> {
                requireFinishedOutcome(status, finishedAt, outcome);
                if (outcome.success()) {
                    throw new IllegalArgumentException(status + " requires outcome.success=false");
                }
            }
        }
        if (outcome != null && !outcome.completedAt().equals(finishedAt)) {
            throw new IllegalArgumentException("outcome.completedAt must equal finishedAt");
        }
    }

    private static void requireNoCompletionData(Status status, Instant finishedAt, Outcome outcome) {
        if (finishedAt != null) {
            throw new IllegalArgumentException("finishedAt is not allowed for " + status);
        }
        if (outcome != null) {
            throw new IllegalArgumentException("outcome is not allowed for " + status);
        }
    }

    private static void requireFinishedOutcome(Status status, Instant finishedAt, Outcome outcome) {
        if (finishedAt == null) {
            throw new IllegalArgumentException("finishedAt is required for " + status);
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome is required for " + status);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
