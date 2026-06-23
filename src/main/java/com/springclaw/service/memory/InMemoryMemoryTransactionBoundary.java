package com.springclaw.service.memory;

import com.springclaw.runtime.memory.store.InMemoryMemoryIndexOutboxStore;
import com.springclaw.runtime.memory.store.InMemoryMemoryRecordStore;

import java.util.Objects;
import java.util.function.Supplier;

public final class InMemoryMemoryTransactionBoundary {

    private static final int LOGICAL_LOCK_STRIPES = 128;

    private final InMemoryMemoryRecordStore recordStore;
    private final InMemoryMemoryIndexOutboxStore outboxStore;
    private final Object[] logicalLocks = createLogicalLocks();

    public InMemoryMemoryTransactionBoundary(
            InMemoryMemoryRecordStore recordStore,
            InMemoryMemoryIndexOutboxStore outboxStore
    ) {
        this.recordStore = Objects.requireNonNull(recordStore, "recordStore");
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
    }

    public <T> T execute(String logicalMemoryId, Supplier<T> work) {
        return execute(logicalMemoryId, logicalMemoryId, work);
    }

    public <T> T execute(
            String transactionKey,
            String logicalMemoryId,
            Supplier<T> work
    ) {
        transactionKey = requireText(transactionKey, "transactionKey");
        logicalMemoryId = requireText(logicalMemoryId, "logicalMemoryId");
        Objects.requireNonNull(work, "work");
        String targetLogicalId = logicalMemoryId;
        synchronized (lockFor(transactionKey)) {
            return recordStore.executeExclusively(() ->
                    outboxStore.executeExclusively(() -> {
                        InMemoryMemoryRecordStore.Snapshot recordSnapshot =
                                recordStore.snapshot(targetLogicalId);
                        InMemoryMemoryIndexOutboxStore.Snapshot outboxSnapshot =
                                outboxStore.snapshot(targetLogicalId);
                        try {
                            return work.get();
                        } catch (RuntimeException | Error failure) {
                            recordStore.restore(recordSnapshot);
                            outboxStore.restore(outboxSnapshot);
                            throw failure;
                        }
                    })
            );
        }
    }

    private Object lockFor(String logicalMemoryId) {
        return logicalLocks[Math.floorMod(
                logicalMemoryId.hashCode(),
                logicalLocks.length
        )];
    }

    private static Object[] createLogicalLocks() {
        Object[] locks = new Object[LOGICAL_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
