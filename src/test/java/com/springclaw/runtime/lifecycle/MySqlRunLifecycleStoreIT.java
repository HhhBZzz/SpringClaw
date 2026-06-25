package com.springclaw.runtime.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.config.RuntimeLifecycleSchemaInitializer;
import com.springclaw.config.RuntimeLifecycleStoreConfig;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.ExecutionDecision;
import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunEventType;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = MySqlRunLifecycleStoreIT.TestApp.class,
        properties = {
        "OPENCLAW_PRIMARY_API_KEY=test-key",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "springclaw.runtime.lifecycle.store=mysql",
        "spring.datasource.url=jdbc:mysql://${MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:3306}/${MYSQL_DB:openclaw}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
})
class MySqlRunLifecycleStoreIT {

    private static final Instant T0 = Instant.parse("2026-06-25T08:00:00Z");
    private static final String RUN_ID = "phase3e00000000000000000000000001";

    @Autowired
    private MySqlRunLifecycleStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
            RuntimeLifecycleStoreConfig.class,
            RuntimeLifecycleSchemaInitializer.class
    })
    static class TestApp {
    }

    @BeforeEach
    void cleanBefore() {
        cleanLifecycleRows();
    }

    @AfterEach
    void cleanAfter() {
        cleanLifecycleRows();
    }

    @Test
    void persistsLifecycleAndCanBeReloadedByANewStoreInstance() {
        RunCoordinator coordinator = new RunCoordinator(store);

        coordinator.accept(acceptance(RUN_ID));
        coordinator.contextReady(RUN_ID, snapshot(RUN_ID), T0.plusSeconds(1));
        coordinator.decided(RUN_ID, decision(RUN_ID), T0.plusSeconds(2));
        coordinator.running(RUN_ID, "agent-runtime", T0.plusSeconds(3));
        coordinator.toolStarted(RUN_ID, T0.plusSeconds(4));

        MySqlRunLifecycleStore reloaded =
                new MySqlRunLifecycleStore(jdbcTemplate, objectMapper);
        RunState state = reloaded.requireByRunId(RUN_ID);
        List<RunEvent> events = reloaded.findEventsByRunId(RUN_ID);

        assertThat(state.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(state.revision()).isEqualTo(3);
        assertThat(state.contextSnapshot().snapshotHash()).isEqualTo("snapshot-hash-" + RUN_ID);
        assertThat(events).extracting(RunEvent::sequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(events).extracting(RunEvent::eventType)
                .containsExactly(
                        RunEventType.RUN_CREATED,
                        RunEventType.CONTEXT_READY,
                        RunEventType.DECISION_MADE,
                        RunEventType.STRATEGY_STARTED,
                        RunEventType.TOOL_STARTED
                );
    }

    @Test
    void staleCommitDoesNotMutateStateOrAppendEvent() {
        RunState created = createdState(RUN_ID, "hello");
        store.create(created, event(RUN_ID, RunEventType.RUN_CREATED, RunStatus.CREATED, T0));
        RunState failed = failedState(RUN_ID, "hello");

        store.commit(
                0,
                failed,
                event(RUN_ID, RunEventType.RUN_FAILED, RunStatus.FAILED, T0.plusSeconds(1))
        );

        assertThatThrownBy(() -> store.commit(
                0,
                failed,
                event(RUN_ID, RunEventType.RUN_FAILED, RunStatus.FAILED, T0.plusSeconds(1))
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale");
        assertThat(store.requireByRunId(RUN_ID).revision()).isEqualTo(1);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::sequence)
                .containsExactly(1L, 2L);
    }

    @Test
    void identicalCreateIsIdempotentButConflictingCreateFails() {
        RunState created = createdState(RUN_ID, "hello");
        store.create(created, event(RUN_ID, RunEventType.RUN_CREATED, RunStatus.CREATED, T0));

        assertThat(store.create(
                created,
                event(RUN_ID, RunEventType.RUN_CREATED, RunStatus.CREATED, T0)
        )).isEqualTo(created);
        assertThat(store.findEventsByRunId(RUN_ID)).hasSize(1);

        assertThatThrownBy(() -> store.create(
                createdState(RUN_ID, "different"),
                event(RUN_ID, RunEventType.RUN_CREATED, RunStatus.CREATED, T0)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting");
    }

    private void cleanLifecycleRows() {
        jdbcTemplate.update("DELETE FROM runtime_run_event WHERE run_id LIKE 'phase3e%'");
        jdbcTemplate.update("DELETE FROM runtime_run_state WHERE run_id LIKE 'phase3e%'");
    }

    private static RunAcceptance acceptance(String runId) {
        return new RunAcceptance(
                runId, "session-1", "api", "user-1", claim(),
                "USER", "hello", "agent", T0, T0.plusSeconds(300)
        );
    }

    private static RunState createdState(String runId, String message) {
        return new RunState(
                runId, runId, 0, RunStatus.CREATED,
                "session-1", "api", "user-1", claim(),
                "USER", message, "agent",
                T0, null, T0, null, T0.plusSeconds(300),
                null, null, "", 1, "", List.of(), null, null, Map.of(), null
        );
    }

    private static RunState failedState(String runId, String message) {
        return new RunState(
                runId, runId, 1, RunStatus.FAILED,
                "session-1", "api", "user-1", claim(),
                "USER", message, "agent",
                T0, null, T0.plusSeconds(1), T0.plusSeconds(1), T0.plusSeconds(300),
                null, null, "", 1, "", List.of(), null, null, Map.of(),
                new RunState.Failure("LEGACY_FAILED", "failed", false)
        );
    }

    private static ContextSnapshot snapshot(String runId) {
        return new ContextSnapshot(
                runId, "session-1", "user-1", "api", "user-1", "USER",
                "hello", "hello", "system", "", List.of(), List.of(), List.of(),
                List.of(), Map.of(), Map.of(), memoryFrame(runId),
                T0.plusSeconds(1), "snapshot-hash-" + runId
        );
    }

    private static MemoryFrame memoryFrame(String runId) {
        return new MemoryFrame(
                runId,
                MemoryScope.from(claim()),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of("source", "phase-3e-test"), List.of(),
                T0, "frame-hash-" + runId
        );
    }

    private static ExecutionDecision decision(String runId) {
        return new ExecutionDecision(
                runId, "general", "answer", "agent", "read", List.of(),
                List.of(), Map.of(), List.of(), 1.0, "legacy", "legacy",
                T0.plusSeconds(2)
        );
    }

    private static RunEvent.Draft event(
            String runId,
            RunEventType type,
            RunStatus status,
            Instant timestamp
    ) {
        return new RunEvent.Draft(
                runId, type, "lifecycle", status, timestamp, 0,
                "springclaw.runtime.lifecycle.v1", "{}", null, runId
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
