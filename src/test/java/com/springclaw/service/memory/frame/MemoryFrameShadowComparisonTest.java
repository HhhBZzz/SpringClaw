package com.springclaw.service.memory.frame;

import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryFrameItem;
import com.springclaw.runtime.memory.contract.MemoryFrameLayer;
import com.springclaw.runtime.memory.contract.MemoryFrameSourceKind;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryScopeType;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.service.context.AssembledContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryFrameShadowComparisonTest {

    @Test
    void comparisonIsReadOnlyAndDoesNotChangeLegacyContextText() {
        AssembledContext legacy = new AssembledContext(
                "s1",
                "api",
                "alice",
                "question",
                "event text",
                "semantic text",
                "observe prompt",
                1,
                0
        );
        MemoryFrame frame = frameWithItems("run-1", List.of(item("semantic-1")));

        MemoryFrameShadowComparison comparison =
                new MemoryFrameShadowComparator().compare("run-1", legacy, frame);

        assertThat(comparison.runId()).isEqualTo("run-1");
        assertThat(comparison.legacyObservePromptHash()).isNotBlank();
        assertThat(comparison.frameHash()).isEqualTo(frame.frameHash());
        assertThat(comparison.legacyMemoryLearningActiveCount()).isEqualTo(1);
        assertThat(comparison.frameLayerCounts()).containsEntry("semantic", 1);
        assertThat(legacy.observePrompt()).isEqualTo("observe prompt");
    }

    private static MemoryFrame frameWithItems(
            String runId,
            List<MemoryFrameItem> semanticItems
    ) {
        return new MemoryFrame(
                runId,
                MemoryScope.from(personalClaim()),
                List.of(),
                List.of(),
                List.of(),
                semanticItems,
                List.of(),
                List.of(),
                Map.of("source", "test"),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z"),
                "frame-hash-1"
        );
    }

    private static MemoryFrameItem item(String sourceId) {
        return new MemoryFrameItem(
                sourceId,
                MemoryFrameSourceKind.MEMORY_RECORD,
                MemoryFrameLayer.SEMANTIC_FACT,
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

    private static SessionAccessClaim personalClaim() {
        return SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                "session-1",
                "alice"
        );
    }
}
