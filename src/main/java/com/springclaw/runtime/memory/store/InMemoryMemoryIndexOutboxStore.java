package com.springclaw.runtime.memory.store;

import com.springclaw.runtime.memory.contract.MemoryIndexOutboxEntry;
import com.springclaw.runtime.memory.contract.MemoryIndexOperation;
import com.springclaw.runtime.memory.port.MemoryIndexOutboxStore;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class InMemoryMemoryIndexOutboxStore
        implements MemoryIndexOutboxStore {

    private static final int MAX_ENTRIES = 5_000;

    private final Map<String, MemoryIndexOutboxEntry> entries = new HashMap<>();
    private final Set<RevisionOperationKey> revisionOperationKeys =
            new HashSet<>();
    private final Object lock = new Object();
    private final Clock clock;

    public InMemoryMemoryIndexOutboxStore() {
        this(Clock.systemUTC());
    }

    public InMemoryMemoryIndexOutboxStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void insert(MemoryIndexOutboxEntry entry) {
        Objects.requireNonNull(entry, "entry");
        synchronized (lock) {
            if (entries.containsKey(entry.eventId())) {
                throw new IllegalStateException(
                        "duplicate outbox eventId: " + entry.eventId()
                );
            }
            RevisionOperationKey revisionOperationKey =
                    RevisionOperationKey.from(entry);
            if (revisionOperationKeys.contains(revisionOperationKey)) {
                throw new IllegalStateException(
                        "duplicate logicalMemoryId/indexRevision/operation"
                );
            }
            if (entries.size() >= MAX_ENTRIES) {
                throw new IllegalStateException("outbox capacity exhausted");
            }
            entries.put(entry.eventId(), entry);
            revisionOperationKeys.add(revisionOperationKey);
        }
    }

    public List<MemoryIndexOutboxEntry> findAll() {
        synchronized (lock) {
            return entries.values().stream()
                    .sorted(Comparator
                            .comparing(MemoryIndexOutboxEntry::logicalMemoryId)
                            .thenComparingLong(
                                    MemoryIndexOutboxEntry::indexRevision
                            )
                            .thenComparing(MemoryIndexOutboxEntry::operation)
                            .thenComparing(MemoryIndexOutboxEntry::eventId))
                    .toList();
        }
    }

    public Snapshot snapshot(String logicalMemoryId) {
        logicalMemoryId = requireText(logicalMemoryId, "logicalMemoryId");
        synchronized (lock) {
            String target = logicalMemoryId;
            return new Snapshot(
                    target,
                    entries.values().stream()
                            .filter(entry -> entry.logicalMemoryId().equals(target))
                            .toList()
            );
        }
    }

    public void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        synchronized (lock) {
            List<MemoryIndexOutboxEntry> current = entries.values().stream()
                    .filter(entry -> entry.logicalMemoryId().equals(
                            snapshot.logicalMemoryId
                    ))
                    .toList();
            for (MemoryIndexOutboxEntry entry : current) {
                entries.remove(entry.eventId());
                revisionOperationKeys.remove(RevisionOperationKey.from(entry));
            }
            for (MemoryIndexOutboxEntry entry : snapshot.entries) {
                entries.put(entry.eventId(), entry);
                revisionOperationKeys.add(RevisionOperationKey.from(entry));
            }
        }
    }

    public <T> T executeExclusively(Supplier<T> work) {
        Objects.requireNonNull(work, "work");
        synchronized (lock) {
            return work.get();
        }
    }

    @Override
    public Optional<MemoryIndexOutboxEntry> claimNext(
            String owner,
            Instant now,
            Instant leaseUntil
    ) {
        owner = requireText(owner, "owner");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (!leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("leaseUntil must be after now");
        }
        synchronized (lock) {
            Map<String, Long> lowestOutstanding = lowestOutstandingRevisions();
            Optional<MemoryIndexOutboxEntry> candidate = entries.values().stream()
                    .filter(entry -> isEligible(entry, now))
                    .filter(entry -> entry.indexRevision()
                            == lowestOutstanding.get(entry.logicalMemoryId()))
                    .sorted(Comparator
                            .comparing(MemoryIndexOutboxEntry::availableAt)
                            .thenComparing(MemoryIndexOutboxEntry::createdAt)
                            .thenComparing(MemoryIndexOutboxEntry::eventId))
                    .findFirst();
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            MemoryIndexOutboxEntry claimed = candidate.get().claim(
                    owner,
                    now,
                    leaseUntil,
                    UUID.randomUUID().toString()
            );
            entries.put(claimed.eventId(), claimed);
            return Optional.of(claimed);
        }
    }

    @Override
    public boolean complete(
            String eventId,
            String claimToken,
            Instant completedAt
    ) {
        eventId = requireText(eventId, "eventId");
        claimToken = requireText(claimToken, "claimToken");
        Objects.requireNonNull(completedAt, "completedAt");
        synchronized (lock) {
            MemoryIndexOutboxEntry current = entries.get(eventId);
            if (!matchingClaim(current, claimToken)
                    || !clock.instant().isBefore(current.leaseUntil())
                    || !completedAt.isBefore(current.leaseUntil())) {
                return false;
            }
            entries.put(eventId, current.complete(completedAt));
            return true;
        }
    }

    @Override
    public boolean fail(
            String eventId,
            String claimToken,
            String error,
            Instant retryAt
    ) {
        eventId = requireText(eventId, "eventId");
        claimToken = requireText(claimToken, "claimToken");
        error = requireText(error, "error");
        Objects.requireNonNull(retryAt, "retryAt");
        synchronized (lock) {
            MemoryIndexOutboxEntry current = entries.get(eventId);
            if (!matchingClaim(current, claimToken)
                    || !clock.instant().isBefore(current.leaseUntil())) {
                return false;
            }
            entries.put(eventId, current.fail(error, retryAt));
            return true;
        }
    }

    @Override
    public List<MemoryIndexOutboxEntry> findExpiredClaims(
            Instant now,
            int limit
    ) {
        Objects.requireNonNull(now, "now");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        synchronized (lock) {
            return entries.values().stream()
                    .filter(entry -> entry.status()
                            == MemoryIndexOutboxEntry.Status.CLAIMED)
                    .filter(entry -> !entry.leaseUntil().isAfter(now))
                    .sorted(Comparator
                            .comparing(MemoryIndexOutboxEntry::leaseUntil)
                            .thenComparing(MemoryIndexOutboxEntry::eventId))
                    .limit(limit)
                    .toList();
        }
    }

    @Override
    public List<MemoryIndexOutboxEntry> findPendingAfterRevision(
            long exclusiveRevision,
            String afterEventId,
            int limit
    ) {
        if (exclusiveRevision < 0) {
            throw new IllegalArgumentException("exclusiveRevision must not be negative");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        synchronized (lock) {
            return entries.values().stream()
                    .filter(entry -> entry.status() != MemoryIndexOutboxEntry.Status.SUCCEEDED)
                    .filter(entry -> isAfterCursor(entry, exclusiveRevision, afterEventId))
                    .sorted(Comparator
                            .comparingLong(MemoryIndexOutboxEntry::indexRevision)
                            .thenComparing(MemoryIndexOutboxEntry::eventId))
                    .limit(limit)
                    .toList();
        }
    }

    private static boolean isAfterCursor(
            MemoryIndexOutboxEntry entry,
            long revision,
            String afterEventId
    ) {
        if (entry.indexRevision() > revision) {
            return true;
        }
        return entry.indexRevision() == revision
                && afterEventId != null
                && entry.eventId().compareTo(afterEventId) > 0;
    }

    private Map<String, Long> lowestOutstandingRevisions() {
        Map<String, Long> revisions = new HashMap<>();
        for (MemoryIndexOutboxEntry entry : entries.values()) {
            if (entry.status() != MemoryIndexOutboxEntry.Status.SUCCEEDED) {
                revisions.merge(
                        entry.logicalMemoryId(),
                        entry.indexRevision(),
                        Math::min
                );
            }
        }
        return revisions;
    }

    private static boolean isEligible(
            MemoryIndexOutboxEntry entry,
            Instant now
    ) {
        if (entry.status() == MemoryIndexOutboxEntry.Status.PENDING
                || entry.status() == MemoryIndexOutboxEntry.Status.FAILED) {
            return !entry.availableAt().isAfter(now);
        }
        return entry.status() == MemoryIndexOutboxEntry.Status.CLAIMED
                && !entry.leaseUntil().isAfter(now);
    }

    private static boolean matchingClaim(
            MemoryIndexOutboxEntry entry,
            String claimToken
    ) {
        return entry != null
                && entry.status() == MemoryIndexOutboxEntry.Status.CLAIMED
                && claimToken.equals(entry.claimToken());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private record RevisionOperationKey(
            String logicalMemoryId,
            long indexRevision,
            MemoryIndexOperation operation
    ) {
        private static RevisionOperationKey from(
                MemoryIndexOutboxEntry entry
        ) {
            return new RevisionOperationKey(
                    entry.logicalMemoryId(),
                    entry.indexRevision(),
                    entry.operation()
            );
        }
    }

    public static final class Snapshot {
        private final String logicalMemoryId;
        private final List<MemoryIndexOutboxEntry> entries;

        private Snapshot(
                String logicalMemoryId,
                List<MemoryIndexOutboxEntry> entries
        ) {
            this.logicalMemoryId = logicalMemoryId;
            this.entries = entries;
        }
    }
}
