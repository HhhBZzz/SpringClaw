package com.springclaw.runtime.contract;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical aggregate for one user request from acceptance through a terminal
 * result or a suspended confirmation boundary.
 *
 * <p>Only {@code RunCoordinator} (introduced later) reduces this aggregate from
 * accepted {@link RunEvent} values. The aggregate itself has no direct database
 * access, no model/tool invocation methods, and no transport emitters — see
 * unified-runtime architecture spec § 7.2.
 *
 * <p>Invariants: {@code runId == requestId}; {@code revision} increases per
 * accepted transition; terminal states are immutable; {@code WAITING_CONFIRMATION}
 * requires a non-empty {@code pendingProposalId}; terminal state, completion
 * decision, and result values must agree; {@code FAILED} requires a typed
 * {@link Failure}.
 */
public record RunState(
        String runId,
        String requestId,
        long revision,
        RunStatus status,
        String sessionKey,
        String channel,
        String userId,
        String roleCodeAtAcceptance,
        String originalMessage,
        String responseMode,
        Instant acceptedAt,
        Instant startedAt,
        Instant updatedAt,
        Instant finishedAt,
        Instant deadlineAt,
        ContextSnapshot contextSnapshot,
        ExecutionDecision executionDecision,
        String strategyId,
        int attempt,
        String pendingProposalId,
        List<ToolInvocation> toolInvocations,
        CompletionDecision completionDecision,
        RunResult result,
        Map<String, Long> usage,
        Failure failure
) {
    public record Failure(String code, String message, boolean retryable) {
        public Failure {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("failure.code must not be blank");
            }
            message = message == null ? "" : message;
        }
    }

    public RunState {
        runId = requireText(runId, "runId");
        requestId = requireText(requestId, "requestId");
        if (!runId.equals(requestId)) {
            throw new IllegalArgumentException("runId and requestId must be equal");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
        status = Objects.requireNonNull(status, "status");
        sessionKey = requireText(sessionKey, "sessionKey");
        channel = requireText(channel, "channel");
        userId = requireText(userId, "userId");
        roleCodeAtAcceptance = requireText(roleCodeAtAcceptance, "roleCodeAtAcceptance");
        originalMessage = Objects.requireNonNullElse(originalMessage, "");
        responseMode = requireText(responseMode, "responseMode");
        acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        if (updatedAt.isBefore(acceptedAt)) {
            throw new IllegalArgumentException("updatedAt must not be before acceptedAt");
        }
        if (deadlineAt.isBefore(acceptedAt)) {
            throw new IllegalArgumentException("deadlineAt must not be before acceptedAt");
        }
        if (startedAt != null
                && (startedAt.isBefore(acceptedAt) || startedAt.isAfter(updatedAt))) {
            throw new IllegalArgumentException(
                    "startedAt must be between acceptedAt and updatedAt"
            );
        }
        if (finishedAt != null
                && (finishedAt.isBefore(acceptedAt)
                || (startedAt != null && finishedAt.isBefore(startedAt))
                || finishedAt.isAfter(updatedAt))) {
            throw new IllegalArgumentException(
                    "finishedAt must be between acceptedAt and updatedAt and not before startedAt"
            );
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        toolInvocations = copyToolInvocations(toolInvocations);
        usage = usage == null ? Map.of() : Map.copyOf(usage);
        pendingProposalId = pendingProposalId == null ? "" : pendingProposalId;
        strategyId = strategyId == null ? "" : strategyId;

        requireMatchingRunId(runId, contextSnapshot == null ? null : contextSnapshot.runId(), "ContextSnapshot");
        requireMatchingRunId(runId, executionDecision == null ? null : executionDecision.runId(), "ExecutionDecision");
        Set<String> invocationIds = new HashSet<>();
        Set<String> idempotencyKeys = new HashSet<>();
        for (ToolInvocation invocation : toolInvocations) {
            requireMatchingRunId(runId, invocation.runId(), "ToolInvocation");
            if (invocation.attempt() > attempt) {
                throw new IllegalArgumentException(
                        "ToolInvocation attempt must not exceed RunState attempt"
                );
            }
            if (!invocationIds.add(invocation.invocationId())) {
                throw new IllegalArgumentException(
                        "toolInvocations must not contain duplicate invocationId"
                );
            }
            if (!idempotencyKeys.add(invocation.idempotencyKey())) {
                throw new IllegalArgumentException(
                        "toolInvocations must not contain duplicate idempotencyKey"
                );
            }
        }
        requireMatchingRunId(
                runId,
                completionDecision == null ? null : completionDecision.runId(),
                "CompletionDecision"
        );
        requireMatchingRunId(runId, result == null ? null : result.runId(), "RunResult");

        if (status == RunStatus.WAITING_CONFIRMATION && pendingProposalId.isBlank()) {
            throw new IllegalArgumentException("pendingProposalId is required for WAITING_CONFIRMATION");
        }
        if (!status.isTerminal() && finishedAt != null) {
            throw new IllegalArgumentException("finishedAt is not allowed for nonterminal status");
        }
        if (!status.isTerminal() && failure != null) {
            throw new IllegalArgumentException("failure is not allowed for nonterminal status");
        }
        if (!status.isTerminal() && result != null) {
            throw new IllegalArgumentException("RunResult is not allowed for nonterminal status");
        }
        if (status.isTerminal() && result != null && result.status() != status) {
            throw new IllegalArgumentException("RunResult status must equal RunState status");
        }
        switch (status) {
            case COMPLETED -> requireTerminalEvidence(
                    status,
                    finishedAt,
                    completionDecision,
                    CompletionDecision.Outcome.COMPLETE,
                    result
            );
            case DEGRADED -> requireTerminalEvidence(
                    status,
                    finishedAt,
                    completionDecision,
                    CompletionDecision.Outcome.DEGRADE,
                    result
            );
            case FAILED -> {
                if (failure == null) {
                    throw new IllegalArgumentException("failure is required for FAILED");
                }
                if (finishedAt == null) {
                    throw new IllegalArgumentException("finishedAt is required for FAILED");
                }
                if (completionDecision != null
                        && completionDecision.outcome() != CompletionDecision.Outcome.FAIL) {
                    throw new IllegalArgumentException(
                            "CompletionDecision FAIL is required when present for FAILED"
                    );
                }
            }
            default -> {
                // Non-terminal states carry partial evidence as the run progresses.
            }
        }
        if ((status == RunStatus.COMPLETED || status == RunStatus.DEGRADED)
                && failure != null) {
            throw new IllegalArgumentException("failure is not allowed for " + status);
        }
        if (result != null
                && finishedAt != null
                && !result.completedAt().equals(finishedAt)) {
            throw new IllegalArgumentException(
                    "RunResult completedAt must equal RunState finishedAt"
            );
        }
        if (status == RunStatus.FAILED
                && result != null
                && !result.failureCode().equals(failure.code())) {
            throw new IllegalArgumentException(
                    "RunResult failureCode must equal RunState failure code"
            );
        }
    }

    private static void requireMatchingRunId(
            String runId,
            String nestedRunId,
            String contractName
    ) {
        if (nestedRunId != null && !runId.equals(nestedRunId)) {
            throw new IllegalArgumentException(contractName + " runId must match RunState runId");
        }
    }

    private static List<ToolInvocation> copyToolInvocations(List<ToolInvocation> source) {
        if (source == null) {
            return List.of();
        }
        if (source.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("toolInvocations must not contain null");
        }
        return List.copyOf(source);
    }

    private static void requireTerminalEvidence(
            RunStatus status,
            Instant finishedAt,
            CompletionDecision completionDecision,
            CompletionDecision.Outcome requiredOutcome,
            RunResult result
    ) {
        if (result == null) {
            throw new IllegalArgumentException("RunResult is required for " + status);
        }
        if (completionDecision == null) {
            throw new IllegalArgumentException("CompletionDecision is required for " + status);
        }
        if (completionDecision.outcome() != requiredOutcome) {
            throw new IllegalArgumentException(
                    "CompletionDecision " + requiredOutcome + " is required for " + status
            );
        }
        if (finishedAt == null) {
            throw new IllegalArgumentException("finishedAt is required for " + status);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
