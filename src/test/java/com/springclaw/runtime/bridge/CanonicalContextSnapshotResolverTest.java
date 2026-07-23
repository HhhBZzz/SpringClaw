package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.RunStateRepository;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CanonicalContextSnapshotResolverTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-07-11T00:00:00Z");

    @Test
    void createsCandidateOnceAndReturnsSnapshotFromCommittedState() {
        CanonicalContextReadyProjector projector = mock(CanonicalContextReadyProjector.class);
        RunStateRepository repository = mock(RunStateRepository.class);
        ContextSnapshot candidate = snapshot("candidate-hash");
        ContextSnapshot committed = snapshot("committed-hash");
        when(projector.project(eq(RUN_ID), eq(candidate), eq(NOW)))
                .thenReturn(state(RunStatus.CONTEXT_READY, committed));
        AtomicInteger calls = new AtomicInteger();

        ContextSnapshot result = new CanonicalContextSnapshotResolver(projector, repository).resolve(
                new AcceptedRunContext(state(RunStatus.CREATED, null)),
                countingCandidate(candidate, calls)
        );

        assertThat(result).isSameAs(committed);
        assertThat(calls).hasValue(1);
        verify(projector).project(RUN_ID, candidate, NOW);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsCreatedRunThatAlreadyOwnsSnapshotWithoutInvokingCandidate() {
        CanonicalContextReadyProjector projector = mock(CanonicalContextReadyProjector.class);
        RunStateRepository repository = mock(RunStateRepository.class);
        AtomicInteger calls = new AtomicInteger();

        assertInvariant(() -> new CanonicalContextSnapshotResolver(projector, repository).resolve(
                new AcceptedRunContext(state(RunStatus.CREATED, snapshot("stored"))),
                countingCandidate(snapshot("candidate"), calls)
        ));

        assertThat(calls).hasValue(0);
        verifyNoInteractions(projector, repository);
    }

    @ParameterizedTest
    @EnumSource(
            value = RunStatus.class,
            names = {"CONTEXT_READY", "DECIDED", "RUNNING", "WAITING_CONFIRMATION", "VERIFYING"}
    )
    void reusesStoredSnapshotForEveryLaterNonTerminalState(RunStatus status) {
        CanonicalContextReadyProjector projector = mock(CanonicalContextReadyProjector.class);
        RunStateRepository repository = mock(RunStateRepository.class);
        ContextSnapshot stored = snapshot(status.name().toLowerCase());
        AtomicInteger calls = new AtomicInteger();

        ContextSnapshot result = new CanonicalContextSnapshotResolver(projector, repository).resolve(
                new AcceptedRunContext(state(status, stored)),
                countingCandidate(snapshot("candidate"), calls)
        );

        assertThat(result).isSameAs(stored);
        assertThat(calls).hasValue(0);
        verifyNoInteractions(projector, repository);
    }

    @Test
    void rejectsLaterNonTerminalStateWithoutSnapshotWithoutInvokingCandidate() {
        CanonicalContextReadyProjector projector = mock(CanonicalContextReadyProjector.class);
        RunStateRepository repository = mock(RunStateRepository.class);
        AtomicInteger calls = new AtomicInteger();

        assertInvariant(() -> new CanonicalContextSnapshotResolver(projector, repository).resolve(
                new AcceptedRunContext(state(RunStatus.DECIDED, null)),
                countingCandidate(snapshot("candidate"), calls)
        ));

        assertThat(calls).hasValue(0);
        verifyNoInteractions(projector, repository);
    }

    @Test
    void returnsReloadedWinnerSnapshotAfterProjectionRace() {
        CanonicalContextReadyProjector projector = mock(CanonicalContextReadyProjector.class);
        RunStateRepository repository = mock(RunStateRepository.class);
        ContextSnapshot candidate = snapshot("candidate-hash");
        ContextSnapshot winner = snapshot("winner-hash");
        IllegalStateException race = new IllegalStateException("revision conflict");
        when(projector.project(any(), any(), any())).thenThrow(race);
        when(repository.requireByRunId(RUN_ID))
                .thenReturn(state(RunStatus.CONTEXT_READY, winner));
        AtomicInteger calls = new AtomicInteger();

        ContextSnapshot result = new CanonicalContextSnapshotResolver(projector, repository).resolve(
                new AcceptedRunContext(state(RunStatus.CREATED, null)),
                countingCandidate(candidate, calls)
        );

        assertThat(result).isSameAs(winner);
        assertThat(result.snapshotHash()).isEqualTo("winner-hash");
        assertThat(calls).hasValue(1);
        verify(repository).requireByRunId(RUN_ID);
    }

    @Test
    void retainsProjectionFailureWhenReloadDoesNotContainCanonicalSnapshot() {
        CanonicalContextReadyProjector projector = mock(CanonicalContextReadyProjector.class);
        RunStateRepository repository = mock(RunStateRepository.class);
        IllegalStateException race = new IllegalStateException("revision conflict");
        when(projector.project(any(), any(), any())).thenThrow(race);
        when(repository.requireByRunId(RUN_ID)).thenReturn(state(RunStatus.CREATED, null));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> new CanonicalContextSnapshotResolver(projector, repository).resolve(
                new AcceptedRunContext(state(RunStatus.CREATED, null)),
                countingCandidate(snapshot("candidate"), calls)
        ))
                .isInstanceOf(CanonicalRunContextException.class)
                .satisfies(error -> {
                    CanonicalRunContextException failure =
                            (CanonicalRunContextException) error;
                    assertThat(failure.code()).isEqualTo(
                            CanonicalRunContextException.Code.CANONICAL_SNAPSHOT_INVARIANT
                    );
                    assertThat(failure.getCause()).isSameAs(race);
                });
        assertThat(calls).hasValue(1);
    }

    private static void assertInvariant(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(CanonicalRunContextException.class)
                .extracting(error -> ((CanonicalRunContextException) error).code())
                .isEqualTo(CanonicalRunContextException.Code.CANONICAL_SNAPSHOT_INVARIANT);
    }

    private static Supplier<ContextSnapshot> countingCandidate(
            ContextSnapshot snapshot,
            AtomicInteger calls
    ) {
        return () -> {
            calls.incrementAndGet();
            return snapshot;
        };
    }

    private static RunState state(RunStatus status, ContextSnapshot snapshot) {
        String pendingProposalId = status == RunStatus.WAITING_CONFIRMATION ? "proposal-1" : "";
        Instant startedAt = status == RunStatus.RUNNING ? NOW : null;
        return new RunState(
                RUN_ID,
                RUN_ID,
                status == RunStatus.CREATED ? 0 : 1,
                status,
                "session-1",
                "api",
                "user-1",
                claim(),
                "USER",
                "hello",
                "agent",
                NOW,
                startedAt,
                NOW,
                null,
                NOW.plusSeconds(300),
                snapshot,
                null,
                "",
                1,
                pendingProposalId,
                List.of(),
                null,
                null,
                Map.of(),
                null,
                null
        );
    }

    private static ContextSnapshot snapshot(String hash) {
        return new ContextSnapshot(
                RUN_ID,
                "session-1",
                "user-1",
                "api",
                "user-1",
                "USER",
                "hello",
                "hello",
                "system",
                "project",
                List.of(),
                List.of(),
                List.of(),
                List.of("web"),
                Map.of("providerId", "provider", "model", "model"),
                Map.of("schema", "test"),
                new MemoryFrame(
                        RUN_ID,
                        MemoryScope.from(claim()),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of("source", "test"),
                        List.of(),
                        NOW,
                        "frame-" + hash
                ),
                NOW,
                hash
        );
    }

    private static SessionAccessClaim claim() {
        return SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                "session-1",
                "user-1"
        );
    }
}
