package com.springclaw.service.memory;

import com.springclaw.runtime.memory.contract.MemoryIndexOperation;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.port.MemoryIndexOutboxStore;
import com.springclaw.runtime.memory.port.MemoryRecordStore;
import com.springclaw.runtime.memory.store.InMemoryMemoryIndexOutboxStore;
import com.springclaw.runtime.memory.store.InMemoryMemoryRecordStore;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@Service
public class MemoryManagementService {

    private final MemoryRecordStore recordStore;
    private final MemoryIndexOutboxStore outboxStore;
    private final MemoryVersionFactory factory;
    private final InMemoryMemoryTransactionBoundary inMemoryBoundary;

    public MemoryManagementService(
            MemoryRecordStore recordStore,
            MemoryIndexOutboxStore outboxStore,
            MemoryVersionFactory factory
    ) {
        this.recordStore = Objects.requireNonNull(recordStore, "recordStore");
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.inMemoryBoundary =
                recordStore instanceof InMemoryMemoryRecordStore inMemoryRecords
                && outboxStore
                instanceof InMemoryMemoryIndexOutboxStore inMemoryOutbox
                        ? new InMemoryMemoryTransactionBoundary(
                                inMemoryRecords,
                                inMemoryOutbox
                        )
                        : null;
    }

    @Transactional
    public MemoryRecordVersion create(MemoryWriteCommand command) {
        Objects.requireNonNull(command, "command");
        return atomic(
                transactionKey(command),
                command.logicalMemoryId(),
                () -> createInternal(command)
        );
    }

