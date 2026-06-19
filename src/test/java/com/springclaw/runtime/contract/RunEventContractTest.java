package com.springclaw.runtime.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
                "cmd-1", "run-1"
        );

        assertThat(mapper.writeValueAsString(event))
                .contains("\"eventType\":\"context.ready\"");
    }

    @Test
    void rejectsNonPositiveSequence() {
        assertThatThrownBy(() -> new RunEvent(
                "evt-1", "run-1", 0, RunEventType.RUN_CREATED,
                "acceptance", RunStatus.CREATED, Instant.now(), 0,
                "v1", "{}", null, "run-1"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence");
    }

    @Test
    void exposesSequenceFreeDraftThatConvertsWithStoreOwnedIdentityAndSequence() {
        assertThat(Arrays.stream(RunEvent.class.getDeclaredClasses())
                .map(Class::getSimpleName))
                .contains("Draft");

        assertThatCode(() -> {
            Class<?> draftType = Class.forName("com.springclaw.runtime.contract.RunEvent$Draft");
            Object draft = draftType.getDeclaredConstructor(
                    String.class,
                    RunEventType.class,
                    String.class,
                    RunStatus.class,
                    Instant.class,
                    long.class,
                    String.class,
                    String.class,
                    String.class,
                    String.class
            ).newInstance(
                    "run-1",
                    RunEventType.CONTEXT_READY,
                    "context",
                    RunStatus.CONTEXT_READY,
                    Instant.parse("2026-06-19T00:00:00Z"),
                    12L,
                    "springclaw.run-event.context-ready.v1",
                    "{\"hash\":\"h1\"}",
                    "cmd-1",
                    "run-1"
            );

            RunEvent persisted = (RunEvent) draftType
                    .getMethod("persisted", String.class, long.class)
                    .invoke(draft, "evt-1", 7L);

            assertThat(persisted.eventId()).isEqualTo("evt-1");
            assertThat(persisted.sequence()).isEqualTo(7L);
            assertThat(persisted.runId()).isEqualTo("run-1");
            assertThat(persisted.eventType()).isEqualTo(RunEventType.CONTEXT_READY);
        }).doesNotThrowAnyException();
    }
}
