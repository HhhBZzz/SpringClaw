package com.springclaw.service.memory.index;

import com.springclaw.runtime.memory.contract.MemoryIndexOperation;
import com.springclaw.runtime.memory.contract.MemoryIndexOutboxEntry;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScopeType;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.port.MemoryIndexOutboxStore;
import com.springclaw.runtime.memory.port.MemoryRecordStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryIndexWorkerTest {

    private static final Instant NOW = Instant.parse("2026-06-23T00:00:00Z");
    private final MemoryRecordStore recordStore = mock(MemoryRecordStore.class);
    private final MemoryIndexOutboxStore outboxStore = mock(MemoryIndexOutboxStore.class);
    private final MemoryVectorIndex index = mock(MemoryVectorIndex.class);
    private final MemoryIndexGenerationStore generationStore = mock(MemoryIndexGenerationStore.class);
    private final MemoryIndexWorker worker = new MemoryIndexWorker(
            recordStore,
            outboxStore,
            index,
            generationStore,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void staleUpsertIsNotAppliedAfterDeleteRevision() {
        when(generationStore.activeGeneration()).thenReturn("gen-1");
        when(recordStore.findActive("logical-1")).thenReturn(Optional.empty());
        when(outboxStore.complete(eq("event-1"), eq("token-1"), any())).thenReturn(true);

        worker.process(claimedUpsert("version-1", 1));

        verify(index, never()).upsert(any(), anyString());
        verify(index).delete("version-1", "gen-1");
        verify(outboxStore).complete(eq("event-1"), eq("token-1"), any());
    }

    @Test
    void workerCrashLeaseCanBeRetriedWithoutRevivingOldVersion() {
        when(generationStore.activeGeneration()).thenReturn("gen-1");
        when(recordStore.findActive("logical-1"))
                .thenReturn(Optional.of(activeVersion("version-2", 3)));
        when(outboxStore.complete(eq("event-1"), eq("token-1"), any())).thenReturn(true);

        worker.process(claimedUpsert("version-1", 1));

        verify(index).delete("version-1", "gen-1");
        verify(index, never()).upsert(eq(activeVersion("version-1", 1)), anyString());
        verify(outboxStore).complete(eq("event-1"), eq("token-1"), any());
    }

    @Test
    void doesNotMarkSuccessAfterLosingLease() {
        when(generationStore.activeGeneration()).thenReturn("gen-1");
        when(recordStore.findActive("logical-1"))
                .thenReturn(Optional.of(activeVersion("version-1", 1)));
        when(outboxStore.complete(eq("event-1"), eq("token-1"), any())).thenReturn(false);

        worker.process(claimedUpsert("version-1", 1));

        verify(index).upsert(activeVersion("version-1", 1), "gen-1");
        verify(outboxStore).complete(eq("event-1"), eq("token-1"), any());
    }

    private static MemoryIndexOutboxEntry claimedUpsert(String versionId, long revision) {
        return new MemoryIndexOutboxEntry(
                "event-1",
                "logical-1",
                versionId,
                1,
                revision,
                MemoryIndexOperation.UPSERT,
                MemoryIndexOutboxEntry.Status.CLAIMED,
                1,
                NOW.minusSeconds(10),
                NOW,
                "worker-1",
                "token-1",
                NOW.plusSeconds(60),
                null,
                NOW.minusSeconds(20),
                NOW);
    }

    static MemoryRecordVersion activeVersion(String versionId, long indexRevision) {
        return new MemoryRecordVersion(
                1L,
                "logical-1",
                versionId,
                MemoryType.SEMANTIC,
                MemoryScopeType.PERSONAL_SESSION,
                "api:s1:u1",
                "u1",
                "content " + versionId,
                "hash-" + versionId,
                "summary",
                "run-1",
                List.of("event-source-1"),
                List.of(),
                List.of("tag"),
                0.8,
                0.9,
                MemoryStatus.ACTIVE,
                NOW.minusSeconds(60),
                null,
                null,
                1,
                1,
                "CHAT",
                "source-" + versionId,
                "policy-v1",
                indexRevision,
                NOW.minusSeconds(60),
                NOW,
                false);
    }
}
