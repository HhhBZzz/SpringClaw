package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunEventType;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.InMemoryRunLifecycleStore;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.runtime.lifecycle.RunCoordinator;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalContextReadyProjectorTest {

    private static final String RUN_ID = "0123456789abcdef0123456789abcdef";
    private static final Instant T0 = Instant.parse("2026-06-25T00:00:00Z");

    @Test
    void projectsCreatedRunToContextReadyWithSnapshot() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        accept(coordinator);
        CanonicalContextReadyProjector projector =
                new CanonicalContextReadyProjector(coordinator, store);
        ContextSnapshot snapshot = snapshot();

        RunState state = projector.project(RUN_ID, snapshot, T0.plusSeconds(1));

        assertThat(state.status()).isEqualTo(RunStatus.CONTEXT_READY);
        assertThat(state.contextSnapshot()).isEqualTo(snapshot);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .containsExactly(
                        RunEventType.RUN_CREATED,
                        RunEventType.CONTEXT_READY
                );
    }

    @Test
    void returnsExistingContextReadyStateWithoutDuplicatingEvent() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        accept(coordinator);
        ContextSnapshot snapshot = snapshot();
        coordinator.contextReady(RUN_ID, snapshot, T0.plusSeconds(1));
        CanonicalContextReadyProjector projector =
                new CanonicalContextReadyProjector(coordinator, store);

        RunState state = projector.project(RUN_ID, snapshot, T0.plusSeconds(2));

        assertThat(state.status()).isEqualTo(RunStatus.CONTEXT_READY);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .containsExactly(
                        RunEventType.RUN_CREATED,
                        RunEventType.CONTEXT_READY
                );
    }

    @Test
    void returnsStoredSnapshotInsteadOfNewCandidateForExistingContextReadyRun() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        accept(coordinator);
        ContextSnapshot stored = snapshot();
        coordinator.contextReady(RUN_ID, stored, T0.plusSeconds(1));
        CanonicalContextReadyProjector projector =
                new CanonicalContextReadyProjector(coordinator, store);
        ContextSnapshot candidate = new ContextSnapshot(
                RUN_ID,
                "session-1",
                "user-1",
                "api",
                "user-1",
                "USER",
                "hello",
                "hello",
                "replacement system",
                "project",
                List.of(),
                List.of(),
                List.of(),
                List.of("web"),
                Map.of("providerId", "provider"),
                Map.of("schema", "test"),
                frame(),
                T0.plusSeconds(2),
                "candidate-hash"
        );

        RunState state = projector.project(RUN_ID, candidate, T0.plusSeconds(2));

        assertThat(state.contextSnapshot()).isSameAs(stored);
        assertThat(state.contextSnapshot().snapshotHash()).isEqualTo("snapshot-hash");
    }

    @Test
    void rejectsProjectionAfterDecisionBoundary() {
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        accept(coordinator);
        ContextSnapshot snapshot = snapshot();
        coordinator.contextReady(RUN_ID, snapshot, T0.plusSeconds(1));
        coordinator.decided(
                RUN_ID,
                new RunExecutionDecisionProjector().adapt(TestChatContexts.context(RUN_ID), T0.plusSeconds(2)),
                T0.plusSeconds(2)
        );
        CanonicalContextReadyProjector projector =
                new CanonicalContextReadyProjector(coordinator, store);

        assertThatThrownBy(() -> projector.project(RUN_ID, snapshot, T0.plusSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot project contextReady from status DECIDED");
    }

    private static void accept(RunCoordinator coordinator) {
        coordinator.accept(new RunAcceptance(
                RUN_ID,
                "session-1",
                "api",
                "user-1",
                claim(),
                "USER",
                "hello",
                "agent",
                T0,
                T0.plusSeconds(300)
        ));
    }

    private static SessionAccessClaim claim() {
        return SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                "session-1",
                "user-1"
        );
    }

    private static ContextSnapshot snapshot() {
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
                Map.of("providerId", "provider"),
                Map.of("schema", "test"),
                frame(),
                T0,
                "snapshot-hash"
        );
    }

    private static MemoryFrame frame() {
        return new MemoryFrame(
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
                T0,
                "frame-hash"
        );
    }

    static final class TestChatContexts {
        static com.springclaw.service.chat.impl.ChatContext context(String runId) {
            com.springclaw.domain.entity.AgentSession session =
                    new com.springclaw.domain.entity.AgentSession();
            session.setSessionKey("session-1");
            session.setUserId("user-1");
            session.setChannel("api");
            com.springclaw.service.context.AssembledContext assembled =
                    new com.springclaw.service.context.AssembledContext(
                            "session-1",
                            "api",
                            "user-1",
                            "hello",
                            "",
                            "",
                            "observe"
                    );
            return new com.springclaw.service.chat.impl.ChatContext(
                    session,
                    "api",
                    "user-1",
                    "USER",
                    "hello",
                    "hello",
                    runId,
                    "system",
                    assembled,
                    null,
                    "simplified",
                    "test",
                    "agent",
                    "general",
                    new com.springclaw.service.agent.AgentDecision(
                            "general",
                            "basic_model",
                            List.of("web"),
                            "read",
                            false,
                            "test"
                    ),
                    new com.springclaw.service.context.ContextInjection(
                            "observe",
                            "",
                            "",
                            Map.of()
                    )
            );
        }
    }
}
