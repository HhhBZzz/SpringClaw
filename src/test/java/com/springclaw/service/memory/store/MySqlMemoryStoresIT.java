package com.springclaw.service.memory.store;

import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryIndexOperation;
import com.springclaw.runtime.memory.contract.MemoryIndexOutboxEntry;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryScopeType;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 3A1 Task 3 集成测试：守住 MySQL schema 不变量与 persistence adapter 语义。
 *
 * 不变量：
 *   - 单 active 版本（uk_memory_single_active）
 *   - 自动来源幂等（uk_memory_source_policy）
 *   - 版本号唯一（uk_memory_logical_version、uk_memory_version_id）
 *   - outbox 事件唯一 + 逻辑/revision/operation 唯一
 *   - claimNext 只取同一 logical memory 的最小未完成 revision，且用过期租约可被新 token 抢占
 *   - complete/fail 必须带匹配 claimToken
 */
@SpringBootTest(properties = {
        "OPENCLAW_PRIMARY_API_KEY=test-key",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none"
})
@Transactional
class MySqlMemoryStoresIT {

    private static final Instant T0 = Instant.parse("2026-06-23T00:00:00Z");
    private static final MemoryScope SCOPE = MemoryScope.from(
            SessionAccessClaim.personal(
                    SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                    "api",
                    "session-1",
                    "alice"
            )
    );

    @Autowired
    private MySqlMemoryRecordStore recordStore;

    @Autowired
    private MySqlMemoryIndexOutboxStore outboxStore;

