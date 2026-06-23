package com.springclaw.runtime.memory.port;

import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.ShortTermMemoryEntry;

import java.util.List;

public interface ShortTermMemoryStore {

    void append(MemoryScope scope, ShortTermMemoryEntry entry);

    List<ShortTermMemoryEntry> readRecent(MemoryScope scope, int limit);

    void mergeRecovery(
            MemoryScope scope,
            long watermark,
            List<ShortTermMemoryEntry> persistedEntries
    );
}
