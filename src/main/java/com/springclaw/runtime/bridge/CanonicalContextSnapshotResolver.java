package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.lifecycle.RunStateRepository;

import java.util.Objects;
import java.util.function.Supplier;

public final class CanonicalContextSnapshotResolver {

    private final CanonicalContextReadyProjector projector;
    private final RunStateRepository runStateRepository;

    public CanonicalContextSnapshotResolver(
            CanonicalContextReadyProjector projector,
            RunStateRepository runStateRepository
    ) {
        this.projector = Objects.requireNonNull(projector, "projector");
        this.runStateRepository = Objects.requireNonNull(
                runStateRepository,
                "runStateRepository"
        );
    }

    public ContextSnapshot resolve(
            AcceptedRunContext accepted,
            Supplier<ContextSnapshot> snapshotCandidate
    ) {
        Objects.requireNonNull(accepted, "accepted");
        Objects.requireNonNull(snapshotCandidate, "snapshotCandidate");
        RunState initial = accepted.runState();

        if (initial.status() == RunStatus.CREATED) {
            if (initial.contextSnapshot() != null) {
                throw invariant(initial.runId(), "CREATED run already owns a snapshot");
            }
            ContextSnapshot candidate = Objects.requireNonNull(
                    snapshotCandidate.get(),
                    "snapshotCandidate returned null"
            );
            requireMatchingRunId(initial.runId(), candidate);
            try {
                RunState committed = projector.project(
                        initial.runId(),
                        candidate,
                        candidate.capturedAt()
                );
                return requireCanonicalSnapshot(initial.runId(), committed);
            } catch (RuntimeException projectionFailure) {
                return resolveAfterProjectionFailure(initial.runId(), projectionFailure);
            }
        }

        if (initial.status().isTerminal() || initial.contextSnapshot() == null) {
            throw invariant(initial.runId(), "non-created run has no reusable snapshot");
        }
        return initial.contextSnapshot();
    }

    private ContextSnapshot resolveAfterProjectionFailure(
            String runId,
            RuntimeException projectionFailure
    ) {
        RunState reloaded;
        try {
            reloaded = runStateRepository.requireByRunId(runId);
        } catch (RuntimeException reloadFailure) {
            projectionFailure.addSuppressed(reloadFailure);
            throw invariant(
                    runId,
                    "snapshot projection did not produce canonical state",
                    projectionFailure
            );
        }
        if (!reloaded.status().isTerminal() && reloaded.contextSnapshot() != null) {
            return reloaded.contextSnapshot();
        }
        throw invariant(
                runId,
                "snapshot projection did not produce canonical state",
                projectionFailure
        );
    }

    private static ContextSnapshot requireCanonicalSnapshot(
            String runId,
            RunState committed
    ) {
        if (committed == null
                || !runId.equals(committed.runId())
                || committed.status() == RunStatus.CREATED
                || committed.status().isTerminal()
                || committed.contextSnapshot() == null) {
            throw invariant(runId, "projection returned an invalid canonical state");
        }
        requireMatchingRunId(runId, committed.contextSnapshot());
        return committed.contextSnapshot();
    }

    private static void requireMatchingRunId(String runId, ContextSnapshot snapshot) {
        if (!runId.equals(snapshot.runId())) {
            throw invariant(runId, "snapshot runId does not match accepted run");
        }
    }

    private static CanonicalRunContextException invariant(String runId, String reason) {
        return invariant(runId, reason, null);
    }

    private static CanonicalRunContextException invariant(
            String runId,
            String reason,
            Throwable cause
    ) {
        return cause == null
                ? new CanonicalRunContextException(
                        CanonicalRunContextException.Code.CANONICAL_SNAPSHOT_INVARIANT,
                        "canonical snapshot invariant: " + reason + " for run " + runId
                )
                : new CanonicalRunContextException(
                        CanonicalRunContextException.Code.CANONICAL_SNAPSHOT_INVARIANT,
                        "canonical snapshot invariant: " + reason + " for run " + runId,
                        cause
                );
    }
}
