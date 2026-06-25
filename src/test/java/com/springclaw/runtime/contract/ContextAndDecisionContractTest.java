package com.springclaw.runtime.contract;

import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextAndDecisionContractTest {

    @Test
    void contextSnapshotCopiesEveryCollection() {
        List<String> events = new ArrayList<>(List.of("event-1"));
        Map<String, String> provider = new HashMap<>(Map.of("providerId", "p1"));

        ContextSnapshot snapshot = new ContextSnapshot(
                "run-1", "session-1", "user-1", "web", "user-1", "USER",
                "original", "effective", "system", "memory",
                events, List.of("semantic-1"), List.of("rule-1"),
                List.of("web.search"), provider, Map.of("schema", "v1"),
                memoryFrame("run-1"),
                Instant.parse("2026-06-19T00:00:00Z"), "hash-1"
        );

        events.add("event-2");
        provider.put("modelId", "m1");

        assertThat(snapshot.shortTermEvents()).containsExactly("event-1");
        assertThat(snapshot.providerSnapshot()).containsOnly(Map.entry("providerId", "p1"));
        assertThatThrownBy(() -> snapshot.allowedCapabilities().add("workspace.edit"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void executionDecisionRequiresTheSameRunIdentifier() {
        assertThatThrownBy(() -> new ExecutionDecision(
                " ", "research", "answer", "agent", "read",
                List.of(), List.of(), Map.of(), List.of(), 0.8,
                "matched capability", "policy", Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
    }

    @Test
    void executionDecisionRejectsNonFiniteOrOutOfRangeConfidence() {
        for (double invalid : List.of(
                Double.NaN,
                Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY,
                -0.01,
                1.01
        )) {
            assertThatThrownBy(() -> new ExecutionDecision(
                    "run-1", "research", "answer", "agent", "read",
                    List.of(), List.of(), Map.of(), List.of(), invalid,
                    "matched capability", "policy", Instant.now()
            )).as("confidence %s", invalid)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("confidence");
        }
    }

    private static MemoryFrame memoryFrame(String runId) {
        return new MemoryFrame(
                runId,
                MemoryScope.from(SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api", "session-1", "user-1"
                )),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of("source", "legacy-test"), List.of(),
                Instant.parse("2026-06-24T00:00:00Z"), "frame-hash-" + runId
        );
    }
}