    @Transactional
    public MemoryRecordVersion supersede(
            String currentVersionId,
            MemoryWriteCommand replacement
    ) {
        String targetVersionId = requireText(
                currentVersionId,
                "currentVersionId"
        );
        Objects.requireNonNull(replacement, "replacement");
        MemoryRecordVersion observed = requireVersion(targetVersionId);
        if (!observed.logicalMemoryId().equals(replacement.logicalMemoryId())) {
            throw new IllegalArgumentException(
                    "replacement logicalMemoryId must match current version"
            );
        }
        if (replacement.requestedStatus() != MemoryStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "replacement requestedStatus must be ACTIVE"
            );
        }
        return atomic(observed.logicalMemoryId(), observed.logicalMemoryId(), () ->
                supersedeInternal(targetVersionId, replacement));
    }

    @Transactional
    public MemoryRecordVersion transition(
            String versionId,
            MemoryStatus expected,
            MemoryStatus next,
            Instant at
    ) {
        versionId = requireText(versionId, "versionId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        at = Objects.requireNonNull(at, "at");
        MemoryRecordVersion observed = requireVersion(versionId);
        String targetVersionId = versionId;
        Instant transitionAt = at;
        return atomic(
                observed.logicalMemoryId(),
                observed.logicalMemoryId(),
                () -> transitionInternal(
                targetVersionId,
                expected,
                next,
                transitionAt
        ));
    }

    private MemoryRecordVersion createInternal(MemoryWriteCommand command) {
        Optional<MemoryRecordVersion> existing = findAutomaticSource(command);
        if (existing.isPresent()) {
            return existing.get();
        }
        MemoryStatus status = command.requestedStatus();
        MemoryRecordVersion version = factory.create(
                command,
                1,
                1L,
                null,
                status,
                status == MemoryStatus.ACTIVE ? 1 : null
        );
        try {
            recordStore.insert(version);
        } catch (DuplicateKeyException duplicate) {
            if (!command.automaticSource()) {
                throw duplicate;
            }
            return recordStore.findByAutomaticSourceCurrent(
                            command.sourceKind(),
                            command.sourceIdentity(),
                            command.extractionPolicyVersion(),
                            command.memoryType()
                    )
                    .orElseThrow(() -> duplicate);
        }
        MemoryRecordVersion persisted = requireVersion(
                version.memoryVersionId()
        );
        if (status == MemoryStatus.ACTIVE) {
            outboxStore.insert(factory.outbox(
                    persisted,
                    persisted.indexRevision(),
                    MemoryIndexOperation.UPSERT,
                    persisted.createdAt()
            ));
        }
        return persisted;
    }

    private MemoryRecordVersion supersedeInternal(
            String currentVersionId,
            MemoryWriteCommand replacement
    ) {
        MemoryRecordVersion current = requireVersion(currentVersionId);
        if (current.status() != MemoryStatus.ACTIVE) {
            throw new IllegalStateException(
                    "current memory version must be ACTIVE"
            );
        }
        if (findAutomaticSource(replacement).isPresent()) {
            throw new IllegalStateException(
                    "replacement automatic source identity already exists"
            );
        }

        long deleteRevision = current.indexRevision() + 1;
        boolean deactivated = recordStore.compareAndSetStatus(
                current.memoryVersionId(),
                MemoryStatus.ACTIVE,
                MemoryStatus.SUPERSEDED,
                null,
                current.indexRevision(),
                deleteRevision,
                factory.now()
        );
        if (!deactivated) {
            throw new IllegalStateException(
                    "memory supersede compare-and-set failed"
            );
        }
        MemoryRecordVersion superseded = requireVersion(
                current.memoryVersionId()
        );
        outboxStore.insert(factory.outbox(
                superseded,
                deleteRevision,
                MemoryIndexOperation.DELETE,
                superseded.updatedAt()
        ));

        long upsertRevision = deleteRevision + 1;
        MemoryRecordVersion replacementVersion = factory.create(
                replacement,
                current.version() + 1,
                upsertRevision,
                current.recordId(),
                MemoryStatus.ACTIVE,
                1
        );
        recordStore.insert(replacementVersion);
        MemoryRecordVersion persistedReplacement = requireVersion(
                replacementVersion.memoryVersionId()
        );
        outboxStore.insert(factory.outbox(
                persistedReplacement,
                upsertRevision,
                MemoryIndexOperation.UPSERT,
                persistedReplacement.createdAt()
        ));
        return persistedReplacement;
    }

    private MemoryRecordVersion transitionInternal(
            String versionId,
            MemoryStatus expected,
            MemoryStatus next,
            Instant at
    ) {
        MemoryRecordVersion current = requireVersion(versionId);
        if (current.status() != expected) {
            throw new IllegalStateException(
                    "memory transition compare-and-set failed"
            );
        }
        if (expected == next) {
            return current;
        }
        requireAllowedTransition(expected, next);
        if (at.isBefore(current.updatedAt())) {
            throw new IllegalArgumentException(
                    "transition time must not precede updatedAt"
            );
        }

        long nextRevision = current.indexRevision() + 1;
        boolean changed = recordStore.compareAndSetStatus(
                versionId,
                expected,
                next,
                next == MemoryStatus.ACTIVE ? 1 : null,
                current.indexRevision(),
                nextRevision,
                at
        );
        if (!changed) {
            throw new IllegalStateException(
                    "memory transition compare-and-set failed"
            );
        }
        MemoryRecordVersion transitioned = requireVersion(versionId);
        if (expected != MemoryStatus.ACTIVE
                && next == MemoryStatus.ACTIVE) {
            outboxStore.insert(factory.outbox(
                    transitioned,
                    nextRevision,
                    MemoryIndexOperation.UPSERT,
                    at
            ));
        } else if (expected == MemoryStatus.ACTIVE
                && next != MemoryStatus.ACTIVE) {
            outboxStore.insert(factory.outbox(
                    transitioned,
                    nextRevision,
                    MemoryIndexOperation.DELETE,
                    at
            ));
        }
        return transitioned;
    }

    private Optional<MemoryRecordVersion> findAutomaticSource(
            MemoryWriteCommand command
    ) {
        if (!command.automaticSource()) {
            return Optional.empty();
        }
        return recordStore.findByAutomaticSource(
                command.sourceKind(),
                command.sourceIdentity(),
                command.extractionPolicyVersion(),
                command.memoryType()
        );
    }

    private MemoryRecordVersion requireVersion(String versionId) {
        return recordStore.findByVersionId(versionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "memory version not found: " + versionId
                ));
    }

    private <T> T atomic(
            String transactionKey,
            String logicalMemoryId,
            Supplier<T> work
    ) {
        if (inMemoryBoundary != null) {
            return inMemoryBoundary.execute(
                    transactionKey,
                    logicalMemoryId,
                    work
            );
        }
        return work.get();
    }

    private static String transactionKey(MemoryWriteCommand command) {
        if (!command.automaticSource()) {
            return "logical:" + command.logicalMemoryId();
        }
        return "source:"
                + command.sourceKind() + "\u0000"
                + command.sourceIdentity() + "\u0000"
                + command.extractionPolicyVersion() + "\u0000"
                + command.memoryType().name();
    }

    private static void requireAllowedTransition(
            MemoryStatus expected,
            MemoryStatus next
    ) {
        boolean allowed = expected == MemoryStatus.CANDIDATE
                && (next == MemoryStatus.ACTIVE
                || next == MemoryStatus.REJECTED)
                || expected == MemoryStatus.ACTIVE
                && (next == MemoryStatus.EXPIRED
                || next == MemoryStatus.REJECTED
                || next == MemoryStatus.SUPERSEDED);
        if (!allowed) {
            throw new IllegalArgumentException(
                    "unsupported memory transition: "
                            + expected + " -> " + next
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
