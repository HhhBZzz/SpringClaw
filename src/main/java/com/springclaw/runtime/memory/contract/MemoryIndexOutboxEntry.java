package com.springclaw.runtime.memory.contract;

import java.time.Instant;
import java.util.Objects;

public record MemoryIndexOutboxEntry(
        String eventId,
        String logicalMemoryId,
        String memoryVersionId,
        int memoryVersion,
        long indexRevision,
        MemoryIndexOperation operation,
        Status status,
        int attempts,
        Instant availableAt,
        Instant claimedAt,
        String claimOwner,
        String claimToken,
        Instant leaseUntil,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
    public enum Status {
        PENDING,
        CLAIMED,
        SUCCEEDED,
        FAILED
    }

    public MemoryIndexOutboxEntry {
        eventId = requireText(eventId, "eventId");
        logicalMemoryId = requireText(logicalMemoryId, "logicalMemoryId");
        memoryVersionId = requireText(memoryVersionId, "memoryVersionId");
        operation = Objects.requireNonNull(operation, "operation");
        status = Objects.requireNonNull(status, "status");
        availableAt = Objects.requireNonNull(availableAt, "availableAt");
        claimOwner = optionalText(claimOwner);
        claimToken = optionalText(claimToken);
        lastError = optionalText(lastError);
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (memoryVersion <= 0) {
            throw new IllegalArgumentException("memoryVersion must be positive");
        }
        if (indexRevision <= 0) {
            throw new IllegalArgumentException("indexRevision must be positive");
        }
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
        validateState(
                status,
                attempts,
                claimedAt,
                claimOwner,
                claimToken,
                leaseUntil,
                lastError
        );
    }

    public MemoryIndexOutboxEntry claim(
            String owner,
            Instant now,
            Instant newLeaseUntil,
            String token
    ) {
        owner = requireText(owner, "owner");
        now = Objects.requireNonNull(now, "now");
        newLeaseUntil = Objects.requireNonNull(newLeaseUntil, "leaseUntil");
        token = requireText(token, "token");
        if (!newLeaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("leaseUntil must be after now");
        }
        if (status == Status.PENDING || status == Status.FAILED) {
            if (availableAt.isAfter(now)) {
                throw new IllegalStateException("entry is not available");
            }
        } else if (status == Status.CLAIMED) {
            if (leaseUntil == null || leaseUntil.isAfter(now)) {
                throw new IllegalStateException("claim lease has not expired");
            }
        } else {
            throw new IllegalStateException("SUCCEEDED entry cannot be claimed");
        }
        return new MemoryIndexOutboxEntry(
                eventId,
                logicalMemoryId,
                memoryVersionId,
                memoryVersion,
                indexRevision,
                operation,
                Status.CLAIMED,
                attempts + 1,
                availableAt,
                now,
                owner,
                token,
                newLeaseUntil,
                null,
                createdAt,
                now
        );
    }

    public MemoryIndexOutboxEntry complete(Instant at) {
        requireClaimed();
        at = Objects.requireNonNull(at, "at");
        if (at.isBefore(claimedAt)) {
            throw new IllegalArgumentException("completion time precedes claim");
        }
        return new MemoryIndexOutboxEntry(
                eventId,
                logicalMemoryId,
                memoryVersionId,
                memoryVersion,
                indexRevision,
                operation,
                Status.SUCCEEDED,
                attempts,
                availableAt,
                null,
                null,
                null,
                null,
                null,
                createdAt,
                at
        );
    }

    public MemoryIndexOutboxEntry fail(String error, Instant retryAt) {
        requireClaimed();
        error = requireText(error, "error");
        retryAt = Objects.requireNonNull(retryAt, "retryAt");
        if (retryAt.isBefore(claimedAt)) {
            throw new IllegalArgumentException("retryAt precedes claim");
        }
        return new MemoryIndexOutboxEntry(
                eventId,
                logicalMemoryId,
                memoryVersionId,
                memoryVersion,
                indexRevision,
                operation,
                Status.FAILED,
                attempts,
                retryAt,
                null,
                null,
                null,
                null,
                error,
                createdAt,
                retryAt
        );
    }

    private void requireClaimed() {
        if (status != Status.CLAIMED) {
            throw new IllegalStateException("entry must be CLAIMED");
        }
    }

    private static void validateState(
            Status status,
            int attempts,
            Instant claimedAt,
            String claimOwner,
            String claimToken,
            Instant leaseUntil,
            String lastError
    ) {
        boolean hasAnyClaimField = claimedAt != null
                || claimOwner != null
                || claimToken != null
                || leaseUntil != null;
        if (status == Status.CLAIMED) {
            if (attempts <= 0) {
                throw new IllegalArgumentException(
                        "CLAIMED status requires positive attempts"
                );
            }
            if (claimedAt == null) {
                throw new IllegalArgumentException(
                        "CLAIMED status requires claimedAt"
                );
            }
            if (claimOwner == null) {
                throw new IllegalArgumentException(
                        "CLAIMED status requires claimOwner"
                );
            }
            if (claimToken == null) {
                throw new IllegalArgumentException(
                        "CLAIMED status requires claimToken"
                );
            }
            if (leaseUntil == null || !leaseUntil.isAfter(claimedAt)) {
                throw new IllegalArgumentException(
                        "CLAIMED status requires leaseUntil after claimedAt"
                );
            }
            if (lastError != null) {
                throw new IllegalArgumentException(
                        "CLAIMED status requires null lastError"
                );
            }
            return;
        }
        if (hasAnyClaimField) {
            throw new IllegalArgumentException(
                    status + " status requires null claim fields"
            );
        }
        if (status == Status.PENDING) {
            if (attempts != 0) {
                throw new IllegalArgumentException(
                        "PENDING status requires zero attempts"
                );
            }
            if (lastError != null) {
                throw new IllegalArgumentException(
                        "PENDING status requires null lastError"
                );
            }
        } else if (status == Status.SUCCEEDED) {
            if (attempts <= 0 || lastError != null) {
                throw new IllegalArgumentException(
                        "SUCCEEDED status requires attempts and no lastError"
                );
            }
        } else if (attempts <= 0 || lastError == null) {
            throw new IllegalArgumentException(
                    "FAILED status requires attempts and lastError"
            );
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
