package com.springclaw.service.memory;

import com.springclaw.runtime.memory.contract.MemoryIndexOperation;
import com.springclaw.runtime.memory.contract.MemoryIndexOutboxEntry;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScopeType;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

@Component
public class MemoryVersionFactory {

    private final Clock clock;

    @Autowired
    public MemoryVersionFactory(ObjectProvider<Clock> clockProvider) {
        this(clockProvider.getIfAvailable(Clock::systemUTC));
    }

    public MemoryVersionFactory(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public MemoryRecordVersion create(
            MemoryWriteCommand command,
            int version,
            long indexRevision,
            Long supersedesRecordId,
            MemoryStatus status,
            Integer activeSlot
    ) {
        Objects.requireNonNull(command, "command");
        Instant now = clock.instant();
        return new MemoryRecordVersion(
                null,
                command.logicalMemoryId(),
                memoryVersionId(command.logicalMemoryId(), version),
                command.memoryType(),
                command.scope().scopeType(),
                command.scope().scopeId(),
                ownerUserId(command),
                command.content(),
                contentHash(command.content()),
                command.summary(),
                command.sourceRunId(),
                command.sourceEventIds(),
                command.evidenceRefs(),
                command.tags(),
                command.importance(),
                command.confidence(),
                status,
                command.validFrom(),
                command.validUntil(),
                supersedesRecordId,
                version,
                activeSlot,
                command.sourceKind(),
                command.sourceIdentity(),
                command.extractionPolicyVersion(),
                indexRevision,
                now,
                now,
                false
        );
    }

    public MemoryIndexOutboxEntry outbox(
            MemoryRecordVersion version,
            long indexRevision,
            MemoryIndexOperation operation,
            Instant at
    ) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(operation, "operation");
        at = Objects.requireNonNull(at, "at");
        return new MemoryIndexOutboxEntry(
                outboxEventId(
                        version.memoryVersionId(),
                        indexRevision,
                        operation
                ),
                version.logicalMemoryId(),
                version.memoryVersionId(),
                version.version(),
                indexRevision,
                operation,
                MemoryIndexOutboxEntry.Status.PENDING,
                0,
                at,
                null,
                null,
                null,
                null,
                null,
                at,
                at
        );
    }

    public String memoryVersionId(String logicalMemoryId, int version) {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        return sha256(requireText(logicalMemoryId, "logicalMemoryId")
                + ":" + version);
    }

    public String contentHash(String content) {
        return sha256(MemoryWriteCommand.normalizeContent(content));
    }

    public String outboxEventId(
            String memoryVersionId,
            long indexRevision,
            MemoryIndexOperation operation
    ) {
        if (indexRevision <= 0) {
            throw new IllegalArgumentException(
                    "indexRevision must be positive"
            );
        }
        return sha256(requireText(memoryVersionId, "memoryVersionId")
                + ":" + indexRevision
                + ":" + Objects.requireNonNull(operation, "operation").name());
    }

    public Instant now() {
        return clock.instant();
    }

    private static String ownerUserId(MemoryWriteCommand command) {
        MemoryScopeType scopeType = command.scope().scopeType();
        if (scopeType == MemoryScopeType.PERSONAL_SESSION
                || scopeType == MemoryScopeType.USER) {
            return command.scope().requestingUserId();
        }
        return null;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
