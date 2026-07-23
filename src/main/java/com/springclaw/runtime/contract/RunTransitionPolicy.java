package com.springclaw.runtime.contract;

import java.util.Objects;

/**
 * Validates aggregate-to-aggregate lifecycle transitions.
 *
 * <p>This is a pure validation helper — it does not perform the transition. The
 * only runtime owner allowed to apply transitions is {@code RunCoordinator}
 * (introduced in a later migration phase). See unified-runtime architecture
 * spec § 8.1.
 */
public final class RunTransitionPolicy {

    private RunTransitionPolicy() {
    }

    public static void validate(RunState previous, RunState next) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        if (previous.status().isTerminal()) {
            throw new IllegalStateException("terminal run state is immutable: " + previous.status());
        }
        if (!previous.runId().equals(next.runId())
                || !previous.requestId().equals(next.requestId())) {
            throw new IllegalStateException("run identity cannot change");
        }
        requireEqual(previous.sessionKey(), next.sessionKey(), "sessionKey");
        requireEqual(previous.channel(), next.channel(), "channel");
        requireEqual(previous.userId(), next.userId(), "userId");
        requireEqual(
                previous.sessionAccessClaim(),
                next.sessionAccessClaim(),
                "sessionAccessClaim"
        );
        requireEqual(
                previous.roleCodeAtAcceptance(),
                next.roleCodeAtAcceptance(),
                "roleCodeAtAcceptance"
        );
        requireEqual(previous.paradigm(), next.paradigm(), "paradigm");
        requireEqual(previous.originalMessage(), next.originalMessage(), "originalMessage");
        requireEqual(previous.responseMode(), next.responseMode(), "responseMode");
        requireEqual(previous.acceptedAt(), next.acceptedAt(), "acceptedAt");
        requireEqual(previous.deadlineAt(), next.deadlineAt(), "deadlineAt");
        if (next.revision() != previous.revision() + 1) {
            throw new IllegalStateException("revision must increase by exactly one");
        }
        if (!previous.status().canTransitionTo(next.status())) {
            throw new IllegalStateException(
                    "invalid run transition: " + previous.status() + " -> " + next.status()
            );
        }
        if (next.updatedAt().isBefore(previous.updatedAt())) {
            throw new IllegalStateException("updatedAt cannot move backwards");
        }
        validateStartedAt(previous, next);
        boolean verificationRetry = previous.status() == RunStatus.VERIFYING
                && next.status() == RunStatus.DECIDED;
        if (!verificationRetry && next.attempt() != previous.attempt()) {
            throw new IllegalStateException("attempt cannot change outside verification retry");
        }
        validateContextSnapshot(previous, next);
        validateExecutionDecision(previous, next, verificationRetry);
        validateStrategy(previous, next, verificationRetry);
        validateToolInvocations(previous, next, verificationRetry);
        validateUsage(previous, next);
        if (next.status() == RunStatus.FAILED && next.failure() == null) {
            throw new IllegalStateException("failure is required for transition to FAILED");
        }
        if (previous.status() == RunStatus.CREATED
                && next.status() == RunStatus.CONTEXT_READY
                && next.contextSnapshot() == null) {
            throw new IllegalStateException(
                    "contextSnapshot is required for CREATED -> CONTEXT_READY"
            );
        }
        if (previous.status() == RunStatus.CONTEXT_READY
                && next.status() == RunStatus.DECIDED
                && next.executionDecision() == null) {
            throw new IllegalStateException(
                    "executionDecision is required for CONTEXT_READY -> DECIDED"
            );
        }
        if (previous.status() == RunStatus.DECIDED
                && next.status() == RunStatus.RUNNING
                && next.strategyId().isBlank()) {
            throw new IllegalStateException("strategyId is required for DECIDED -> RUNNING");
        }
        if (verificationRetry) {
            CompletionDecision completionDecision = next.completionDecision();
            if (completionDecision == null
                    || completionDecision.outcome() != CompletionDecision.Outcome.RETRY) {
                throw new IllegalStateException(
                        "CompletionDecision RETRY is required for VERIFYING -> DECIDED"
                );
            }
            if (next.attempt() != previous.attempt() + 1) {
                throw new IllegalStateException(
                        "attempt must increase by exactly one for VERIFYING -> DECIDED"
                );
            }
            if (completionDecision.nextAttempt() != next.attempt()) {
                throw new IllegalStateException(
                        "CompletionDecision nextAttempt must equal next state attempt"
                );
            }
        }
        if (previous.status() == RunStatus.VERIFYING
                && next.status() == RunStatus.FAILED) {
            CompletionDecision completionDecision = next.completionDecision();
            if (completionDecision == null
                    || completionDecision.outcome() != CompletionDecision.Outcome.FAIL) {
                throw new IllegalStateException(
                        "CompletionDecision FAIL is required for VERIFYING -> FAILED"
                );
            }
        }
    }

    private static void validateContextSnapshot(RunState previous, RunState next) {
        ContextSnapshot previousSnapshot = previous.contextSnapshot();
        ContextSnapshot nextSnapshot = next.contextSnapshot();
        if (previousSnapshot != null) {
            requireEqual(previousSnapshot, nextSnapshot, "contextSnapshot");
            return;
        }
        if (nextSnapshot != null
                && !(previous.status() == RunStatus.CREATED
                && next.status() == RunStatus.CONTEXT_READY)) {
            throw new IllegalStateException(
                    "contextSnapshot may only be introduced by CREATED -> CONTEXT_READY"
            );
        }
    }

    private static void validateExecutionDecision(
            RunState previous,
            RunState next,
            boolean verificationRetry
    ) {
        ExecutionDecision previousDecision = previous.executionDecision();
        if (previousDecision == null) {
            if (next.executionDecision() != null
                    && !(previous.status() == RunStatus.CONTEXT_READY
                    && next.status() == RunStatus.DECIDED)) {
                throw new IllegalStateException(
                        "executionDecision may only be introduced by CONTEXT_READY -> DECIDED"
                );
            }
            return;
        }
        if (next.executionDecision() == null) {
            throw new IllegalStateException("executionDecision cannot disappear");
        }
        if (!verificationRetry) {
            requireEqual(previousDecision, next.executionDecision(), "executionDecision");
        }
    }

    private static void validateStrategy(
            RunState previous,
            RunState next,
            boolean verificationRetry
    ) {
        if (verificationRetry) {
            if (!next.strategyId().isBlank()) {
                throw new IllegalStateException(
                        "strategyId must be blank for VERIFYING -> DECIDED retry"
                );
            }
            return;
        }
        boolean selectsStrategy = previous.status() == RunStatus.DECIDED
                && next.status() == RunStatus.RUNNING
                && previous.strategyId().isBlank();
        if (!selectsStrategy) {
            requireEqual(previous.strategyId(), next.strategyId(), "strategyId");
        }
    }

    private static void validateToolInvocations(
            RunState previous,
            RunState next,
            boolean verificationRetry
    ) {
        if (verificationRetry) {
            requireEqual(
                    previous.toolInvocations(),
                    next.toolInvocations(),
                    "toolInvocations"
            );
            return;
        }
        if (next.toolInvocations().size() < previous.toolInvocations().size()) {
            throw new IllegalStateException(
                    "toolInvocations cannot be removed"
            );
        }
        for (int index = 0; index < previous.toolInvocations().size(); index++) {
            validateToolInvocationProgression(
                    previous.toolInvocations().get(index),
                    next.toolInvocations().get(index)
            );
        }
        for (int index = previous.toolInvocations().size();
             index < next.toolInvocations().size();
             index++) {
            if (next.toolInvocations().get(index).attempt() != next.attempt()) {
                throw new IllegalStateException(
                        "new ToolInvocation attempt must equal next RunState attempt"
                );
            }
        }
    }

    private static void validateStartedAt(RunState previous, RunState next) {
        if (previous.startedAt() != null
                && !previous.startedAt().equals(next.startedAt())) {
            throw new IllegalStateException("startedAt cannot change or disappear");
        }
    }

    private static void validateToolInvocationProgression(
            ToolInvocation previous,
            ToolInvocation next
    ) {
        requireToolFieldEqual(previous.invocationId(), next.invocationId(), "invocationId");
        requireToolFieldEqual(previous.runId(), next.runId(), "runId");
        requireToolFieldEqual(previous.attempt(), next.attempt(), "attempt");
        requireToolFieldEqual(previous.capabilityId(), next.capabilityId(), "capabilityId");
        requireToolFieldEqual(previous.operationId(), next.operationId(), "operationId");
        requireToolFieldEqual(previous.toolName(), next.toolName(), "toolName");
        requireToolFieldEqual(previous.toolsetId(), next.toolsetId(), "toolsetId");
        requireToolFieldEqual(
                previous.canonicalArgumentsJson(),
                next.canonicalArgumentsJson(),
                "canonicalArgumentsJson"
        );
        requireToolFieldEqual(previous.argumentsHash(), next.argumentsHash(), "argumentsHash");
        requireToolFieldEqual(previous.riskLevel(), next.riskLevel(), "riskLevel");
        requireToolFieldEqual(previous.targetPaths(), next.targetPaths(), "targetPaths");
        requireToolFieldEqual(
                previous.expectedEvidence(),
                next.expectedEvidence(),
                "expectedEvidence"
        );
        requireToolFieldEqual(
                previous.idempotencyKey(),
                next.idempotencyKey(),
                "idempotencyKey"
        );
        validateToolStatus(previous.status(), next.status());
        validateProposalId(previous, next);
        validateInvocationStartedAt(previous, next);
        validateTerminalField(previous.finishedAt(), next.finishedAt(), next.status(), "finishedAt");
        validateTerminalField(previous.outcome(), next.outcome(), next.status(), "outcome");
    }

    private static void validateToolStatus(
            ToolInvocation.Status previous,
            ToolInvocation.Status next
    ) {
        boolean allowed = switch (previous) {
            case REQUESTED -> next == ToolInvocation.Status.REQUESTED
                    || next == ToolInvocation.Status.WAITING_CONFIRMATION
                    || next == ToolInvocation.Status.APPROVED
                    || next == ToolInvocation.Status.RUNNING
                    || next == ToolInvocation.Status.FAILED
                    || next == ToolInvocation.Status.DENIED;
            case WAITING_CONFIRMATION -> next == ToolInvocation.Status.WAITING_CONFIRMATION
                    || next == ToolInvocation.Status.APPROVED
                    || next == ToolInvocation.Status.FAILED
                    || next == ToolInvocation.Status.DENIED;
            case APPROVED -> next == ToolInvocation.Status.APPROVED
                    || next == ToolInvocation.Status.RUNNING
                    || next == ToolInvocation.Status.FAILED
                    || next == ToolInvocation.Status.DENIED;
            case RUNNING -> next == ToolInvocation.Status.RUNNING
                    || next == ToolInvocation.Status.SUCCEEDED
                    || next == ToolInvocation.Status.FAILED;
            case SUCCEEDED, FAILED, DENIED -> next == previous;
        };
        if (!allowed) {
            throw new IllegalStateException(
                    "toolInvocations contain invalid status progression: "
                            + previous + " -> " + next
            );
        }
    }

    private static void validateProposalId(
            ToolInvocation previous,
            ToolInvocation next
    ) {
        if (!previous.proposalId().isBlank()) {
            requireToolFieldEqual(previous.proposalId(), next.proposalId(), "proposalId");
            return;
        }
        if (!next.proposalId().isBlank()
                && next.status() != ToolInvocation.Status.WAITING_CONFIRMATION) {
            throw new IllegalStateException(
                    "toolInvocations proposalId may first appear only when entering WAITING_CONFIRMATION"
            );
        }
    }

    private static void validateInvocationStartedAt(
            ToolInvocation previous,
            ToolInvocation next
    ) {
        if (previous.startedAt() != null) {
            requireToolFieldEqual(previous.startedAt(), next.startedAt(), "startedAt");
        }
    }

    private static void validateTerminalField(
            Object previous,
            Object next,
            ToolInvocation.Status nextStatus,
            String field
    ) {
        if (previous != null) {
            requireToolFieldEqual(previous, next, field);
            return;
        }
        if (next != null
                && nextStatus != ToolInvocation.Status.SUCCEEDED
                && nextStatus != ToolInvocation.Status.FAILED
                && nextStatus != ToolInvocation.Status.DENIED) {
            throw new IllegalStateException(
                    "toolInvocations " + field + " may first appear only on terminal progression"
            );
        }
    }

    private static void requireToolFieldEqual(Object previous, Object next, String field) {
        if (!Objects.equals(previous, next)) {
            throw new IllegalStateException(
                    "toolInvocations cannot change " + field
            );
        }
    }

    private static void validateUsage(RunState previous, RunState next) {
        for (var entry : previous.usage().entrySet()) {
            Long nextValue = next.usage().get(entry.getKey());
            if (nextValue == null || nextValue < entry.getValue()) {
                throw new IllegalStateException(
                        "usage values cannot be removed or decreased"
                );
            }
        }
    }

    private static void requireEqual(Object previous, Object next, String field) {
        if (!Objects.equals(previous, next)) {
            throw new IllegalStateException(field + " cannot change across transitions");
        }
    }
}