    @Test
    void onlyOneActiveVersionMayExist() {
        recordStore.insert(active("logical-1", "version-1", 1, 1));
        assertThatThrownBy(() ->
                recordStore.insert(active("logical-1", "version-2", 2, 1))
        ).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void automaticSourceIdentityIsIdempotent() {
        recordStore.insert(autoCandidate("run-1", "version-1"));
        assertThatThrownBy(() ->
                recordStore.insert(autoCandidate("run-1", "version-2"))
        ).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void duplicateLogicalVersionIsRejected() {
        recordStore.insert(candidate("logical-1", "version-1", 1));
        assertThatThrownBy(() ->
                recordStore.insert(candidate("logical-1", "version-2", 1))
        ).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void duplicateMemoryVersionIdIsRejected() {
        recordStore.insert(candidate("logical-1", "version-1", 1));
        assertThatThrownBy(() ->
                recordStore.insert(candidate("logical-2", "version-1", 1))
        ).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void findByVersionIdAndFindActiveRoundTrip() {
        recordStore.insert(active("logical-1", "version-1", 1, 1));

        Optional<MemoryRecordVersion> byVersion = recordStore.findByVersionId("version-1");
        assertThat(byVersion).isPresent();
        assertThat(byVersion.get().memoryVersionId()).isEqualTo("version-1");

        Optional<MemoryRecordVersion> active = recordStore.findActive("logical-1");
        assertThat(active).isPresent();
        assertThat(active.get().memoryVersionId()).isEqualTo("version-1");
    }

    @Test
    void findActiveByScopeFiltersByTypeAndExcludesDeleted() {
        recordStore.insert(active("logical-1", "version-1", 1, 1, MemoryType.SEMANTIC));
        recordStore.insert(active("logical-2", "version-2", 1, 1, MemoryType.EPISODIC));
        recordStore.insert(candidate("logical-3", "version-3", 1));

        List<MemoryRecordVersion> semantic = recordStore.findActiveByScope(
                SCOPE, Set.of(MemoryType.SEMANTIC), 10);
        assertThat(semantic).extracting(MemoryRecordVersion::memoryVersionId)
                .containsExactly("version-1");

        List<MemoryRecordVersion> all = recordStore.findActiveByScope(SCOPE, null, 10);
        assertThat(all).extracting(MemoryRecordVersion::memoryVersionId)
                .containsExactlyInAnyOrder("version-1", "version-2");
    }

    @Test
    void compareAndSetStatusAdvancesActiveSlotAndRevision() {
        recordStore.insert(candidate("logical-1", "version-1", 1));
        MemoryRecordVersion inserted = recordStore.findByVersionId("version-1").orElseThrow();

        boolean changed = recordStore.compareAndSetStatus(
                "version-1",
                MemoryStatus.CANDIDATE,
                MemoryStatus.ACTIVE,
                1,
                inserted.indexRevision(),
                inserted.indexRevision() + 1,
                T0.plusSeconds(10)
        );
        assertThat(changed).isTrue();

        MemoryRecordVersion activated = recordStore.findActive("logical-1").orElseThrow();
        assertThat(activated.status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(activated.activeSlot()).isEqualTo(1);
        assertThat(activated.indexRevision()).isEqualTo(inserted.indexRevision() + 1);
    }

    @Test
    void compareAndSetStatusFailsOnStaleRevision() {
        recordStore.insert(candidate("logical-1", "version-1", 1));
        boolean changed = recordStore.compareAndSetStatus(
                "version-1",
                MemoryStatus.CANDIDATE,
                MemoryStatus.ACTIVE,
                1,
                999L,
                1000L,
                T0
        );
        assertThat(changed).isFalse();
    }

    @Test
    void expiredClaimCanBeReclaimedWithNewToken() {
        outboxStore.insert(pending("event-1", "logical-1", 1L, T0));
        MemoryIndexOutboxEntry first =
                outboxStore.claimNext("worker-a", T0, T0.plusSeconds(30)).orElseThrow();
        MemoryIndexOutboxEntry second =
                outboxStore.claimNext("worker-b", T0.plusSeconds(31), T0.plusSeconds(61))
                        .orElseThrow();

        assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(second.attempts()).isEqualTo(2);
    }

    @Test
    void claimNextSkipsHigherRevisionWhileLowerOutstanding() {
        outboxStore.insert(pending("event-2", "logical-1", 2L, T0));
        outboxStore.insert(pending("event-1", "logical-1", 1L, T0.plusSeconds(10)));

        assertThat(outboxStore.claimNext("worker-a", T0, T0.plusSeconds(5))).isEmpty();

        MemoryIndexOutboxEntry first = outboxStore.claimNext(
                "worker-a", T0.plusSeconds(10), T0.plusSeconds(20)).orElseThrow();
        assertThat(first.eventId()).isEqualTo("event-1");
    }

    @Test
    void outboxRejectsDuplicateEventAndDuplicateLogicalRevisionOperation() {
        outboxStore.insert(pending("event-1", "logical-1", 1L, T0));
        assertThatThrownBy(() ->
                outboxStore.insert(pending("event-1", "logical-2", 1L, T0))
        ).isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() ->
                outboxStore.insert(pending("event-2", "logical-1", 1L, T0))
        ).isInstanceOf(DuplicateKeyException.class);
        // different operation on same logical/revision is allowed
        outboxStore.insert(pending("event-3", "logical-1", 1L, T0,
                MemoryIndexOperation.DELETE));
    }

    @Test
    void completeAndFailRequireMatchingClaimToken() {
        outboxStore.insert(pending("event-1", "logical-1", 1L, T0));
        MemoryIndexOutboxEntry claimed =
                outboxStore.claimNext("worker-a", T0, T0.plusSeconds(30)).orElseThrow();

        assertThat(outboxStore.complete("event-1", "wrong", T0.plusSeconds(1))).isFalse();
        assertThat(outboxStore.fail(
                "event-1", claimed.claimToken(), "temporary", T0.plusSeconds(40))).isTrue();

        // failed entry becomes reclaimable after retryAt
        MemoryIndexOutboxEntry retried = outboxStore.claimNext(
                "worker-b", T0.plusSeconds(40), T0.plusSeconds(60)).orElseThrow();
        assertThat(outboxStore.complete(
                "event-1", retried.claimToken(), T0.plusSeconds(41))).isTrue();
    }

    @Test
    void findExpiredClaimsReturnsOnlyExpiredLeases() {
        outboxStore.insert(pending("event-1", "logical-1", 1L, T0));
        outboxStore.insert(pending("event-2", "logical-2", 1L, T0));
        outboxStore.claimNext("worker-a", T0, T0.plusSeconds(10)).orElseThrow();
        outboxStore.claimNext("worker-b", T0, T0.plusSeconds(100)).orElseThrow();

        List<MemoryIndexOutboxEntry> expired = outboxStore.findExpiredClaims(
                T0.plusSeconds(11), 10);
        assertThat(expired).extracting(MemoryIndexOutboxEntry::eventId)
                .containsExactly("event-1");
    }

    // ---- helpers ----

    private static MemoryRecordVersion candidate(
            String logicalMemoryId, String memoryVersionId, int version) {
        return record(null, logicalMemoryId, memoryVersionId, version,
                MemoryStatus.CANDIDATE, null, MemoryType.SEMANTIC,
                SCOPE, "alice", false, null, null, null);
    }

    private static MemoryRecordVersion active(
            String logicalMemoryId, String memoryVersionId, int version, int activeSlot) {
        return active(logicalMemoryId, memoryVersionId, version, activeSlot,
                MemoryType.SEMANTIC);
    }

    private static MemoryRecordVersion active(
            String logicalMemoryId, String memoryVersionId, int version,
            int activeSlot, MemoryType type) {
        return record(null, logicalMemoryId, memoryVersionId, version,
                MemoryStatus.ACTIVE, activeSlot, type,
                SCOPE, "alice", false, null, null, null);
    }

    private static MemoryRecordVersion autoCandidate(
            String sourceRunId, String memoryVersionId) {
        return record(null, "logical-" + sourceRunId, memoryVersionId, 1,
                MemoryStatus.CANDIDATE, null, MemoryType.SEMANTIC,
                SCOPE, "alice", false,
                "EXTRACTION", sourceRunId, "v1");
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
            String extractionPolicyVersion) {
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
            String eventId, String logicalMemoryId, long revision, Instant availableAt) {
        return pending(eventId, logicalMemoryId, revision, availableAt,
                MemoryIndexOperation.UPSERT);
    }

    private static MemoryIndexOutboxEntry pending(
            String eventId, String logicalMemoryId, long revision, Instant availableAt,
            MemoryIndexOperation operation) {
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
}
