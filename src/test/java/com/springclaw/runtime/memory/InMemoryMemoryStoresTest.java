package com.springclaw.runtime.memory;

import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryIndexOperation;
import com.springclaw.runtime.memory.contract.MemoryIndexOutboxEntry;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.contract.ShortTermMemoryEntry;
import com.springclaw.runtime.memory.store.InMemoryMemoryIndexOutboxStore;
import com.springclaw.runtime.memory.store.InMemoryMemoryRecordStore;
import com.springclaw.runtime.memory.store.InMemoryShortTermMemoryStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryMemoryStoresTest {

    private static final Instant T0 = Instant.parse("2026-06-23T00:00:00Z");
    private static final MemoryScope SCOPE = MemoryScope.from(
            SessionAccessClaim.personal(
                    SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                    "api",
                    "session-1",
                    "alice"
            )
    );

    @Test
    void recordStoreRejectsDuplicateVersionAndSecondActiveVersion() {
        InMemoryMemoryRecordStore store = new InMemoryMemoryRecordStore();
        store.insert(record("logical-1", "version-1", 1, MemoryStatus.ACTIVE, 1));

        assertThat(store.findByVersionId("version-1")).isPresent();
        assertThat(store.findActive("logical-1"))
                .get()
                .extracting(MemoryRecordVersion::memoryVersionId)
                .isEqualTo("version-1");
        assertThatThrownBy(() -> store.insert(
                record("logical-2", "version-1", 1, MemoryStatus.CANDIDATE, null)
        )).hasMessageContaining("memoryVersionId");
        assertThatThrownBy(() -> store.insert(
                record("logical-1", "version-2", 2, MemoryStatus.ACTIVE, 1)
        )).hasMessageContaining("active version");
    }

    @Test
    void concurrentActiveInsertsAcceptExactlyOneVersion() {
        InMemoryMemoryRecordStore store = new InMemoryMemoryRecordStore();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        CompletableFuture<Void> first = insertActive(
                store, start, accepted, rejected, "version-1", 1
        );
        CompletableFuture<Void> second = insertActive(
                store, start, accepted, rejected, "version-2", 2
        );
        start.countDown();
        CompletableFuture.allOf(first, second).join();

        assertThat(accepted).hasValue(1);
        assertThat(rejected).hasValue(1);
        assertThat(store.findActive("logical-1")).isPresent();
    }

    @Test
    void recordStoreCasChecksStatusRevisionAndActiveInvariant() {
        InMemoryMemoryRecordStore store = new InMemoryMemoryRecordStore();
        store.insert(record("logical-1", "version-1", 1, MemoryStatus.ACTIVE, 1));
        store.insert(record("logical-1", "version-2", 2, MemoryStatus.CANDIDATE, null));

        assertThat(store.compareAndSetStatus(
                "version-1", MemoryStatus.ACTIVE, MemoryStatus.SUPERSEDED,
                null, 1L, 2L, T0.plusSeconds(1)
        )).isTrue();
        assertThat(store.compareAndSetStatus(
                "version-1", MemoryStatus.ACTIVE, MemoryStatus.REJECTED,
                null, 2L, 3L, T0.plusSeconds(2)
        )).isFalse();
        assertThat(store.compareAndSetStatus(
                "version-2", MemoryStatus.CANDIDATE, MemoryStatus.ACTIVE,
                1, 2L, 3L, T0.plusSeconds(2)
        )).isTrue();
        assertThat(store.findActive("logical-1"))
                .get()
                .extracting(MemoryRecordVersion::memoryVersionId)
                .isEqualTo("version-2");
    }

    @Test
    void recordStoreFindsOnlyActiveScopeMatchesAndEnforcesLimit() {
        InMemoryMemoryRecordStore store = new InMemoryMemoryRecordStore();
        store.insert(record("logical-1", "version-1", 1, MemoryStatus.ACTIVE, 1));
        store.insert(record(
                "logical-2", "version-2", 1, MemoryStatus.ACTIVE, 1,
                MemoryType.PROCEDURAL
        ));
        store.insert(record("logical-3", "version-3", 1, MemoryStatus.CANDIDATE, null));

        assertThat(store.findActiveByScope(SCOPE, Set.of(MemoryType.SEMANTIC), 10))
                .extracting(MemoryRecordVersion::memoryVersionId)
                .containsExactly("version-1");
        assertThat(store.findActiveByScope(SCOPE, Set.of(), 1)).hasSize(1);
        assertThatThrownBy(() -> store.findActiveByScope(SCOPE, null, 0))
                .hasMessageContaining("limit");
    }

    @Test
    void sharedScopeStorageIdentityIgnoresRequestingUserAuthorizationContext() {
        MemoryScope aliceScope = sharedScope("alice");
        MemoryScope bobScope = sharedScope("bob");
        InMemoryMemoryRecordStore recordStore = new InMemoryMemoryRecordStore();
        InMemoryShortTermMemoryStore shortTermStore =
                new InMemoryShortTermMemoryStore();

        recordStore.insert(record(
                null, "logical-shared", "version-shared", 1,
                MemoryStatus.ACTIVE, 1, MemoryType.SEMANTIC,
                aliceScope, null, false, null, null, null
        ));
        shortTermStore.append(aliceScope, shortTerm(1L, "event-shared"));

        assertThat(recordStore.findActiveByScope(
                bobScope, Set.of(MemoryType.SEMANTIC), 10
        )).extracting(MemoryRecordVersion::memoryVersionId)
                .containsExactly("version-shared");
        assertThat(shortTermStore.readRecent(bobScope, 10))
                .extracting(ShortTermMemoryEntry::eventKey)
                .containsExactly("event-shared");
    }

    @Test
    void recordStoreAssignsMonotonicIdsPreservesExistingIdsAndFiltersDeleted() {
        InMemoryMemoryRecordStore store = new InMemoryMemoryRecordStore();
        store.insert(record(
                50L, "logical-explicit", "version-explicit", 1,
                MemoryStatus.CANDIDATE, null, MemoryType.SEMANTIC,
                SCOPE, "alice", false, null, null, null
        ));
        store.insert(record(
                null, "logical-auto-1", "version-auto-1", 1,
                MemoryStatus.CANDIDATE, null, MemoryType.SEMANTIC,
                SCOPE, "alice", false, null, null, null
        ));
        store.insert(record(
                null, "logical-auto-2", "version-auto-2", 1,
                MemoryStatus.CANDIDATE, null, MemoryType.SEMANTIC,
                SCOPE, "alice", false, null, null, null
        ));
        store.insert(record(
                null, "logical-deleted", "version-deleted", 1,
                MemoryStatus.ACTIVE, 1, MemoryType.SEMANTIC,
                SCOPE, "alice", true, null, null, null
        ));

        assertThat(store.findByVersionId("version-explicit").orElseThrow().recordId())
                .isEqualTo(50L);
        assertThat(store.findByVersionId("version-auto-1").orElseThrow().recordId())
                .isEqualTo(51L);
        assertThat(store.findByVersionId("version-auto-2").orElseThrow().recordId())
                .isEqualTo(52L);
        assertThat(store.findActive("logical-deleted")).isEmpty();
        assertThat(store.findActiveByScope(SCOPE, null, 10))
                .extracting(MemoryRecordVersion::memoryVersionId)
                .doesNotContain("version-deleted");
        assertThatThrownBy(() -> store.insert(record(
                50L, "logical-duplicate-id", "version-duplicate-id", 1,
                MemoryStatus.CANDIDATE, null, MemoryType.SEMANTIC,
                SCOPE, "alice", false, null, null, null
        ))).hasMessageContaining("recordId");
    }

    @Test
    void recordStoreRejectsDuplicateAutomaticSourceIdentityConcurrently() {
        InMemoryMemoryRecordStore store = new InMemoryMemoryRecordStore();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        CompletableFuture<Void> first = insertAutomatic(
                store, start, accepted, rejected,
                "logical-auto-1", "version-auto-1"
        );
        CompletableFuture<Void> second = insertAutomatic(
                store, start, accepted, rejected,
                "logical-auto-2", "version-auto-2"
        );
        start.countDown();
        CompletableFuture.allOf(first, second).join();

        assertThat(accepted).hasValue(1);
        assertThat(rejected).hasValue(1);
    }

    @Test
    void recordStoreUsesFixedStripedLocksForMissesAndRejectedWrites()
            throws ReflectiveOperationException {
        InMemoryMemoryRecordStore store = new InMemoryMemoryRecordStore();
        store.insert(record(
                "logical-seed", "version-seed", 1,
                MemoryStatus.CANDIDATE, null
        ));

        for (int index = 0; index < 1_000; index++) {
            store.findActive("missing-" + index);
            int current = index;
            assertThatThrownBy(() -> store.insert(record(
                    "rejected-" + current,
                    "version-seed",
                    1,
                    MemoryStatus.CANDIDATE,
                    null
            ))).hasMessageContaining("memoryVersionId");
        }

        var locksField = InMemoryMemoryRecordStore.class
                .getDeclaredField("logicalLocks");
        locksField.setAccessible(true);
        Object locks = locksField.get(store);

        assertThat(locks).isInstanceOf(Object[].class);
        assertThat((Object[]) locks)
                .hasSize(128)
                .doesNotContainNull();
    }

    @Test
    void recordStoreThrowsWhenAuthoritativeVersionCapIsExhausted() {
        InMemoryMemoryRecordStore store = new InMemoryMemoryRecordStore();
        for (int index = 1; index <= 5_000; index++) {
            store.insert(record(
                    "logical-" + index,
                    "version-" + index,
                    1,
                    MemoryStatus.CANDIDATE,
                    null
            ));
        }

        assertThatThrownBy(() -> store.insert(record(
                "logical-overflow", "version-overflow", 1,
                MemoryStatus.CANDIDATE, null
        ))).hasMessageContaining("capacity");
    }

    @Test
    void outboxClaimsOnlyLowestOutstandingRevisionAndUsesFreshTokens() {
        InMemoryMemoryIndexOutboxStore store = new InMemoryMemoryIndexOutboxStore();
        store.insert(pending("event-2", "logical-1", 2L, T0));
        store.insert(pending("event-1", "logical-1", 1L, T0.plusSeconds(10)));

        assertThat(store.claimNext("worker-a", T0, T0.plusSeconds(5))).isEmpty();

        MemoryIndexOutboxEntry first = store.claimNext(
                "worker-a", T0.plusSeconds(10), T0.plusSeconds(20)
        ).orElseThrow();
        assertThat(first.eventId()).isEqualTo("event-1");
        assertThat(first.claimToken()).isNotBlank();

        MemoryIndexOutboxEntry reclaimed = store.claimNext(
                "worker-b", T0.plusSeconds(21), T0.plusSeconds(30)
        ).orElseThrow();
        assertThat(reclaimed.eventId()).isEqualTo("event-1");
        assertThat(reclaimed.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(reclaimed.attempts()).isEqualTo(2);
    }

    @Test
    void outboxCompletionAndFailureRequireMatchingClaimToken() {
        InMemoryMemoryIndexOutboxStore store = outboxStoreAt(T0.plusSeconds(1));
        store.insert(pending("event-1", "logical-1", 1L, T0));
        MemoryIndexOutboxEntry claimed = store.claimNext(
                "worker-a", T0, T0.plusSeconds(10)
        ).orElseThrow();

        assertThat(store.complete("event-1", "wrong", T0.plusSeconds(1))).isFalse();
        assertThat(store.fail(
                "event-1", claimed.claimToken(), "temporary", T0.plusSeconds(20)
        )).isTrue();
        assertThat(store.claimNext(
                "worker-b", T0.plusSeconds(19), T0.plusSeconds(30)
        )).isEmpty();

        MemoryIndexOutboxEntry retried = store.claimNext(
                "worker-b", T0.plusSeconds(20), T0.plusSeconds(30)
        ).orElseThrow();
        assertThat(store.complete(
                "event-1", retried.claimToken(), T0.plusSeconds(21)
        )).isTrue();
        assertThat(store.claimNext(
                "worker-c", T0.plusSeconds(31), T0.plusSeconds(40)
        )).isEmpty();
    }

    @Test
    void outboxRejectsDuplicateLogicalRevisionOperation() {
        InMemoryMemoryIndexOutboxStore store =
                new InMemoryMemoryIndexOutboxStore();
        store.insert(pending(
                "event-1", "logical-1", 1L, T0, MemoryIndexOperation.UPSERT
        ));

        assertThatThrownBy(() -> store.insert(pending(
                "event-2", "logical-1", 1L, T0, MemoryIndexOperation.UPSERT
        ))).hasMessageContaining("indexRevision");
        store.insert(pending(
                "event-3", "logical-1", 1L, T0, MemoryIndexOperation.DELETE
        ));
    }

    @Test
    void outboxRejectsCompletionAndFailureAfterClaimLeaseExpires() {
        InMemoryMemoryIndexOutboxStore store = outboxStoreAt(T0.plusSeconds(11));
        store.insert(pending("event-1", "logical-1", 1L, T0));
        MemoryIndexOutboxEntry claimed = store.claimNext(
                "worker-a", T0, T0.plusSeconds(10)
        ).orElseThrow();

        assertThat(store.complete(
                "event-1", claimed.claimToken(), T0.plusSeconds(11)
        )).isFalse();
        assertThat(store.fail(
                "event-1", claimed.claimToken(), "late", T0.plusSeconds(20)
        )).isFalse();
    }

    @Test
    void outboxCompletionUsesStoreClockForLeaseFencing() {
        InMemoryMemoryIndexOutboxStore store = outboxStoreAt(T0.plusSeconds(11));
        store.insert(pending("event-1", "logical-1", 1L, T0));
        MemoryIndexOutboxEntry claimed = store.claimNext(
                "worker-a", T0, T0.plusSeconds(10)
        ).orElseThrow();

        assertThat(store.complete(
                "event-1", claimed.claimToken(), T0.plusSeconds(9)
        )).isFalse();
    }

    @Test
    void outboxLeaseDeadlineIsStrictForCompletionAndFailure() {
        Instant leaseUntil = T0.plusSeconds(10);
        InMemoryMemoryIndexOutboxStore expiredAtDeadline =
                outboxStoreAt(leaseUntil);
        expiredAtDeadline.insert(pending("event-1", "logical-1", 1L, T0));
        MemoryIndexOutboxEntry claimed = expiredAtDeadline.claimNext(
                "worker-a", T0, leaseUntil
        ).orElseThrow();

        assertThat(expiredAtDeadline.complete(
                "event-1", claimed.claimToken(), T0.plusSeconds(9)
        )).isFalse();
        assertThat(expiredAtDeadline.fail(
                "event-1", claimed.claimToken(), "late", T0.plusSeconds(20)
        )).isFalse();

        InMemoryMemoryIndexOutboxStore completedAtDeadline =
                outboxStoreAt(T0.plusSeconds(9));
        completedAtDeadline.insert(pending(
                "event-2", "logical-2", 1L, T0
        ));
        MemoryIndexOutboxEntry secondClaim = completedAtDeadline.claimNext(
                "worker-a", T0, leaseUntil
        ).orElseThrow();

        assertThat(completedAtDeadline.complete(
                "event-2", secondClaim.claimToken(), leaseUntil
        )).isFalse();
    }

    @Test
    void outboxRejectsDuplicateEventsCapsSizeAndBoundsExpiredClaimQuery() {
        InMemoryMemoryIndexOutboxStore store = new InMemoryMemoryIndexOutboxStore();
        store.insert(pending("event-1", "logical-1", 1L, T0));
        assertThatThrownBy(() -> store.insert(
                pending("event-1", "logical-2", 1L, T0)
        )).hasMessageContaining("eventId");

        store.claimNext("worker-a", T0, T0.plusSeconds(1));
        store.insert(pending("event-2", "logical-2", 1L, T0));
        store.claimNext("worker-b", T0, T0.plusSeconds(1));
        assertThat(store.findExpiredClaims(T0.plusSeconds(1), 1)).hasSize(1);
        assertThatThrownBy(() -> store.findExpiredClaims(T0, 0))
                .hasMessageContaining("limit");

        for (int index = 3; index <= 5_000; index++) {
            store.insert(pending(
                    "event-" + index,
                    "logical-" + index,
                    1L,
                    T0
            ));
        }
        assertThatThrownBy(() -> store.insert(
                pending("event-overflow", "logical-overflow", 1L, T0)
        )).hasMessageContaining("capacity");
    }

    @Test
    void shortTermStoreIsIdempotentOrderedAndKeepsOnlyFortyNewestEntries() {
        InMemoryShortTermMemoryStore store = new InMemoryShortTermMemoryStore();
        store.append(SCOPE, shortTerm(2L, "event-2"));
        store.append(SCOPE, shortTerm(1L, "event-1"));
        store.append(SCOPE, shortTerm(2L, "event-2"));
        for (long eventId = 3; eventId <= 42; eventId++) {
            store.append(SCOPE, shortTerm(eventId, "event-" + eventId));
        }

        assertThat(store.readRecent(SCOPE, 100))
                .extracting(ShortTermMemoryEntry::eventId)
                .containsExactlyElementsOf(
                        java.util.stream.LongStream.rangeClosed(3, 42)
                                .boxed()
                                .toList()
                );
        assertThat(store.readRecent(SCOPE, 3))
                .extracting(ShortTermMemoryEntry::eventId)
                .containsExactly(40L, 41L, 42L);
    }

    @Test
    void shortTermRecoveryHonorsWatermarkAndPreservesConcurrentHigherEvents() {
        InMemoryShortTermMemoryStore store = new InMemoryShortTermMemoryStore();
        store.append(SCOPE, shortTerm(50L, "event-50"));

        store.mergeRecovery(SCOPE, 40L, List.of(
                shortTerm(30L, "event-30"),
                shortTerm(60L, "event-60")
        ));

        assertThat(store.readRecent(SCOPE, 10))
                .extracting(ShortTermMemoryEntry::eventId)
                .containsExactly(30L, 50L);
    }

    @Test
    void shortTermStoreThrowsWhenNewScopeCapIsExhausted() {
        InMemoryShortTermMemoryStore store = new InMemoryShortTermMemoryStore();
        for (int index = 1; index <= 5_000; index++) {
            store.append(scope(index), shortTerm(index, "event-" + index));
        }

        assertThatThrownBy(() -> store.append(
                scope(5_001), shortTerm(5_001L, "event-5001")
        )).hasMessageContaining("scope capacity");
    }

    private static CompletableFuture<Void> insertActive(
            InMemoryMemoryRecordStore store,
            CountDownLatch start,
            AtomicInteger accepted,
            AtomicInteger rejected,
            String versionId,
            int version
    ) {
        return CompletableFuture.runAsync(() -> {
            try {
                start.await();
                store.insert(record(
                        "logical-1", versionId, version, MemoryStatus.ACTIVE, 1
                ));
                accepted.incrementAndGet();
            } catch (IllegalStateException expected) {
                rejected.incrementAndGet();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
    }

    private static CompletableFuture<Void> insertAutomatic(
            InMemoryMemoryRecordStore store,
            CountDownLatch start,
            AtomicInteger accepted,
            AtomicInteger rejected,
            String logicalMemoryId,
            String memoryVersionId
    ) {
        return CompletableFuture.runAsync(() -> {
            try {
                start.await();
                store.insert(record(
                        null, logicalMemoryId, memoryVersionId, 1,
                        MemoryStatus.CANDIDATE, null, MemoryType.SEMANTIC,
                        SCOPE, "alice", false,
                        "EXTRACTION", "shared-source", "v1"
                ));
                accepted.incrementAndGet();
            } catch (IllegalStateException expected) {
                rejected.incrementAndGet();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
    }

    private static MemoryRecordVersion record(
            String logicalMemoryId,
            String memoryVersionId,
            int version,
            MemoryStatus status,
            Integer activeSlot
    ) {
        return record(
                null, logicalMemoryId, memoryVersionId, version,
                status, activeSlot, MemoryType.SEMANTIC,
                SCOPE, "alice", false, null, null, null
        );
    }

    private static MemoryRecordVersion record(
            String logicalMemoryId,
            String memoryVersionId,
            int version,
            MemoryStatus status,
            Integer activeSlot,
            MemoryType memoryType
    ) {
        return record(
                null, logicalMemoryId, memoryVersionId, version,
                status, activeSlot, memoryType,
                SCOPE, "alice", false, null, null, null
        );
    }

    private static MemoryRecordVersion record(
            Long recordId,
            String logicalMemoryId,
            String memoryVersionId,
            int version,
            MemoryStatus status,
            Integer activeSlot,
            MemoryType memoryType,
            MemoryScope scope,
            String ownerUserId,
            boolean deleted,
            String sourceKind,
            String sourceIdentity,
            String extractionPolicyVersion
    ) {
        String sourceRunId = sourceKind == null ? null : "run-1";
        return new MemoryRecordVersion(
                recordId,
                logicalMemoryId,
                memoryVersionId,
                memoryType,
                scope.scopeType(),
                scope.scopeId(),
                ownerUserId,
                "content-" + memoryVersionId,
                "hash-" + memoryVersionId,
                "summary",
                sourceRunId,
                List.of(),
                List.of(),
                List.of(),
                0.5,
                0.5,
                status,
                T0,
                null,
                null,
                version,
                activeSlot,
                sourceKind,
                sourceIdentity,
                extractionPolicyVersion,
                version,
                T0,
                T0.plusSeconds(version),
                deleted
        );
    }

    private static MemoryIndexOutboxEntry pending(
            String eventId,
            String logicalMemoryId,
            long revision,
            Instant availableAt
    ) {
        return pending(
                eventId,
                logicalMemoryId,
                revision,
                availableAt,
                MemoryIndexOperation.UPSERT
        );
    }

    private static MemoryIndexOutboxEntry pending(
            String eventId,
            String logicalMemoryId,
            long revision,
            Instant availableAt,
            MemoryIndexOperation operation
    ) {
        return new MemoryIndexOutboxEntry(
                eventId,
                logicalMemoryId,
                "version-" + eventId,
                (int) revision,
                revision,
                operation,
                MemoryIndexOutboxEntry.Status.PENDING,
                0,
                availableAt,
                null,
                null,
                null,
                null,
                null,
                T0,
                T0
        );
    }

    private static InMemoryMemoryIndexOutboxStore outboxStoreAt(Instant now) {
        return new InMemoryMemoryIndexOutboxStore(
                Clock.fixed(now, ZoneOffset.UTC)
        );
    }

    private static ShortTermMemoryEntry shortTerm(long eventId, String eventKey) {
        return new ShortTermMemoryEntry(
                eventId,
                eventKey,
                "request-" + eventId,
                "user",
                "alice",
                "content-" + eventId,
                T0.plusSeconds(eventId)
        );
    }

    private static MemoryScope scope(int index) {
        return MemoryScope.from(SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                "session-" + index,
                "alice"
        ));
    }

    private static MemoryScope sharedScope(String acceptedUserId) {
        return MemoryScope.from(SessionAccessClaim.sharedVerified(
                "feishu",
                "group-1",
                acceptedUserId
        ));
    }
}
