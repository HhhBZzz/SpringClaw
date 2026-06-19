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
                previous.roleCodeAtAcceptance(),
                next.roleCodeAtAcceptance(),
                "roleCodeAtAcceptance"
        );
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
        boolean verificationRetry = previous.status() == RunStatus.VERIFYING
                && next.status() == RunStatus.DECIDED;
        if (!verificationRetry && next.attempt() != previous.attempt()) {
            throw new IllegalStateException("attempt cannot change outside verification retry");
        }
        validateContextSnapshot(previous, next);
        validateExecutionDecision(previous, next, verificationRetry);
        validateStrategy(previous, next, verificationRetry);
        validateToolInvocations(previous, next);
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

    private static void validateToolInvocations(RunState previous, RunState next) {
        if (next.toolInvocations().size() < previous.toolInvocations().size()) {
            throw new IllegalStateException(
                    "toolInvocations must preserve the previous list as an exact prefix"
            );
        }
        for (int index = 0; index < previous.toolInvocations().size(); index++) {
            if (!previous.toolInvocations().get(index).equals(next.toolInvocations().get(index))) {
                throw new IllegalStateException(
                        "toolInvocations must preserve the previous list as an exact prefix"
                );
            }
        }
        for (int index = previous.toolInvocations().size();
             index < next.toolInvocations().size();
             index++) {
            if (next.toolInvocations().get(index).attempt() > next.attempt()) {
                throw new IllegalStateException(
                        "new ToolInvocation attempt must not exceed next RunState attempt"
                );
            }
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
