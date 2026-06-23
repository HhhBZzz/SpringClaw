package com.springclaw.service.memory.index;

import com.springclaw.runtime.memory.contract.MemoryRecordVersion;

import java.util.List;

public interface MemoryVectorIndex {

    void createGeneration(String generation);

    void upsert(MemoryRecordVersion version, String generation);

    void delete(String memoryVersionId, String generation);

    IndexedPage listIndexedVersionIds(String generation, String cursor, int limit);

    record IndexedPage(List<String> memoryVersionIds, String nextCursor) {
        public IndexedPage {
            memoryVersionIds = memoryVersionIds == null ? List.of() : List.copyOf(memoryVersionIds);
        }
    }
}
