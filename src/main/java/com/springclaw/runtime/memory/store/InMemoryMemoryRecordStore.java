package com.springclaw.runtime.memory.store;

import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.port.MemoryRecordStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryMemoryRecordStore implements MemoryRecordStore {

    private static final int MAX_VERSIONS = 5_000;
    private static final int LOGICAL_LOCK_STRIPES = 128;

    private final ConcurrentMap<String, MemoryRecordVersion> recordsByVersionId =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<MemoryRecordVersion>> recordsByLogicalId =
            new ConcurrentHashMap<>();
    private final Object[] logicalLocks = createLogicalLocks();
    private final Set<Long> recordIds = new HashSet<>();
    private final Set<AutomaticSourceKey> automaticSourceKeys = new HashSet<>();
    private final Object capacityLock = new Object();
    private long lastRecordId;

    @Override
    public Optional<MemoryRecordVersion> findByVersionId(String memoryVersionId) {
        return Optional.ofNullable(recordsByVersionId.get(
                requireText(memoryVersionId, "memoryVersionId")
        ));
    }

    @Override
    public Optional<MemoryRecordVersion> findActive(String logicalMemoryId) {
        logicalMemoryId = requireText(logicalMemoryId, "logicalMemoryId");
        Object lock = lockFor(logicalMemoryId);
        synchronized (lock) {
            return recordsByLogicalId.getOrDefault(logicalMemoryId, List.of())
                    .stream()
                    .filter(record -> !record.deleted())
                    .filter(record -> record.status() == MemoryStatus.ACTIVE)
                    .findFirst();
        }
    }

    @Override
    public List<MemoryRecordVersion> findActiveByScope(
            MemoryScope scope,
            Set<MemoryType> types,
            int limit
    ) {
        Objects.requireNonNull(scope, "scope");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return recordsByVersionId.values().stream()
                .filter(record -> !record.deleted())
                .filter(record -> record.status() == MemoryStatus.ACTIVE)
                .filter(record -> record.scopeType() == scope.scopeType())
                .filter(record -> record.scopeId().equals(scope.scopeId()))
                .filter(record -> types == null
                        || types.isEmpty()
                        || types.contains(record.memoryType()))
                .sorted(Comparator
                        .comparing(MemoryRecordVersion::updatedAt)
                        .reversed()
                        .thenComparing(MemoryRecordVersion::memoryVersionId))
                .limit(limit)
                .toList();
    }

    @Override
    public void insert(MemoryRecordVersion version) {
        Objects.requireNonNull(version, "version");
        Object lock = lockFor(version.logicalMemoryId());
        synchronized (lock) {
            synchronized (capacityLock) {
                if (recordsByVersionId.containsKey(version.memoryVersionId())) {
                    throw new IllegalStateException(
                            "duplicate memoryVersionId: "
                                    + version.memoryVersionId()
                    );
                }
                if (version.recordId() != null
                        && recordIds.contains(version.recordId())) {
                    throw new IllegalStateException(
                            "duplicate recordId: " + version.recordId()
                    );
                }
                AutomaticSourceKey sourceKey = AutomaticSourceKey.from(version);
                if (sourceKey != null && automaticSourceKeys.contains(sourceKey)) {
                    throw new IllegalStateException(
                            "duplicate automatic source identity"
                    );
                }
                List<MemoryRecordVersion> logicalRecords =
                        recordsByLogicalId.getOrDefault(
                                version.logicalMemoryId(),
                                List.of()
                        );
                boolean duplicateVersion = logicalRecords.stream()
                        .anyMatch(record -> record.version() == version.version());
                if (duplicateVersion) {
                    throw new IllegalStateException(
                            "duplicate logical memory version: "
                                    + version.logicalMemoryId()
                                    + "/" + version.version()
                    );
                }
                if (version.status() == MemoryStatus.ACTIVE
                        && hasActive(logicalRecords, null)) {
                    throw new IllegalStateException(
                            "logical memory already has an active version: "
                                    + version.logicalMemoryId()
                    );
                }
                if (recordsByVersionId.size() >= MAX_VERSIONS) {
                    throw new IllegalStateException(
                            "memory version capacity exhausted"
                    );
                }
                MemoryRecordVersion persisted = version.recordId() == null
                        ? copyWithRecordId(version, nextRecordId())
                        : version;
                if (version.recordId() != null) {
                    lastRecordId = Math.max(lastRecordId, version.recordId());
                }
                List<MemoryRecordVersion> updatedRecords =
                        new ArrayList<>(logicalRecords);
                updatedRecords.add(persisted);
                recordsByLogicalId.put(
                        persisted.logicalMemoryId(),
                        updatedRecords
                );
                recordsByVersionId.put(
                        persisted.memoryVersionId(),
                        persisted
                );
                recordIds.add(persisted.recordId());
                if (sourceKey != null) {
                    automaticSourceKeys.add(sourceKey);
                }
            }
        }
    }

    @Override
    public boolean compareAndSetStatus(
            String memoryVersionId,
            MemoryStatus expected,
            MemoryStatus next,
            Integer nextActiveSlot,
            long expectedIndexRevision,
            long nextIndexRevision,
            Instant updatedAt
    ) {
        memoryVersionId = requireText(memoryVersionId, "memoryVersionId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(updatedAt, "updatedAt");
        MemoryRecordVersion observed = recordsByVersionId.get(memoryVersionId);
        if (observed == null) {
            return false;
        }
        Object lock = lockFor(observed.logicalMemoryId());
        synchronized (lock) {
            MemoryRecordVersion current = recordsByVersionId.get(memoryVersionId);
            if (current == null
                    || current.status() != expected
                    || current.indexRevision() != expectedIndexRevision) {
                return false;
            }
            if (nextIndexRevision <= expectedIndexRevision) {
                throw new IllegalArgumentException(
                        "nextIndexRevision must advance"
                );
            }
            List<MemoryRecordVersion> logicalRecords =
                    recordsByLogicalId.get(current.logicalMemoryId());
            if (next == MemoryStatus.ACTIVE
                    && hasActive(logicalRecords, current.memoryVersionId())) {
                throw new IllegalStateException(
                        "logical memory already has an active version: "
                                + current.logicalMemoryId()
                );
            }
            MemoryRecordVersion replacement = copyWithStatus(
                    current,
                    next,
                    nextActiveSlot,
                    nextIndexRevision,
                    updatedAt
            );
            synchronized (capacityLock) {
                int index = findVersionIndex(
                        logicalRecords,
                        current.memoryVersionId()
                );
                logicalRecords.set(index, replacement);
                recordsByVersionId.put(memoryVersionId, replacement);
            }
            return true;
        }
    }

    private Object lockFor(String logicalMemoryId) {
        int index = Math.floorMod(
                logicalMemoryId.hashCode(),
                logicalLocks.length
        );
        return logicalLocks[index];
    }

    private static Object[] createLogicalLocks() {
        Object[] locks = new Object[LOGICAL_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private static boolean hasActive(
            List<MemoryRecordVersion> records,
            String excludedVersionId
    ) {
        return records.stream()
                .anyMatch(record -> record.status() == MemoryStatus.ACTIVE
                        && !record.memoryVersionId().equals(excludedVersionId));
    }

    private static int findVersionIndex(
            List<MemoryRecordVersion> records,
            String memoryVersionId
    ) {
        for (int index = 0; index < records.size(); index++) {
            if (records.get(index).memoryVersionId().equals(memoryVersionId)) {
                return index;
            }
        }
        throw new IllegalStateException(
                "memory version missing from logical index: " + memoryVersionId
        );
    }

    private static MemoryRecordVersion copyWithStatus(
            MemoryRecordVersion current,
            MemoryStatus next,
            Integer nextActiveSlot,
            long nextIndexRevision,
            Instant updatedAt
    ) {
        return new MemoryRecordVersion(
                current.recordId(),
                current.logicalMemoryId(),
                current.memoryVersionId(),
                current.memoryType(),
                current.scopeType(),
                current.scopeId(),
                current.ownerUserId(),
                current.content(),
                current.contentHash(),
                current.summary(),
                current.sourceRunId(),
                current.sourceEventIds(),
                current.evidenceRefs(),
                current.tags(),
                current.importance(),
                current.confidence(),
                next,
                current.validFrom(),
                current.validUntil(),
                current.supersedesRecordId(),
                current.version(),
                nextActiveSlot,
                current.sourceKind(),
                current.sourceIdentity(),
                current.extractionPolicyVersion(),
                nextIndexRevision,
                current.createdAt(),
                updatedAt,
                current.deleted()
        );
    }

    private long nextRecordId() {
        if (lastRecordId == Long.MAX_VALUE) {
            throw new IllegalStateException("recordId capacity exhausted");
        }
        lastRecordId++;
        return lastRecordId;
    }

    private static MemoryRecordVersion copyWithRecordId(
            MemoryRecordVersion version,
            long recordId
    ) {
        return new MemoryRecordVersion(
                recordId,
                version.logicalMemoryId(),
                version.memoryVersionId(),
                version.memoryType(),
                version.scopeType(),
                version.scopeId(),
                version.ownerUserId(),
                version.content(),
                version.contentHash(),
                version.summary(),
                version.sourceRunId(),
                version.sourceEventIds(),
                version.evidenceRefs(),
                version.tags(),
                version.importance(),
                version.confidence(),
                version.status(),
                version.validFrom(),
                version.validUntil(),
                version.supersedesRecordId(),
                version.version(),
                version.activeSlot(),
                version.sourceKind(),
                version.sourceIdentity(),
                version.extractionPolicyVersion(),
                version.indexRevision(),
                version.createdAt(),
                version.updatedAt(),
                version.deleted()
        );
    }

    private record AutomaticSourceKey(
            String sourceKind,
            String sourceIdentity,
            String extractionPolicyVersion,
            MemoryType memoryType
    ) {
        private static AutomaticSourceKey from(MemoryRecordVersion version) {
            if (version.sourceKind() == null
                    || version.sourceIdentity() == null
                    || version.extractionPolicyVersion() == null) {
                return null;
            }
            return new AutomaticSourceKey(
                    version.sourceKind(),
                    version.sourceIdentity(),
                    version.extractionPolicyVersion(),
                    version.memoryType()
            );
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
