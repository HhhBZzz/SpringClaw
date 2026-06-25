package com.springclaw.runtime.contract;

import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextSnapshotMemoryFrameContractTest {

    private static final Instant T0 = Instant.parse("2026-06-24T00:00:00Z");

    @Test
    void requiresMemoryFrameAndMatchingRunId() {
        MemoryFrame frame = frame("run-1");

        ContextSnapshot snapshot = snapshot("run-1", frame);

        assertThat(snapshot.memoryFrame()).isEqualTo(frame);
        assertThatThrownBy(() -> snapshot("run-2", frame))
                .hasMessageContaining("MemoryFrame");
        assertThatThrownBy(() -> snapshot("run-1", null))
                .hasMessageContaining("memoryFrame");
    }

    @Test
    void copiesCompatibilityCollections() {
        List<String> events = new ArrayList<>();
        events.add("event-1");

        ContextSnapshot snapshot = new ContextSnapshot(
                "run-1",
                "session-1",
                "alice",
                "api",
                "alice",
                "USER",
                "original",
                "effective",
                "system",
                "project",
                events,
                List.of("semantic"),
                List.of("rule"),
                List.of("web"),
                Map.of("providerId", "test"),
                Map.of("schema", "test"),
                frame("run-1"),
                T0,
                "hash-1"
        );

        events.add("event-2");

        assertThat(snapshot.shortTermEvents()).containsExactly("event-1");
        assertThatThrownBy(() -> snapshot.shortTermEvents().add("event-3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ContextSnapshot snapshot(String runId, MemoryFrame frame) {
        return new ContextSnapshot(
                runId,
                "session-1",
                "alice",
                "api",
                "alice",
                "USER",
                "original",
                "effective",
                "system",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                frame,
                T0,
                "hash-1"
        );
    }

    static MemoryFrame frame(String runId) {
        return new MemoryFrame(
                runId,
                MemoryScope.from(SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api",
                        "session-1",
                        "alice"
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("source", "test"),
                List.of(),
                T0,
                "frame-hash-1"
        );
    }
}
