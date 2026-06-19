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
            CompletionDecision completionDecision = previous.completionDecision();
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
}
