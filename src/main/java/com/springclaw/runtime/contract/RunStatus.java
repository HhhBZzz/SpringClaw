package com.springclaw.runtime.contract;

import java.util.Set;

/**
 * Canonical run lifecycle states for the unified agent runtime.
 *
 * <p>Only {@link RunCoordinator} (introduced in a later migration phase) is allowed
 * to apply transitions; this enum only declares which transitions are architecturally
 * valid. Transport status and proposal status are NOT encoded here.
 *
 * @see RunTransitionPolicy
 */
public enum RunStatus {
    CREATED,
    CONTEXT_READY,
    DECIDED,
    WAITING_CONFIRMATION,
    RUNNING,
    VERIFYING,
    COMPLETED,
    DEGRADED,
    FAILED;

    /**
     * Business-terminal states are immutable: a run reaching one of them must not
     * transition further. {@code WAITING_CONFIRMATION} is intentionally NOT terminal —
     * it is a suspension boundary that resumes into {@code RUNNING} or fails.
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == DEGRADED || this == FAILED;
    }

    /**
     * The set of states this status may legally transition into. Matches the
     * transition table in the unified-runtime architecture spec § 8.1.
     */
    public Set<RunStatus> allowedTargets() {
        return switch (this) {
            case CREATED -> Set.of(CONTEXT_READY, FAILED);
            case CONTEXT_READY -> Set.of(DECIDED, FAILED);
            case DECIDED -> Set.of(RUNNING, FAILED);
            case RUNNING -> Set.of(WAITING_CONFIRMATION, VERIFYING, FAILED);
            case WAITING_CONFIRMATION -> Set.of(RUNNING, FAILED);
            case VERIFYING -> Set.of(DECIDED, COMPLETED, DEGRADED, FAILED);
            case COMPLETED, DEGRADED, FAILED -> Set.of();
        };
    }

    public boolean canTransitionTo(RunStatus target) {
        return target != null && allowedTargets().contains(target);
    }
}
