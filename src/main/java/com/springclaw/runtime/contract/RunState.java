package com.springclaw.runtime.contract;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * requires a non-empty {@code pendingProposalId}; {@code COMPLETED}/{@code DEGRADED}
 * require a {@link RunResult}; {@code FAILED} requires a typed {@link Failure}.
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
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be >= 1");
        }
        toolInvocations = toolInvocations == null ? List.of() : List.copyOf(toolInvocations);
        usage = usage == null ? Map.of() : Map.copyOf(usage);
        pendingProposalId = pendingProposalId == null ? "" : pendingProposalId;
        strategyId = strategyId == null ? "" : strategyId;

        if (status == RunStatus.WAITING_CONFIRMATION && pendingProposalId.isBlank()) {
            throw new IllegalArgumentException("pendingProposalId is required for WAITING_CONFIRMATION");
        }
        if ((status == RunStatus.COMPLETED || status == RunStatus.DEGRADED)) {
            if (result == null) {
                throw new IllegalArgumentException("RunResult is required for " + status);
            }
            if (finishedAt == null) {
                throw new IllegalArgumentException("finishedAt is required for " + status);
            }
        }
        if (status == RunStatus.FAILED) {
            if (failure == null) {
                throw new IllegalArgumentException("failure is required for FAILED");
            }
            if (finishedAt == null) {
                throw new IllegalArgumentException("finishedAt is required for FAILED");
            }
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    /** Mutable builder for constructing RunState fixtures and transitions. */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns a builder pre-populated with this state's values (for transitions). */
    public Builder toBuilder() {
        return new Builder()
                .runId(runId)
                .requestId(requestId)
                .revision(revision)
                .status(status)
                .sessionKey(sessionKey)
                .channel(channel)
                .userId(userId)
                .roleCodeAtAcceptance(roleCodeAtAcceptance)
                .originalMessage(originalMessage)
                .responseMode(responseMode)
                .acceptedAt(acceptedAt)
                .startedAt(startedAt)
                .updatedAt(updatedAt)
                .finishedAt(finishedAt)
                .deadlineAt(deadlineAt)
                .contextSnapshot(contextSnapshot)
                .executionDecision(executionDecision)
                .strategyId(strategyId)
                .attempt(attempt)
                .pendingProposalId(pendingProposalId)
                .toolInvocations(toolInvocations)
                .completionDecision(completionDecision)
                .result(result)
                .usage(usage)
                .failure(failure);
    }

    public static final class Builder {
        private String runId;
        private String requestId;
        private long revision;
        private RunStatus status;
        private String sessionKey;
        private String channel;
        private String userId;
        private String roleCodeAtAcceptance;
        private String originalMessage;
        private String responseMode;
        private Instant acceptedAt;
        private Instant startedAt;
        private Instant updatedAt;
        private Instant finishedAt;
        private Instant deadlineAt;
        private ContextSnapshot contextSnapshot;
        private ExecutionDecision executionDecision;
        private String strategyId;
        private int attempt;
        private String pendingProposalId;
        private List<ToolInvocation> toolInvocations = List.of();
        private CompletionDecision completionDecision;
        private RunResult result;
        private Map<String, Long> usage = Map.of();
        private Failure failure;

        public Builder runId(String v) { this.runId = v; return this; }
        public Builder requestId(String v) { this.requestId = v; return this; }
        public Builder revision(long v) { this.revision = v; return this; }
        public Builder status(RunStatus v) { this.status = v; return this; }
        public Builder sessionKey(String v) { this.sessionKey = v; return this; }
        public Builder channel(String v) { this.channel = v; return this; }
        public Builder userId(String v) { this.userId = v; return this; }
        public Builder roleCodeAtAcceptance(String v) { this.roleCodeAtAcceptance = v; return this; }
        public Builder originalMessage(String v) { this.originalMessage = v; return this; }
        public Builder responseMode(String v) { this.responseMode = v; return this; }
        public Builder acceptedAt(Instant v) { this.acceptedAt = v; return this; }
        public Builder startedAt(Instant v) { this.startedAt = v; return this; }
        public Builder updatedAt(Instant v) { this.updatedAt = v; return this; }
        public Builder finishedAt(Instant v) { this.finishedAt = v; return this; }
        public Builder deadlineAt(Instant v) { this.deadlineAt = v; return this; }
        public Builder contextSnapshot(ContextSnapshot v) { this.contextSnapshot = v; return this; }
        public Builder executionDecision(ExecutionDecision v) { this.executionDecision = v; return this; }
        public Builder strategyId(String v) { this.strategyId = v; return this; }
        public Builder attempt(int v) { this.attempt = v; return this; }
        public Builder pendingProposalId(String v) { this.pendingProposalId = v; return this; }
        public Builder toolInvocations(List<ToolInvocation> v) { this.toolInvocations = v; return this; }
        public Builder completionDecision(CompletionDecision v) { this.completionDecision = v; return this; }
        public Builder result(RunResult v) { this.result = v; return this; }
        public Builder usage(Map<String, Long> v) { this.usage = v; return this; }
        public Builder failure(Failure v) { this.failure = v; return this; }

        public RunState build() {
            return new RunState(
                    runId, requestId, revision, status, sessionKey, channel, userId,
                    roleCodeAtAcceptance, originalMessage, responseMode, acceptedAt,
                    startedAt, updatedAt, finishedAt, deadlineAt, contextSnapshot,
                    executionDecision, strategyId, attempt, pendingProposalId,
                    toolInvocations, completionDecision, result, usage, failure);
        }
    }
}
