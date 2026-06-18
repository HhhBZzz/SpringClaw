package com.springclaw.runtime.contract;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RunStatusTest {

    @Test
    void identifiesOnlyBusinessTerminalStates() {
        Set<RunStatus> terminal = Set.of(
                RunStatus.COMPLETED,
                RunStatus.DEGRADED,
                RunStatus.FAILED
        );

        for (RunStatus status : RunStatus.values()) {
            assertThat(status.isTerminal()).isEqualTo(terminal.contains(status));
        }
    }

    @Test
    void exposesOnlyApprovedStateTransitions() {
        assertThat(RunStatus.CREATED.allowedTargets())
                .containsExactlyInAnyOrder(RunStatus.CONTEXT_READY, RunStatus.FAILED);
        assertThat(RunStatus.CONTEXT_READY.allowedTargets())
                .containsExactlyInAnyOrder(RunStatus.DECIDED, RunStatus.FAILED);
        assertThat(RunStatus.DECIDED.allowedTargets())
                .containsExactlyInAnyOrder(RunStatus.RUNNING, RunStatus.FAILED);
        assertThat(RunStatus.RUNNING.allowedTargets())
                .containsExactlyInAnyOrder(
                        RunStatus.WAITING_CONFIRMATION,
                        RunStatus.VERIFYING,
                        RunStatus.FAILED
                );
        assertThat(RunStatus.WAITING_CONFIRMATION.allowedTargets())
                .containsExactlyInAnyOrder(RunStatus.RUNNING, RunStatus.FAILED);
        assertThat(RunStatus.VERIFYING.allowedTargets())
                .containsExactlyInAnyOrder(
                        RunStatus.DECIDED,
                        RunStatus.COMPLETED,
                        RunStatus.DEGRADED,
                        RunStatus.FAILED
                );
        assertThat(RunStatus.COMPLETED.allowedTargets()).isEmpty();
        assertThat(RunStatus.DEGRADED.allowedTargets()).isEmpty();
        assertThat(RunStatus.FAILED.allowedTargets()).isEmpty();
    }
}
