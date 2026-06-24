package com.springclaw.service.memory.index;

import com.springclaw.runtime.memory.contract.MemoryIndexOutboxEntry;
import com.springclaw.runtime.memory.contract.MemoryIndexOperation;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.port.MemoryIndexOutboxStore;
import com.springclaw.runtime.memory.port.MemoryRecordStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
@ConditionalOnBean(MemoryVectorIndex.class)
@ConditionalOnProperty(prefix = "springclaw.memory.index", name = "enabled", havingValue = "true")
public class MemoryIndexRebuildService {

    private static final int ACTIVE_SCAN_LIMIT = 5_000;
    private static final int TAIL_SCAN_LIMIT = 1_000;

    private final MemoryRecordStore recordStore;
    private final MemoryIndexOutboxStore outboxStore;
    private final MemoryVectorIndex index;
    private final MemoryIndexGenerationStore generationStore;
    private final int activeScanLimit;
    private final int tailScanLimit;
    @SuppressWarnings("unused")
    private final Clock clock;

    @Autowired
    public MemoryIndexRebuildService(
            MemoryRecordStore recordStore,
            MemoryIndexOutboxStore outboxStore,
            MemoryVectorIndex index,
            MemoryIndexGenerationStore generationStore
    ) {
        this(
                recordStore,
                outboxStore,
                index,
                generationStore,
                ACTIVE_SCAN_LIMIT,
                TAIL_SCAN_LIMIT,
                Clock.systemUTC()
        );
    }

    MemoryIndexRebuildService(
            MemoryRecordStore recordStore,
            MemoryIndexOutboxStore outboxStore,
            MemoryVectorIndex index,
            MemoryIndexGenerationStore generationStore,
            Clock clock
    ) {
        this(
                recordStore,
                outboxStore,
                index,
                generationStore,
                ACTIVE_SCAN_LIMIT,
                TAIL_SCAN_LIMIT,
                clock
        );
    }

    MemoryIndexRebuildService(
            MemoryRecordStore recordStore,
            MemoryIndexOutboxStore outboxStore,
            MemoryVectorIndex index,
            MemoryIndexGenerationStore generationStore,
            int activeScanLimit,
            int tailScanLimit,
            Clock clock
    ) {
        this.recordStore = Objects.requireNonNull(recordStore, "recordStore");
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
        this.index = Objects.requireNonNull(index, "index");
        this.generationStore = Objects.requireNonNull(generationStore, "generationStore");
        if (activeScanLimit <= 0 || tailScanLimit <= 0) {
            throw new IllegalArgumentException("scan limits must be positive");
        }
        this.activeScanLimit = activeScanLimit;
        this.tailScanLimit = tailScanLimit;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void rebuild() {
        generationStore.markDegraded(true);
        try {
            String generation = generationStore.nextGeneration();
            index.createGeneration(generation);
            long watermark = copyActiveRecords(generation);
            applyTailEvents(generation, watermark);
            generationStore.activate(generation);
        } finally {
            generationStore.markDegraded(false);
        }
    }

    private long copyActiveRecords(String generation) {
        long afterRecordId = 0L;
        long watermark = 0L;
        while (true) {
            List<MemoryRecordVersion> batch =
                    recordStore.findActiveAfterRecordId(afterRecordId, activeScanLimit);
            if (batch.isEmpty()) {
                return watermark;
            }
            for (MemoryRecordVersion active : batch) {
                index.upsert(active, generation);
                watermark = Math.max(watermark, active.indexRevision());
                if (active.recordId() != null) {
                    afterRecordId = Math.max(afterRecordId, active.recordId());
                }
            }
            if (batch.size() < activeScanLimit) {
                return watermark;
            }
            if (afterRecordId <= 0) {
                throw new IllegalStateException(
                        "cannot paginate active memory records without recordId"
                );
            }
        }
    }

    private void applyTailEvents(String generation, long watermark) {
        long cursorRevision = watermark;
        String cursorEventId = null;
        while (true) {
            List<MemoryIndexOutboxEntry> batch = outboxStore
                    .findPendingAfterRevision(cursorRevision, cursorEventId, tailScanLimit)
                    .stream()
                    .sorted(Comparator
                            .comparingLong(MemoryIndexOutboxEntry::indexRevision)
                            .thenComparing(MemoryIndexOutboxEntry::eventId))
                    .toList();
            if (batch.isEmpty()) {
                return;
            }
            for (MemoryIndexOutboxEntry entry : batch) {
                applyTail(entry, generation);
                cursorRevision = entry.indexRevision();
                cursorEventId = entry.eventId();
            }
            if (batch.size() < tailScanLimit) {
                return;
            }
        }
    }

    private void applyTail(MemoryIndexOutboxEntry entry, String generation) {
        if (entry.operation() == MemoryIndexOperation.DELETE) {
            index.delete(entry.memoryVersionId(), generation);
            return;
        }
        recordStore.findActive(entry.logicalMemoryId())
                .filter(active -> active.memoryVersionId().equals(entry.memoryVersionId()))
                .ifPresent(active -> index.upsert(active, generation));
    }
}
