package com.springclaw.service.memory.index;

import com.springclaw.runtime.memory.contract.MemoryIndexOperation;
import com.springclaw.runtime.memory.contract.MemoryIndexOutboxEntry;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.port.MemoryIndexOutboxStore;
import com.springclaw.runtime.memory.port.MemoryRecordStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.springclaw.service.memory.index.MemoryIndexWorkerTest.activeVersion;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryIndexRebuildServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-23T00:00:00Z");
    private final MemoryRecordStore recordStore = mock(MemoryRecordStore.class);
    private final MemoryIndexOutboxStore outboxStore = mock(MemoryIndexOutboxStore.class);
    private final MemoryVectorIndex index = mock(MemoryVectorIndex.class);
    private final MemoryIndexGenerationStore generationStore = mock(MemoryIndexGenerationStore.class);

    @Test
    void rebuildCopiesOnlyCurrentActiveVersionsThenAppliesTail() {
        MemoryRecordVersion active1 = activeVersion("version-1", 1);
        MemoryRecordVersion active2 = activeVersion("version-2", 2);
        MemoryIndexRebuildService rebuild = rebuild(5_000, 1_000);
        when(generationStore.nextGeneration()).thenReturn("gen-2");
        when(recordStore.findActiveAfterRecordId(0, 5_000))
                .thenReturn(List.of(active1, active2));
        when(outboxStore.findPendingAfterRevision(2, null, 1_000))
                .thenReturn(List.of(claimedDelete("event-tail", "version-1", 3)));

        rebuild.rebuild();

        verify(generationStore).markDegraded(true);
        verify(index).createGeneration("gen-2");
        verify(index).upsert(active1, "gen-2");
        verify(index).upsert(active2, "gen-2");
        verify(index).delete("version-1", "gen-2");
        verify(generationStore).activate("gen-2");
        verify(generationStore).markDegraded(false);
    }

    @Test
    void rebuildScansAllActiveAndTailBatchesBeforeActivating() {
        MemoryRecordVersion active1 = activeVersionWithRecordId("version-1", 1L, 1);
        MemoryRecordVersion active2 = activeVersionWithRecordId("version-2", 2L, 2);
        MemoryRecordVersion active3 = activeVersionWithRecordId("version-3", 3L, 3);
        MemoryIndexRebuildService rebuild = rebuild(2, 2);
        when(generationStore.nextGeneration()).thenReturn("gen-2");
        when(recordStore.findActiveAfterRecordId(0, 2))
                .thenReturn(List.of(active1, active2));
        when(recordStore.findActiveAfterRecordId(2, 2))
                .thenReturn(List.of(active3));
        when(outboxStore.findPendingAfterRevision(3, null, 2))
                .thenReturn(List.of(
                        claimedDelete("event-tail-1", "version-1", 4),
                        claimedDelete("event-tail-2", "version-2", 4)));
        when(outboxStore.findPendingAfterRevision(4, "event-tail-2", 2))
                .thenReturn(List.of(claimedDelete("event-tail-3", "version-3", 5)));

        rebuild.rebuild();

        verify(index).upsert(active1, "gen-2");
        verify(index).upsert(active2, "gen-2");
        verify(index).upsert(active3, "gen-2");
        verify(index).delete("version-1", "gen-2");
        verify(index).delete("version-2", "gen-2");
        verify(index).delete("version-3", "gen-2");
        verify(generationStore).activate("gen-2");
    }

    private MemoryIndexRebuildService rebuild(int activeLimit, int tailLimit) {
        return new MemoryIndexRebuildService(
                recordStore,
                outboxStore,
                index,
                generationStore,
                activeLimit,
                tailLimit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MemoryIndexOutboxEntry claimedDelete(
            String eventId,
            String versionId,
            long revision
    ) {
        return new MemoryIndexOutboxEntry(
                eventId,
                "logical-1",
                versionId,
                1,
                revision,
                MemoryIndexOperation.DELETE,
                MemoryIndexOutboxEntry.Status.PENDING,
                0,
                NOW.minusSeconds(10),
                null,
                null,
                null,
                null,
                null,
                NOW.minusSeconds(20),
                NOW);
    }

    private static MemoryRecordVersion activeVersionWithRecordId(
            String versionId,
            Long recordId,
            long indexRevision
    ) {
        MemoryRecordVersion v = activeVersion(versionId, indexRevision);
        return new MemoryRecordVersion(
                recordId,
                v.logicalMemoryId(),
                v.memoryVersionId(),
                v.memoryType(),
                v.scopeType(),
                v.scopeId(),
                v.ownerUserId(),
                v.content(),
                v.contentHash(),
                v.summary(),
                v.sourceRunId(),
                v.sourceEventIds(),
                v.evidenceRefs(),
                v.tags(),
                v.importance(),
                v.confidence(),
                v.status(),
                v.validFrom(),
                v.validUntil(),
                v.supersedesRecordId(),
                v.version(),
                v.activeSlot(),
                v.sourceKind(),
                v.sourceIdentity(),
                v.extractionPolicyVersion(),
                v.indexRevision(),
                v.createdAt(),
                v.updatedAt(),
                v.deleted());
    }
}
