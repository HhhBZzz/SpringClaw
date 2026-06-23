package com.springclaw.runtime.memory.port;

import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface MemoryRecordStore {

    Optional<MemoryRecordVersion> findByVersionId(String memoryVersionId);

    Optional<MemoryRecordVersion> findActive(String logicalMemoryId);

    Optional<MemoryRecordVersion> findByAutomaticSource(
            String sourceKind,
            String sourceIdentity,
            String extractionPolicyVersion,
            MemoryType memoryType
    );

    default Optional<MemoryRecordVersion> findByAutomaticSourceCurrent(
            String sourceKind,
            String sourceIdentity,
            String extractionPolicyVersion,
            MemoryType memoryType
    ) {
        return findByAutomaticSource(
                sourceKind,
                sourceIdentity,
                extractionPolicyVersion,
                memoryType
        );
    }

    List<MemoryRecordVersion> findActiveByScope(
            MemoryScope scope,
            Set<MemoryType> types,
            int limit
    );

    void insert(MemoryRecordVersion version);

    boolean compareAndSetStatus(
            String memoryVersionId,
            MemoryStatus expected,
            MemoryStatus next,
            Integer nextActiveSlot,
            long expectedIndexRevision,
            long nextIndexRevision,
            Instant updatedAt
    );
}
