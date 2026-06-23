package com.springclaw.service.memory.index;

import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.port.MemoryRecordStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;

@Component
@ConditionalOnBean(MemoryVectorIndex.class)
public class MemoryIndexReconciler {

    private final MemoryRecordStore recordStore;
    private final MemoryVectorIndex index;
    private final MemoryIndexGenerationStore generationStore;

    public MemoryIndexReconciler(
            MemoryRecordStore recordStore,
            MemoryVectorIndex index,
            MemoryIndexGenerationStore generationStore
    ) {
        this.recordStore = Objects.requireNonNull(recordStore, "recordStore");
        this.index = Objects.requireNonNull(index, "index");
        this.generationStore = Objects.requireNonNull(generationStore, "generationStore");
    }

    public void reconcileOnce(int limit) {
        String generation = generationStore.activeGeneration();
        String cursor = null;
        do {
            MemoryVectorIndex.IndexedPage page =
                    index.listIndexedVersionIds(generation, cursor, limit);
            for (String versionId : page.memoryVersionIds()) {
                if (!isCurrentActive(versionId)) {
                    index.delete(versionId, generation);
                }
            }
            cursor = page.nextCursor();
        } while (cursor != null && !cursor.isBlank());
    }

    private boolean isCurrentActive(String memoryVersionId) {
        Optional<MemoryRecordVersion> version = recordStore.findByVersionId(memoryVersionId);
        if (version.isEmpty()) {
            return false;
        }
        Optional<MemoryRecordVersion> active =
                recordStore.findActive(version.get().logicalMemoryId());
        return active.isPresent()
                && active.get().memoryVersionId().equals(memoryVersionId);
    }
}
