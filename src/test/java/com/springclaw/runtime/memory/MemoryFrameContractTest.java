package com.springclaw.runtime.memory;

import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryFrameItem;
import com.springclaw.runtime.memory.contract.MemoryFrameLayer;
import com.springclaw.runtime.memory.contract.MemoryFrameOmission;
import com.springclaw.runtime.memory.contract.MemoryFrameSourceKind;
import com.springclaw.runtime.memory.contract.MemoryRetrievalTrace;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryScopeType;
import com.springclaw.runtime.memory.contract.MemoryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryFrameContractTest {

    @Test
    void frameCopiesCollectionsAndRequiresStableIdentity() {
        MemoryScope scope = MemoryScope.from(personalClaim());
        List<MemoryFrameItem> shortTerm = new ArrayList<>();
        shortTerm.add(item("event-1", MemoryFrameLayer.SHORT_TERM));

        MemoryFrame frame = new MemoryFrame(
                "run-1",
                scope,
                List.of(),
                shortTerm,
                List.of(),
                List.of(item("semantic-1", MemoryFrameLayer.SEMANTIC_FACT)),
                List.of(),
                List.of(),
                Map.of("source", "test"),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z"),
                "hash-1"
        );

        shortTerm.add(item("event-2", MemoryFrameLayer.SHORT_TERM));

        assertThat(frame.shortTermTurns())
                .extracting(MemoryFrameItem::sourceId)
                .containsExactly("event-1");
        assertThatThrownBy(() -> frame.shortTermTurns().add(
                item("event-3", MemoryFrameLayer.SHORT_TERM)
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void frameRejectsBlankRunScopeAndHash() {
        MemoryScope scope = MemoryScope.from(personalClaim());

        assertThatThrownBy(() -> new MemoryFrame(
                "",
                scope,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z"),
                "hash"
        )).hasMessageContaining("runId");
        assertThatThrownBy(() -> new MemoryFrame(
                "run-1",
                scope,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                Instant.parse("2026-06-24T00:00:00Z"),
                ""
        )).hasMessageContaining("frameHash");
    }

    @Test
    void itemCopiesEvidenceAndRejectsInvalidScore() {
        List<String> evidence = new ArrayList<>(List.of("event-1"));

        MemoryFrameItem item = new MemoryFrameItem(
                "source-1",
                MemoryFrameSourceKind.MEMORY_RECORD,
                MemoryFrameLayer.SEMANTIC_FACT,
                "logical-1",
                "version-1",
                MemoryType.SEMANTIC,
                MemoryScopeType.PERSONAL_SESSION,
                "api:session-1:alice",
                "content",
                "hash-content",
                evidence,
                0.7,
                0.8,
                0.9,
                1,
                Instant.parse("2026-06-24T00:00:00Z")
        );

        evidence.add("event-2");

        assertThat(item.evidenceRefs()).containsExactly("event-1");
        assertThatThrownBy(() -> new MemoryFrameItem(
                "source-1",
                MemoryFrameSourceKind.MEMORY_RECORD,
                MemoryFrameLayer.SEMANTIC_FACT,
                "logical-1",
                "version-1",
                MemoryType.SEMANTIC,
                MemoryScopeType.PERSONAL_SESSION,
                "api:session-1:alice",
                "content",
                "hash-content",
                List.of(),
                0.7,
                0.8,
                1.1,
                1,
                Instant.parse("2026-06-24T00:00:00Z")
        )).hasMessageContaining("score");
    }

    @Test
    void traceDoesNotExposeOmittedPrivateContent() {
        MemoryRetrievalTrace trace = new MemoryRetrievalTrace(
                "run-1",
                MemoryScope.from(personalClaim()),
                "hash-1",
                Map.of("shortTerm", 3),
                Map.of("shortTerm", 2),
                Map.of(MemoryFrameOmission.Category.BUDGET_TRUNCATED, 1),
                List.of("vector unavailable"),
                Instant.parse("2026-06-24T00:00:00Z")
        );

        assertThat(trace.omissionCounts())
                .containsEntry(MemoryFrameOmission.Category.BUDGET_TRUNCATED, 1);
        assertThat(trace.sourceWarnings()).containsExactly("vector unavailable");
    }

    private static SessionAccessClaim personalClaim() {
        return SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                "session-1",
                "alice"
        );
    }

    private static MemoryFrameItem item(String sourceId, MemoryFrameLayer layer) {
        return new MemoryFrameItem(
                sourceId,
                MemoryFrameSourceKind.MEMORY_RECORD,
                layer,
                "logical-" + sourceId,
                "version-" + sourceId,
                layer == MemoryFrameLayer.EPISODIC
                        ? MemoryType.EPISODIC
                        : MemoryType.SEMANTIC,
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
