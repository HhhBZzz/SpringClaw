package com.springclaw.runtime.memory;

import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryIndexOperation;
import com.springclaw.runtime.memory.contract.MemoryIndexOutboxEntry;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryScopeType;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.contract.ProjectMemoryItem;
import com.springclaw.runtime.memory.contract.ShortTermMemoryEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryContractTest {

    private static final Instant T0 = Instant.parse("2026-06-23T00:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);

    @Test
    void derivesPersonalAndSharedScopesOnlyFromAcceptedClaims() {
        MemoryScope personal = MemoryScope.from(SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                " api ",
                " session-1 ",
                " alice "
        ));
        MemoryScope shared = MemoryScope.from(SessionAccessClaim.sharedVerified(
                "feishu",
                "group-1",
                "alice"
        ));

        assertThat(personal.scopeType()).isEqualTo(MemoryScopeType.PERSONAL_SESSION);
        assertThat(personal.scopeId()).isEqualTo("api:session-1:alice");
        assertThat(personal.requestingUserId()).isEqualTo("alice");
        assertThat(personal.authorizationPrincipal()).isEqualTo("alice");
        assertThat(personal.crossSessionUserMemoryAllowed()).isTrue();

        assertThat(shared.scopeType()).isEqualTo(MemoryScopeType.SHARED_SESSION);
        assertThat(shared.scopeId()).isEqualTo("feishu:group-1");
        assertThat(shared.requestingUserId()).isEqualTo("alice");
        assertThat(shared.authorizationPrincipal())
                .isEqualTo("shared:feishu:group-1");
        assertThat(shared.crossSessionUserMemoryAllowed()).isFalse();
    }

    @Test
    void memoryRecordCopiesListsAndEnforcesLifecycleFields() {
        List<String> eventIds = new ArrayList<>(List.of("event-1"));
        MemoryRecordVersion record = automaticRecord(
                MemoryScope.from(personalClaim()),
                MemoryStatus.ACTIVE,
                1,
                eventIds
        );

        eventIds.add("event-2");

        assertThat(record.sourceEventIds()).containsExactly("event-1");
        assertThatThrownBy(() -> record.tags().add("new"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> record(
                MemoryScope.from(personalClaim()),
                "alice",
                MemoryStatus.ACTIVE,
                null,
                1,
                1L,
                null,
                List.of(),
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activeSlot");
        assertThatThrownBy(() -> record(
                MemoryScope.from(personalClaim()),
                "alice",
                MemoryStatus.CANDIDATE,
                1,
                1,
                1L,
                null,
                List.of(),
                null,
                null,
                null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("activeSlot");
    }

    @Test
    void memoryRecordRejectsInvalidVersionScoresAndValidityWindow() {
        MemoryScope scope = MemoryScope.from(personalClaim());

        assertThatThrownBy(() -> record(
                scope, "alice", MemoryStatus.CANDIDATE, null,
                0, 1L, null, List.of(), null, null, null
        )).hasMessageContaining("version");
        assertThatThrownBy(() -> new MemoryRecordVersion(
                null, "logical-1", "version-1", MemoryType.SEMANTIC,
                scope.scopeType(), scope.scopeId(), "alice",
                "content", "hash", "summary", null, List.of(), List.of(), List.of(),
                1.1, 0.5, MemoryStatus.CANDIDATE, T0, null, null, 1, null,
                null, null, null, 1L, T0, T0, false
        )).hasMessageContaining("importance");
        assertThatThrownBy(() -> record(
                scope, "alice", MemoryStatus.CANDIDATE, null,
                1, 0L, null, List.of(), null, null, null
        )).hasMessageContaining("indexRevision");
        assertThatThrownBy(() -> new MemoryRecordVersion(
                null, "logical-1", "version-1", MemoryType.SEMANTIC,
                scope.scopeType(), scope.scopeId(), "alice",
                "content", "hash", "summary", null, List.of(), List.of(), List.of(),
                0.5, 0.5, MemoryStatus.CANDIDATE, T1, T0, null, 1, null,
                null, null, null, 1L, T0, T0, false
        )).hasMessageContaining("validUntil");
    }

    @Test
    void memoryRecordUsesPersistentScopeIdentityAndValidatesOptionalRecordId() {
        MemoryScope scope = MemoryScope.from(personalClaim());
        MemoryRecordVersion record = record(
                scope, "alice", MemoryStatus.CANDIDATE, null,
                1, 1L, null, List.of(), null, null, null
        );

        assertThat(record.recordId()).isNull();
        assertThat(record.scopeType()).isEqualTo(MemoryScopeType.PERSONAL_SESSION);
        assertThat(record.scopeId()).isEqualTo("api:session-1:alice");
        assertThat(record.deleted()).isFalse();
        assertThatThrownBy(() -> new MemoryRecordVersion(
                0L, "logical-1", "version-1", MemoryType.SEMANTIC,
                scope.scopeType(), scope.scopeId(), "alice",
                "content", "hash", "summary", null, List.of(), List.of(), List.of(),
                0.5, 0.5, MemoryStatus.CANDIDATE, T0, null, null, 1, null,
                null, null, null, 1L, T0, T0, false
        )).hasMessageContaining("recordId");
    }

    @Test
    void automaticMemoryRequiresCompleteProvenanceAndRawSourceReference() {
        MemoryScope scope = MemoryScope.from(personalClaim());

        assertThatThrownBy(() -> record(
                scope, "alice", MemoryStatus.CANDIDATE, null,
                1, 1L, "run-1", List.of(), null, null, null
        )).hasMessageContaining("sourceKind");
        assertThatThrownBy(() -> record(
                scope, "alice", MemoryStatus.CANDIDATE, null,
                1, 1L, null, List.of(), "EXTRACTION", "source-1", "v1"
        )).hasMessageContaining("sourceRunId");
        assertThatThrownBy(() -> record(
                scope, "alice", MemoryStatus.CANDIDATE, null,
                1, 1L, "run-1", List.of(), "EXTRACTION", null, "v1"
        )).hasMessageContaining("sourceIdentity");
    }

    @Test
    void personalMemoryOwnerMustMatchRequestingUserWhileSharedOwnerMayBeNull() {
        MemoryScope personal = MemoryScope.from(personalClaim());
        MemoryScope shared = MemoryScope.from(SessionAccessClaim.sharedVerified(
                "feishu", "group-1", "alice"
        ));

        assertThatThrownBy(() -> record(
                personal, "bob", MemoryStatus.CANDIDATE, null,
                1, 1L, null, List.of(), null, null, null
        )).hasMessageContaining("ownerUserId");
        assertThat(record(
                shared, null, MemoryStatus.CANDIDATE, null,
                1, 1L, null, List.of(), null, null, null
        ).ownerUserId()).isNull();
    }

    @Test
    void outboxClaimCompleteAndFailProduceValidatedCopies() {
        MemoryIndexOutboxEntry pending = pending("event-1", "logical-1", 1L);
        MemoryIndexOutboxEntry claimed = pending.claim(
                "worker-a", T0, T1, "token-1"
        );

        assertThat(claimed.status())
                .isEqualTo(MemoryIndexOutboxEntry.Status.CLAIMED);
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(claimed.claimOwner()).isEqualTo("worker-a");
        assertThat(claimed.claimToken()).isEqualTo("token-1");
        assertThat(claimed.complete(T1).status())
                .isEqualTo(MemoryIndexOutboxEntry.Status.SUCCEEDED);
        assertThat(claimed.fail("temporary", T1).status())
                .isEqualTo(MemoryIndexOutboxEntry.Status.FAILED);
        assertThat(claimed.fail("temporary", T1).lastError())
                .isEqualTo("temporary");
    }

    @Test
    void outboxRejectsInvalidStatusFieldCombinations() {
        assertThatThrownBy(() -> new MemoryIndexOutboxEntry(
                "event-1", "logical-1", "version-1", 1, 1L,
                MemoryIndexOperation.UPSERT,
                MemoryIndexOutboxEntry.Status.CLAIMED,
                1, T0, null, "worker", "token", T1, null, T0, T0
        )).hasMessageContaining("claimedAt");
        assertThatThrownBy(() -> pending("event-1", "logical-1", 1L)
                .claim("worker", T1, T0, "token"))
                .hasMessageContaining("leaseUntil");
    }

    @Test
    void shortTermAndProjectContractsNormalizeAndValidateTheirTypedFields() {
        ShortTermMemoryEntry entry = new ShortTermMemoryEntry(
                1L, " event-1 ", " request-1 ", " user ",
                " alice ", " hello ", T0
        );
        ProjectMemoryItem project = new ProjectMemoryItem(
                " docs/brief.md ",
                ProjectMemoryItem.SourceType.PROJECT_BRIEF,
                " project brief ",
                " hash ",
                ProjectMemoryItem.ReviewStatus.APPROVED,
                T0
        );

        assertThat(entry.eventKey()).isEqualTo("event-1");
        assertThat(entry.content()).isEqualTo("hello");
        assertThat(project.sourcePath()).isEqualTo("docs/brief.md");
        assertThat(project.reviewStatus())
                .isEqualTo(ProjectMemoryItem.ReviewStatus.APPROVED);
        assertThatThrownBy(() -> new ShortTermMemoryEntry(
                0L, "event", "request", "user", "alice", "hello", T0
        )).hasMessageContaining("eventId");
    }

    @Test
    void shortTermContentAllowsFourThousandNormalizedCharactersOnly() {
        ShortTermMemoryEntry maximum = new ShortTermMemoryEntry(
                1L, "event-1", "request-1", "user", "alice",
                " " + "x".repeat(4_000) + " ", T0
        );

        assertThat(maximum.content()).hasSize(4_000);
        assertThatThrownBy(() -> new ShortTermMemoryEntry(
                2L, "event-2", "request-2", "user", "alice",
                "x".repeat(4_001), T0
        )).hasMessageContaining("4000");
    }

    private static SessionAccessClaim personalClaim() {
        return SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                "session-1",
                "alice"
        );
    }

    private static MemoryRecordVersion automaticRecord(
            MemoryScope scope,
            MemoryStatus status,
            Integer activeSlot,
            List<String> eventIds
    ) {
        return record(
                scope, "alice", status, activeSlot, 1, 1L,
                "run-1", eventIds, "EXTRACTION", "source-1", "v1"
        );
    }

    private static MemoryRecordVersion record(
            MemoryScope scope,
            String ownerUserId,
            MemoryStatus status,
            Integer activeSlot,
            int version,
            long indexRevision,
            String sourceRunId,
            List<String> sourceEventIds,
            String sourceKind,
            String sourceIdentity,
            String extractionPolicyVersion
    ) {
        return new MemoryRecordVersion(
                null,
                "logical-1",
                "version-" + version,
                MemoryType.SEMANTIC,
                scope.scopeType(),
                scope.scopeId(),
                ownerUserId,
                "content",
                "hash",
                "summary",
                sourceRunId,
                sourceEventIds,
                List.of("evidence"),
                List.of("tag"),
                0.7,
                0.8,
                status,
                T0,
                null,
                null,
                version,
                activeSlot,
                sourceKind,
                sourceIdentity,
                extractionPolicyVersion,
                indexRevision,
                T0,
                T0,
                false
        );
    }

    private static MemoryIndexOutboxEntry pending(
            String eventId,
            String logicalMemoryId,
            long indexRevision
    ) {
        return new MemoryIndexOutboxEntry(
                eventId,
                logicalMemoryId,
                "version-" + indexRevision,
                (int) indexRevision,
                indexRevision,
                MemoryIndexOperation.UPSERT,
                MemoryIndexOutboxEntry.Status.PENDING,
                0,
                T0,
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
