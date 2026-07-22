package com.springclaw.runtime.lifecycle;

import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunEventType;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRunLifecycleStoreTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";
    private static final Instant T0 = Instant.parse("2026-06-21T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-06-21T00:00:01Z");

    private final InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();

    @Test
    void createsStateAndFirstEventAtomically() {
        assertThat(store.findByRunId(RUN_ID)).isEmpty();

        RunState created = createdState("hello");
        store.create(created, event(RunEventType.RUN_CREATED, RunStatus.CREATED, T0));

        assertThat(store.requireByRunId(RUN_ID)).isEqualTo(created);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::sequence)
                .containsExactly(1L);
    }

    @Test
    void listsRecentStatesByUpdatedAtDescending() {
        RunState first = createdState("run-recent-1", T0);
        RunState second = createdState("run-recent-2", T1);
        store.create(first, event("run-recent-1", RunEventType.RUN_CREATED, RunStatus.CREATED, T0));
        store.create(second, event("run-recent-2", RunEventType.RUN_CREATED, RunStatus.CREATED, T1));

        assertThat(store.findRecent(1))
                .extracting(RunState::runId)
                .containsExactly("run-recent-2");
        assertThat(store.findRecent(10))
                .extracting(RunState::runId)
                .containsExactly("run-recent-2", "run-recent-1");
    }

    @Test
    void identicalCreationIsIdempotentButConflictingCreationFails() {
        RunState created = createdState("hello");
        store.create(created, event(RunEventType.RUN_CREATED, RunStatus.CREATED, T0));

        assertThat(store.create(
                created,
                event(RunEventType.RUN_CREATED, RunStatus.CREATED, T0)
        )).isEqualTo(created);
        assertThat(store.findEventsByRunId(RUN_ID)).hasSize(1);

        assertThatThrownBy(() -> store.create(
                createdState("different"),
                event(RunEventType.RUN_CREATED, RunStatus.CREATED, T0)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting");
    }

    @Test
    void identicalAcceptanceRemainsIdempotentAfterRunAdvances() {
        RunState created = createdState("hello");
        store.create(created, event(RunEventType.RUN_CREATED, RunStatus.CREATED, T0));
        RunState failed = store.commit(
                0,
                failedState(),
                event(RunEventType.RUN_FAILED, RunStatus.FAILED, T1)
        );

        assertThat(store.create(
                created,
                event(RunEventType.RUN_CREATED, RunStatus.CREATED, T0)
        )).isEqualTo(failed);
        assertThat(store.findEventsByRunId(RUN_ID)).hasSize(2);
    }

    @Test
    void creationWithDifferentSessionAccessClaimConflicts() {
        store.create(
                createdState("hello"),
                event(RunEventType.RUN_CREATED, RunStatus.CREATED, T0)
        );

        assertThatThrownBy(() -> store.create(
                createdState(
                        "hello",
                        SessionAccessClaim.AcceptanceOrigin.VERIFIED_WEBHOOK
                ),
                event(RunEventType.RUN_CREATED, RunStatus.CREATED, T0)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting");
    }

    @Test
    void staleRevisionWritesNeitherStateNorEvent() {
        store.create(
                createdState("hello"),
                event(RunEventType.RUN_CREATED, RunStatus.CREATED, T0)
        );
        RunState failed = failedState();

        store.commit(
                0,
                failed,
                event(RunEventType.RUN_FAILED, RunStatus.FAILED, T1)
        );

        assertThatThrownBy(() -> store.commit(
                0,
                failed,
                event(RunEventType.RUN_FAILED, RunStatus.FAILED, T1)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
        assertThat(store.requireByRunId(RUN_ID).revision()).isEqualTo(1);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::sequence)
                .containsExactly(1L, 2L);
    }

    @Test
    void concurrentCommitsAcceptExactlyOneRevisionAndOneEvent() {
        store.create(
                createdState("hello"),
                event(RunEventType.RUN_CREATED, RunStatus.CREATED, T0)
        );
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger stale = new AtomicInteger();

        CompletableFuture<Void> first = concurrentCommit(start, accepted, stale);
        CompletableFuture<Void> second = concurrentCommit(start, accepted, stale);
        start.countDown();
        CompletableFuture.allOf(first, second).join();

        assertThat(accepted).hasValue(1);
        assertThat(stale).hasValue(1);
        assertThat(store.requireByRunId(RUN_ID).revision()).isEqualTo(1);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::sequence)
                .containsExactly(1L, 2L);
    }

    private CompletableFuture<Void> concurrentCommit(
            CountDownLatch start,
            AtomicInteger accepted,
            AtomicInteger stale
    ) {
        return CompletableFuture.runAsync(() -> {
            try {
                start.await();
                store.commit(
                        0,
                        failedState(),
                        event(RunEventType.RUN_FAILED, RunStatus.FAILED, T1)
                );
                accepted.incrementAndGet();
            } catch (IllegalStateException expected) {
                stale.incrementAndGet();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
    }

    private static RunState createdState(String message) {
        return createdState(
                message,
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API
        );
    }

    private static RunState createdState(
            String message,
            SessionAccessClaim.AcceptanceOrigin origin
    ) {
        return new RunState(
                RUN_ID, RUN_ID, 0, RunStatus.CREATED,
                "session-1", "api", "user-1", claim(origin),
                "USER", message, "agent",
                T0, null, T0, null, T0.plusSeconds(300),
                null, null, "", 1, "", List.of(), null, null, Map.of(), null,
                null
        );
    }

    private static RunState createdState(String runId, Instant at) {
        return new RunState(
                runId, runId, 0, RunStatus.CREATED,
                "session-1", "api", "user-1", claim(),
                "USER", "hello", "agent",
                at, null, at, null, at.plusSeconds(300),
                null, null, "", 1, "", List.of(), null, null, Map.of(), null,
                null
        );
    }

    private static RunState failedState() {
        return new RunState(
                RUN_ID, RUN_ID, 1, RunStatus.FAILED,
                "session-1", "api", "user-1", claim(), "USER", "hello", "agent",
                T0, null, T1, T1, T0.plusSeconds(300),
                null, null, "", 1, "", List.of(), null, null, Map.of(),
                new RunState.Failure("LEGACY_FAILED", "failed", false),
                null
        );
    }

    private static RunEvent.Draft event(
            RunEventType type,
            RunStatus status,
            Instant timestamp
    ) {
        return event(RUN_ID, type, status, timestamp);
    }

    private static RunEvent.Draft event(
            String runId,
            RunEventType type,
            RunStatus status,
            Instant timestamp
    ) {
        return new RunEvent.Draft(
                runId, type, "lifecycle", status, timestamp, 0,
                "springclaw.runtime.lifecycle.v1", "{}", null, runId, null
        );
    }

    private static SessionAccessClaim claim() {
        return claim(SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API);
    }

    private static SessionAccessClaim claim(
            SessionAccessClaim.AcceptanceOrigin origin
    ) {
        return SessionAccessClaim.personal(
                origin,
                "api",
                "session-1",
                "user-1"
        );
    }
}
