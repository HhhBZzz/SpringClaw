package com.springclaw.runtime.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunEventContractTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void serializesStableEventFamilyName() throws Exception {
        RunEvent event = new RunEvent(
                "evt-1", "run-1", 1, RunEventType.CONTEXT_READY,
                "context", RunStatus.CONTEXT_READY,
                Instant.parse("2026-06-19T00:00:00Z"), 12,
                "springclaw.run-event.context-ready.v1", "{\"hash\":\"h1\"}",
                "cmd-1", "run-1", AgentParadigm.OPAR
        );

        assertThat(mapper.writeValueAsString(event))
                .contains("\"eventType\":\"context.ready\"");
    }

    @Test
    void rejectsNonPositiveSequence() {
        assertThatThrownBy(() -> new RunEvent(
                "evt-1", "run-1", 0, RunEventType.RUN_CREATED,
                "acceptance", RunStatus.CREATED, Instant.now(), 0,
                "v1", "{}", null, "run-1", null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence");
    }

    @Test
    void exposesSequenceFreeDraftThatConvertsWithStoreOwnedIdentityAndSequence() {
        Instant timestamp = Instant.parse("2026-06-19T00:00:00Z");
        RunEvent.Draft draft = new RunEvent.Draft(
                "run-1",
                RunEventType.CONTEXT_READY,
                "context",
                RunStatus.CONTEXT_READY,
                timestamp,
                12L,
                "springclaw.run-event.context-ready.v1",
                "{\"hash\":\"h1\"}",
                "cmd-1",
                "run-1",
                AgentParadigm.OPAR
        );

        assertThat(draft.runId()).isEqualTo("run-1");
        assertThat(draft.eventType()).isEqualTo(RunEventType.CONTEXT_READY);
        assertThat(draft.stage()).isEqualTo("context");
        assertThat(draft.status()).isEqualTo(RunStatus.CONTEXT_READY);
        assertThat(draft.timestamp()).isEqualTo(timestamp);
        assertThat(draft.durationMs()).isEqualTo(12L);
        assertThat(draft.payloadSchema()).isEqualTo("springclaw.run-event.context-ready.v1");
        assertThat(draft.payload()).isEqualTo("{\"hash\":\"h1\"}");
        assertThat(draft.causationId()).isEqualTo("cmd-1");
        assertThat(draft.correlationId()).isEqualTo("run-1");
        assertThat(draft.paradigm()).isEqualTo(AgentParadigm.OPAR);

        RunEvent persisted = draft.persisted("evt-1", 7L);

        assertThat(persisted.eventId()).isEqualTo("evt-1");
        assertThat(persisted.sequence()).isEqualTo(7L);
        assertThat(persisted.runId()).isEqualTo(draft.runId());
        assertThat(persisted.eventType()).isEqualTo(draft.eventType());
        assertThat(persisted.stage()).isEqualTo(draft.stage());
        assertThat(persisted.status()).isEqualTo(draft.status());
        assertThat(persisted.timestamp()).isEqualTo(draft.timestamp());
        assertThat(persisted.durationMs()).isEqualTo(draft.durationMs());
        assertThat(persisted.payloadSchema()).isEqualTo(draft.payloadSchema());
        assertThat(persisted.payload()).isEqualTo(draft.payload());
        assertThat(persisted.causationId()).isEqualTo(draft.causationId());
        assertThat(persisted.correlationId()).isEqualTo(draft.correlationId());
        assertThat(persisted.paradigm()).isEqualTo(draft.paradigm());

        assertThatThrownBy(() -> draft.persisted("evt-2", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence");
    }
}
