package com.springclaw.runtime.memory.port;

import com.springclaw.runtime.memory.contract.MemoryIndexOutboxEntry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MemoryIndexOutboxStore {

    void insert(MemoryIndexOutboxEntry entry);

    Optional<MemoryIndexOutboxEntry> claimNext(
            String owner,
            Instant now,
            Instant leaseUntil
    );

    boolean complete(String eventId, String claimToken, Instant completedAt);

    boolean fail(
            String eventId,
            String claimToken,
            String error,
            Instant retryAt
    );

    List<MemoryIndexOutboxEntry> findExpiredClaims(Instant now, int limit);
}
