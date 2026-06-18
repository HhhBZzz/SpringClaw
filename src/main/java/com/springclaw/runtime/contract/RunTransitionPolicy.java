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
    }
}
