package com.springclaw.service.memory.frame;

import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryFrameItem;
import com.springclaw.runtime.memory.contract.MemoryFrameLayer;
import com.springclaw.runtime.memory.contract.MemoryFrameOmission;
import com.springclaw.runtime.memory.contract.MemoryFrameSourceKind;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryScopeType;
import com.springclaw.runtime.memory.contract.MemoryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryFrameHasherTest {

    @Test
    void hashIsStableAndExcludesCapturedAt() {
        MemoryFrame first = frame("run-1", Instant.parse("2026-06-24T00:00:00Z"));
        MemoryFrame second = frame("run-1", Instant.parse("2026-06-24T00:10:00Z"));

        assertThat(MemoryFrameHasher.hash(first))
                .isEqualTo(MemoryFrameHasher.hash(second));
    }

    @Test
    void hashChangesWhenOrderedItemIdentityChanges() {
        MemoryFrame first = frameWithItems("run-1", List.of(item("a"), item("b")));
        MemoryFrame second = frameWithItems("run-1", List.of(item("b"), item("a")));

        assertThat(MemoryFrameHasher.hash(first))
                .isNotEqualTo(MemoryFrameHasher.hash(second));
    }

    @Test
    void hashIncludesOmissionsAndSortedSourceSummary() {
        MemoryFrame first = new MemoryFrame(
                "run-1",
                scope(),
                List.of(),
                List.of(item("a")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("b", "2", "a", "1"),
                List.of(new MemoryFrameOmission(
                        MemoryFrameOmission.Category.VECTOR_UNAVAILABLE,
                        MemoryFrameLayer.SEMANTIC_FACT,
                        "vector",
                        "disabled"
                )),
                Instant.parse("2026-06-24T00:00:00Z"),
                "placeholder"
        );
        MemoryFrame second = new MemoryFrame(
                "run-1",
                scope(),
                List.of(),
                List.of(item("a")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("a", "1", "b", "2"),
                List.of(new MemoryFrameOmission(
                        MemoryFrameOmission.Category.SOURCE_UNAVAILABLE,
                        MemoryFrameLayer.SEMANTIC_FACT,
                        "vector",
                        "disabled"
                )),
                Instant.parse("2026-06-24T00:00:00Z"),
                "placeholder"
        );

        assertThat(MemoryFrameHasher.hash(first))
                .isNotEqualTo(MemoryFrameHasher.hash(second));
    }

    private static MemoryFrame frame(String runId, Instant capturedAt) {
        return new MemoryFrame(
                runId,
                scope(),
                List.of(),
                List.of(item("a")),
                List.of(),
                List.of(item("semantic")),
                List.of(),
                List.of(),
                Map.of("source", "test"),
                List.of(),
                capturedAt,
                "placeholder"
        );
    }

    private static MemoryFrame frameWithItems(
            String runId,
            List<MemoryFrameItem> shortTermItems
    ) {
        return new MemoryFrame(
                runId,
                scope(),
                List.of(),
                shortTermItems,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z"),
                "placeholder"
        );
    }

    private static MemoryScope scope() {
        return MemoryScope.from(SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                "session-1",
                "alice"
        ));
    }

    private static MemoryFrameItem item(String sourceId) {
        return new MemoryFrameItem(
                sourceId,
                MemoryFrameSourceKind.MEMORY_RECORD,
                MemoryFrameLayer.SHORT_TERM,
                "logical-" + sourceId,
                "version-" + sourceId,
                MemoryType.SEMANTIC,
                MemoryScopeType.PERSONAL_SESSION,
                "api:session-1:alice",
                "content " + sourceId,
                "hash-" + sourceId,
                List.of("event-" + sourceId),
                0.7,
                0.8,
                0.9,
                1,
                Instant.parse("2026-06-24T00:00:00Z")
        );
    }
}
