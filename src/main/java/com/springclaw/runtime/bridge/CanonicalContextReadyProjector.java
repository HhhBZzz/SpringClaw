package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.lifecycle.RunCoordinator;
import com.springclaw.runtime.lifecycle.RunStateRepository;

import java.time.Instant;
import java.util.Objects;

public class CanonicalContextReadyProjector {

    private final RunCoordinator runCoordinator;
    private final RunStateRepository runStateRepository;

    public CanonicalContextReadyProjector(
            RunCoordinator runCoordinator,
            RunStateRepository runStateRepository
    ) {
        this.runCoordinator = Objects.requireNonNull(runCoordinator, "runCoordinator");
        this.runStateRepository = Objects.requireNonNull(
                runStateRepository,
                "runStateRepository"
        );
    }

    public RunState project(String runId, ContextSnapshot snapshot, Instant at) {
        Objects.requireNonNull(snapshot, "snapshot");
        Instant observedAt = Objects.requireNonNull(at, "at");
        RunState current = runStateRepository.requireByRunId(runId);
        if (current.status() == RunStatus.CREATED) {
            return runCoordinator.contextReady(runId, snapshot, observedAt);
        }
        if (current.status() == RunStatus.CONTEXT_READY
                && current.contextSnapshot() != null) {
            return current;
        }
        throw new IllegalStateException(
                "cannot project contextReady from status " + current.status()
                        + " for run " + runId
        );
    }
}
