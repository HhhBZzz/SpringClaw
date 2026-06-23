package com.springclaw.service.memory.index;

import com.springclaw.runtime.memory.contract.MemoryIndexOperation;
import com.springclaw.runtime.memory.contract.MemoryIndexOutboxEntry;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.port.MemoryIndexOutboxStore;
import com.springclaw.runtime.memory.port.MemoryRecordStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

@Component
@ConditionalOnBean(MemoryVectorIndex.class)
public class MemoryIndexWorker {

    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final MemoryRecordStore recordStore;
    private final MemoryIndexOutboxStore outboxStore;
    private final MemoryVectorIndex index;
    private final MemoryIndexGenerationStore generationStore;
    private final Clock clock;

    public MemoryIndexWorker(
            MemoryRecordStore recordStore,
            MemoryIndexOutboxStore outboxStore,
            MemoryVectorIndex index,
            MemoryIndexGenerationStore generationStore
    ) {
        this(recordStore, outboxStore, index, generationStore, Clock.systemUTC());
    }

    MemoryIndexWorker(
            MemoryRecordStore recordStore,
            MemoryIndexOutboxStore outboxStore,
            MemoryVectorIndex index,
            MemoryIndexGenerationStore generationStore,
            Clock clock
    ) {
        this.recordStore = Objects.requireNonNull(recordStore, "recordStore");
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
        this.index = Objects.requireNonNull(index, "index");
        this.generationStore = Objects.requireNonNull(generationStore, "generationStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void process(MemoryIndexOutboxEntry claimed) {
        Objects.requireNonNull(claimed, "claimed");
        if (claimed.status() != MemoryIndexOutboxEntry.Status.CLAIMED) {
            throw new IllegalArgumentException("claimed entry is required");
        }
        try {
            String generation = generationStore.activeGeneration();
            Optional<MemoryRecordVersion> active =
                    recordStore.findActive(claimed.logicalMemoryId());
            if (claimed.operation() == MemoryIndexOperation.DELETE) {
                index.delete(claimed.memoryVersionId(), generation);
            } else if (isCurrentActive(claimed, active)) {
                index.upsert(active.orElseThrow(), generation);
                compensateIfAuthorityChanged(claimed, generation);
            } else {
                index.delete(claimed.memoryVersionId(), generation);
            }
            outboxStore.complete(claimed.eventId(), claimed.claimToken(), clock.instant());
        } catch (Exception ex) {
            outboxStore.fail(
                    claimed.eventId(),
                    claimed.claimToken(),
                    ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                    clock.instant().plus(RETRY_DELAY));
        }
    }

    private boolean isCurrentActive(
            MemoryIndexOutboxEntry claimed,
            Optional<MemoryRecordVersion> active
    ) {
        return active.isPresent()
                && active.get().memoryVersionId().equals(claimed.memoryVersionId())
                && active.get().indexRevision() == claimed.indexRevision();
    }

    private void compensateIfAuthorityChanged(
            MemoryIndexOutboxEntry claimed,
            String generation
    ) {
        Optional<MemoryRecordVersion> after = recordStore.findActive(claimed.logicalMemoryId());
        if (after.isEmpty()
                || !after.get().memoryVersionId().equals(claimed.memoryVersionId())
                || after.get().indexRevision() != claimed.indexRevision()) {
            index.delete(claimed.memoryVersionId(), generation);
        }
    }
}
