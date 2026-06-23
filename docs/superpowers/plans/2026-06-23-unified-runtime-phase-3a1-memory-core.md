# Unified Runtime Phase 3A1 Memory Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish a typed, authorized, durable memory core with MySQL authority, Redis short-term projection, fenced vector indexing, and reviewed Markdown sources without changing the active runtime context owner.

**Architecture:** Phase 3A1 adds immutable acceptance access claims, memory lifecycle contracts, durable versioned records, an index outbox, deterministic chat-event identities, short-term Redis shadow storage, and derived vector/Markdown adapters. Existing `ContextAssembler`, Advisors, routing, engines, answers, locks, and stream termination remain active; canonical retrieval and `ContextSnapshotFactory` activation are deferred to Phase 3A2/3A3.

**Tech Stack:** Java 17, Spring Boot 3.5, MyBatis-Plus, MySQL 8, Spring Data Redis, Spring AI `VectorStore`, JUnit 5, AssertJ, Mockito.

---

## 1. Scope, ownership, and stop rules

### Included

- immutable `SessionAccessClaim` on accepted canonical runs;
- typed memory/version/outbox/short-term/project-source contracts;
- MySQL `memory_record`, `memory_index_outbox`, and `message_event.event_key`;
- database-disabled bounded in-memory stores;
- deterministic memory lifecycle and outbox transitions;
- explicit `TERMINAL_RESULT` versus `CONFIRMATION_SUSPENSION`;
- Redis ordered short-term shadow store and MySQL recovery;
- fenced vector projection and generation rebuild primitives;
- typed Markdown project-memory reads and candidate learning default;
- focused tests and compatibility evidence.

### Excluded

- `MemoryCoordinator` ranking or `MemoryFrame` assembly;
- `ContextSnapshot` shape changes;
- `ContextSnapshotFactory`;
- Advisor projection replacement;
- disabling `LegacyRunContextAdapter`;
- engine, route, answer, persistence-owner, lock, or SSE redesign;
- LLM fact extraction, clustering, graph storage, or learned memory control.

### File ownership

Codex owns:

- `src/main/java/com/springclaw/runtime/contract/**`;
- `src/main/java/com/springclaw/runtime/lifecycle/**`;
- `src/main/java/com/springclaw/runtime/memory/contract/**`;
- `src/main/java/com/springclaw/runtime/memory/port/**`;
- lifecycle and contract tests;
- schema invariants and final acceptance.

Claude may own after the corresponding core task is committed:

- persistence entities, mappers, repository adapters;
- Redis and VectorStore adapters;
- Markdown typed reader;
- constructor migration and focused adapter tests.

Workers must not modify Phase 3A2/3A3 owners or perform unrelated refactoring.

---

### Task 1: Freeze trusted session access at acceptance

**Owner:** Codex

**Files:**

- Create: `src/main/java/com/springclaw/runtime/contract/SessionAccessClaim.java`
- Modify: `src/main/java/com/springclaw/runtime/lifecycle/RunAcceptance.java`
- Modify: `src/main/java/com/springclaw/runtime/contract/RunState.java`
- Modify: `src/main/java/com/springclaw/runtime/contract/RunTransitionPolicy.java`
- Modify: `src/main/java/com/springclaw/runtime/lifecycle/RunCoordinator.java`
- Modify: `src/main/java/com/springclaw/controller/ChatController.java`
- Modify: `src/main/java/com/springclaw/service/webhook/WebhookRouterService.java`
- Modify: `src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java`
- Modify: `src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java`
- Test: `src/test/java/com/springclaw/runtime/contract/SessionAccessClaimTest.java`
- Test: `src/test/java/com/springclaw/runtime/contract/RunStateContractTest.java`
- Test: `src/test/java/com/springclaw/runtime/lifecycle/RunCoordinatorTest.java`
- Test: `src/test/java/com/springclaw/controller/ChatControllerAuthTest.java`
- Test: `src/test/java/com/springclaw/service/webhook/WebhookRouterServiceTest.java`
- Test: `src/test/java/com/springclaw/service/task/TaskExecutionServiceTest.java`
- Test: `src/test/java/com/springclaw/service/chat/async/ChatMessageConsumerTest.java`

- [x] **Step 1: Write failing access-claim contract tests**

Add tests equivalent to:

```java
@Test
void sharedClaimRequiresVerifiedWebhookOrigin() {
    assertThatThrownBy(() -> new SessionAccessClaim(
            SessionAccessClaim.ClaimType.SHARED,
            SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
            "feishu",
            "feishu:group:g1",
            "shared:feishu:feishu:group:g1",
            "alice"
    )).isInstanceOf(IllegalArgumentException.class)
      .hasMessageContaining("VERIFIED_WEBHOOK");
}

@Test
void personalClaimUsesAcceptedUserAsPrincipal() {
    SessionAccessClaim claim = SessionAccessClaim.personal(
            SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
            "feishu",
            "feishu:group:g1",
            "alice"
    );

    assertThat(claim.claimType())
            .isEqualTo(SessionAccessClaim.ClaimType.PERSONAL);
    assertThat(claim.ownerOrSharedPrincipal()).isEqualTo("alice");
}
```

Extend `RunStateContractTest` to prove the claim cannot change after acceptance.

- [x] **Step 2: Run the contract tests and verify RED**

Run:

```bash
mvn -q -Dtest=SessionAccessClaimTest,RunStateContractTest,RunCoordinatorTest test
```

Expected: compilation fails because `SessionAccessClaim` and the new acceptance
field do not exist.

- [x] **Step 3: Add the immutable claim**

Implement:

```java
package com.springclaw.runtime.contract;

import java.util.Objects;

public record SessionAccessClaim(
        ClaimType claimType,
        AcceptanceOrigin acceptanceOrigin,
        String channel,
        String sessionKey,
        String ownerOrSharedPrincipal,
        String acceptedUserId
) {
    public enum ClaimType { PERSONAL, SHARED }

    public enum AcceptanceOrigin {
        AUTHENTICATED_API,
        VERIFIED_WEBHOOK,
        SCHEDULED_TASK
    }

    public SessionAccessClaim {
        claimType = Objects.requireNonNull(claimType, "claimType");
        acceptanceOrigin = Objects.requireNonNull(
                acceptanceOrigin, "acceptanceOrigin"
        );
        channel = requireText(channel, "channel");
        sessionKey = requireText(sessionKey, "sessionKey");
        ownerOrSharedPrincipal = requireText(
                ownerOrSharedPrincipal, "ownerOrSharedPrincipal"
        );
        acceptedUserId = requireText(acceptedUserId, "acceptedUserId");
        if (claimType == ClaimType.SHARED
                && acceptanceOrigin != AcceptanceOrigin.VERIFIED_WEBHOOK) {
            throw new IllegalArgumentException(
                    "SHARED claim requires VERIFIED_WEBHOOK origin"
            );
        }
        if (claimType == ClaimType.PERSONAL
                && !ownerOrSharedPrincipal.equals(acceptedUserId)) {
            throw new IllegalArgumentException(
                    "PERSONAL principal must equal acceptedUserId"
            );
        }
    }

    public static SessionAccessClaim personal(
            AcceptanceOrigin origin,
            String channel,
            String sessionKey,
            String userId
    ) {
        return new SessionAccessClaim(
                ClaimType.PERSONAL, origin, channel, sessionKey, userId, userId
        );
    }

    public static SessionAccessClaim sharedVerified(
            String channel,
            String sessionKey,
            String acceptedUserId
    ) {
        return new SessionAccessClaim(
                ClaimType.SHARED,
                AcceptanceOrigin.VERIFIED_WEBHOOK,
                channel,
                sessionKey,
                "shared:" + channel + ":" + sessionKey,
                acceptedUserId
        );
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
```

Add `SessionAccessClaim sessionAccessClaim` to `RunAcceptance` and `RunState`.
Validate that its channel, session key, and accepted user match the acceptance
fields. Preserve it in every `RunCoordinator.copy(...)` call and require equality
in `RunTransitionPolicy.validate(...)`.

- [x] **Step 4: Mint claims only at trusted ingress**

Use these exact policies:

```java
// ChatController: always personal, regardless of request channel/session strings
SessionAccessClaim.personal(
        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
        normalizedChannel(request.channel()),
        request.sessionKey(),
        request.userId()
)
```

```java
// WebhookRouterService: shared only after verified adapter output identifies
// a Feishu group session; every other webhook claim remains personal.
ConversationScopeSupport.isFeishuGroupSession(inboundMessage.sessionKey())
        ? SessionAccessClaim.sharedVerified(
                inboundMessage.channel(),
                inboundMessage.sessionKey(),
                inboundMessage.userId()
        )
        : SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.VERIFIED_WEBHOOK,
                inboundMessage.channel(),
                inboundMessage.sessionKey(),
                inboundMessage.userId()
        )
```

Scheduled tasks use `SCHEDULED_TASK` plus a personal claim. Rabbit does not mint a
claim; `ChatMessageConsumer.requireMatchingAcceptance(...)` verifies the existing
run claim is `PERSONAL` and matches the message.

- [x] **Step 5: Run focused ingress and lifecycle tests**

Run:

```bash
mvn -q -Dtest=SessionAccessClaimTest,RunStateContractTest,RunCoordinatorTest,ChatControllerAuthTest,WebhookRouterServiceTest,TaskExecutionServiceTest,ChatMessageConsumerTest test
```

Expected: all pass; a REST request using
`channel=feishu, sessionKey=feishu:group:*` still creates a `PERSONAL` claim.

- [x] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/runtime \
  src/main/java/com/springclaw/controller/ChatController.java \
  src/main/java/com/springclaw/service/webhook/WebhookRouterService.java \
  src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java \
  src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java \
  src/test/java/com/springclaw/runtime \
  src/test/java/com/springclaw/controller/ChatControllerAuthTest.java \
  src/test/java/com/springclaw/service/webhook/WebhookRouterServiceTest.java \
  src/test/java/com/springclaw/service/task/TaskExecutionServiceTest.java \
  src/test/java/com/springclaw/service/chat/async/ChatMessageConsumerTest.java
git commit -m "feat: freeze trusted memory access at run acceptance"
```

---

### Task 2: Add typed memory contracts and bounded fallback stores

**Owner:** Codex

**Files:**

- Create: `src/main/java/com/springclaw/runtime/memory/contract/MemoryType.java`
- Create: `src/main/java/com/springclaw/runtime/memory/contract/MemoryScopeType.java`
- Create: `src/main/java/com/springclaw/runtime/memory/contract/MemoryStatus.java`
- Create: `src/main/java/com/springclaw/runtime/memory/contract/MemoryScope.java`
- Create: `src/main/java/com/springclaw/runtime/memory/contract/MemoryRecordVersion.java`
- Create: `src/main/java/com/springclaw/runtime/memory/contract/MemoryIndexOperation.java`
- Create: `src/main/java/com/springclaw/runtime/memory/contract/MemoryIndexOutboxEntry.java`
- Create: `src/main/java/com/springclaw/runtime/memory/contract/ShortTermMemoryEntry.java`
- Create: `src/main/java/com/springclaw/runtime/memory/contract/ProjectMemoryItem.java`
- Create: `src/main/java/com/springclaw/runtime/memory/port/MemoryRecordStore.java`
- Create: `src/main/java/com/springclaw/runtime/memory/port/MemoryIndexOutboxStore.java`
- Create: `src/main/java/com/springclaw/runtime/memory/port/ShortTermMemoryStore.java`
- Create: `src/main/java/com/springclaw/runtime/memory/port/ProjectMemorySource.java`
- Create: `src/main/java/com/springclaw/runtime/memory/store/InMemoryMemoryRecordStore.java`
- Create: `src/main/java/com/springclaw/runtime/memory/store/InMemoryMemoryIndexOutboxStore.java`
- Create: `src/main/java/com/springclaw/runtime/memory/store/InMemoryShortTermMemoryStore.java`
- Test: `src/test/java/com/springclaw/runtime/memory/MemoryContractTest.java`
- Test: `src/test/java/com/springclaw/runtime/memory/InMemoryMemoryStoresTest.java`

- [x] **Step 1: Write failing invariants**

Cover:

```java
@Test
void activeMemoryRequiresActiveSlot() {
    assertThatThrownBy(() -> fixture(
            MemoryStatus.ACTIVE, null, 1L
    )).hasMessageContaining("activeSlot");
}

@Test
void personalScopePrincipalMustMatchUser() {
    SessionAccessClaim claim = SessionAccessClaim.personal(
            SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
            "api", "s1", "alice"
    );
    assertThat(MemoryScope.from(claim).scopeId()).isEqualTo("api:s1:alice");
}

@Test
void inMemoryStoreRejectsSecondActiveVersion() {
    store.insert(active("m1", "mv1", 1));
    assertThatThrownBy(() -> store.insert(active("m1", "mv2", 2)))
            .hasMessageContaining("active version");
}
```

- [x] **Step 2: Verify RED**

```bash
mvn -q -Dtest=MemoryContractTest,InMemoryMemoryStoresTest test
```

Expected: compilation failure because the memory package does not exist.

- [x] **Step 3: Implement minimal contracts**

Use these enum values:

```java
public enum MemoryType { EPISODIC, SEMANTIC, PROCEDURAL }
public enum MemoryScopeType {
    PERSONAL_SESSION, SHARED_SESSION, USER, PROJECT
}
public enum MemoryStatus {
    CANDIDATE, ACTIVE, SUPERSEDED, EXPIRED, REJECTED
}
public enum MemoryIndexOperation { UPSERT, DELETE }
```

`MemoryScope.from(SessionAccessClaim)` must create:

- `PERSONAL_SESSION`: `channel:sessionKey:userId`;
- `SHARED_SESSION`: `channel:sessionKey`;
- no method that accepts untrusted loose strings.

`MemoryRecordVersion` contains the spec fields using `Instant`, immutable
`List<String>`, and a nullable `Integer activeSlot`. Its constructor enforces:

- version and index revision are positive;
- scores are between `0` and `1`;
- `ACTIVE` requires `activeSlot == 1`;
- non-active states require `activeSlot == null`;
- automatic records require source kind, source identity, and extraction policy;
- `validUntil` is not before `validFrom`.

- [x] **Step 4: Implement narrow ports**

Use:

```java
public interface MemoryRecordStore {
    Optional<MemoryRecordVersion> findByVersionId(String memoryVersionId);
    Optional<MemoryRecordVersion> findActive(String logicalMemoryId);
    List<MemoryRecordVersion> findActiveByScope(
            MemoryScope scope, Set<MemoryType> types, int limit
    );
    void insert(MemoryRecordVersion version);
    boolean compareAndSetStatus(
            String memoryVersionId,
            MemoryStatus expected,
            MemoryStatus next,
            Integer nextActiveSlot,
            long expectedIndexRevision,
            long nextIndexRevision,
            Instant updatedAt
    );
}
```

```java
public interface MemoryIndexOutboxStore {
    void insert(MemoryIndexOutboxEntry entry);
    Optional<MemoryIndexOutboxEntry> claimNext(
            String owner, Instant now, Instant leaseUntil
    );
    boolean complete(
            String eventId, String claimToken, Instant completedAt
    );
    boolean fail(
            String eventId, String claimToken, String error, Instant retryAt
    );
    List<MemoryIndexOutboxEntry> findExpiredClaims(Instant now, int limit);
}
```

```java
public interface ShortTermMemoryStore {
    void append(MemoryScope scope, ShortTermMemoryEntry entry);
    List<ShortTermMemoryEntry> readRecent(MemoryScope scope, int limit);
    void mergeRecovery(
            MemoryScope scope,
            long watermark,
            List<ShortTermMemoryEntry> persistedEntries
    );
}
```

- [x] **Step 5: Implement bounded in-memory stores**

Use one per-logical-memory synchronized section for active-version writes and one
per-scope synchronized section for ordered short-term entries. Cap:

- 5,000 memory versions total;
- 5,000 outbox entries total;
- 40 short-term entries per scope;
- 5,000 scopes.

Throw on cap exhaustion; do not silently evict authoritative memory versions.

- [x] **Step 6: Verify GREEN and commit**

```bash
mvn -q -Dtest=MemoryContractTest,InMemoryMemoryStoresTest test
git add src/main/java/com/springclaw/runtime/memory \
  src/test/java/com/springclaw/runtime/memory
git commit -m "feat: add canonical memory contracts and fallback stores"
```

---

### Task 3: Add MySQL schema and persistence adapters

**Owner:** Claude after Task 2

**Files:**

- Create: `src/main/resources/sql/migrations/2026-06-23-memory-core.sql`
- Modify: `src/main/resources/sql/schema.sql`
- Create: `src/main/java/com/springclaw/config/MemorySchemaInitializer.java`
- Create: `src/main/java/com/springclaw/domain/entity/MemoryRecordEntity.java`
- Create: `src/main/java/com/springclaw/domain/entity/MemoryIndexOutboxEntity.java`
- Modify: `src/main/java/com/springclaw/domain/entity/MessageEvent.java`
- Create: `src/main/java/com/springclaw/mapper/MemoryRecordMapper.java`
- Create: `src/main/java/com/springclaw/mapper/MemoryIndexOutboxMapper.java`
- Modify: `src/main/java/com/springclaw/mapper/MessageEventMapper.java`
- Create: `src/main/java/com/springclaw/service/memory/store/MySqlMemoryRecordStore.java`
- Create: `src/main/java/com/springclaw/service/memory/store/MySqlMemoryIndexOutboxStore.java`
- Test: `src/test/java/com/springclaw/config/MemorySchemaInitializerTest.java`
- Test: `src/test/java/com/springclaw/service/memory/store/MySqlMemoryStoresIT.java`

- [ ] **Step 1: Write schema and repository tests first**

The integration test must prove:

```java
@Test
void onlyOneActiveVersionMayExist() {
    recordStore.insert(active("logical-1", "version-1", 1, 1));
    assertThatThrownBy(() ->
            recordStore.insert(active("logical-1", "version-2", 2, 2))
    ).hasRootCauseInstanceOf(DuplicateKeyException.class);
}

@Test
void automaticSourceIdentityIsIdempotent() {
    recordStore.insert(autoCandidate("run-1", "version-1"));
    assertThatThrownBy(() ->
            recordStore.insert(autoCandidate("run-1", "version-2"))
    ).hasRootCauseInstanceOf(DuplicateKeyException.class);
}

@Test
void expiredClaimCanBeReclaimedWithNewToken() {
    outboxStore.insert(pending("event-1", 1));
    MemoryIndexOutboxEntry first =
            outboxStore.claimNext("worker-a", T0, T0.plusSeconds(30)).orElseThrow();
    MemoryIndexOutboxEntry second =
            outboxStore.claimNext("worker-b", T0.plusSeconds(31), T0.plusSeconds(61))
                    .orElseThrow();

    assertThat(second.claimToken()).isNotEqualTo(first.claimToken());
}
```

- [ ] **Step 2: Verify RED**

Load local credentials without printing them:

```bash
set -a
source /Users/hanbingzheng/springclaw/.env.local
set +a
mvn -q -Dtest=MemorySchemaInitializerTest,MySqlMemoryStoresIT test
```

Expected: compilation failure because schema and adapters do not exist.

- [ ] **Step 3: Add exact DDL**

The migration must create:

```sql
CREATE TABLE IF NOT EXISTS memory_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  logical_memory_id VARCHAR(64) NOT NULL,
  memory_version_id VARCHAR(64) NOT NULL,
  memory_type VARCHAR(32) NOT NULL,
  scope_type VARCHAR(32) NOT NULL,
  scope_id VARCHAR(256) NOT NULL,
  owner_user_id VARCHAR(64) NULL,
  content TEXT NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  summary VARCHAR(2048) NULL,
  source_run_id VARCHAR(64) NULL,
  source_event_ids_json TEXT NULL,
  evidence_refs_json TEXT NULL,
  tags_json TEXT NULL,
  importance DECIMAL(5,4) NOT NULL,
  confidence DECIMAL(5,4) NOT NULL,
  status VARCHAR(32) NOT NULL,
  valid_from DATETIME(3) NOT NULL,
  valid_until DATETIME(3) NULL,
  supersedes_record_id BIGINT NULL,
  version INT NOT NULL,
  active_slot TINYINT NULL,
  source_kind VARCHAR(32) NULL,
  source_identity VARCHAR(192) NULL,
  extraction_policy_version VARCHAR(64) NULL,
  index_revision BIGINT NOT NULL,
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_memory_version_id (memory_version_id),
  UNIQUE KEY uk_memory_logical_version (logical_memory_id, version),
  UNIQUE KEY uk_memory_single_active (logical_memory_id, active_slot),
  UNIQUE KEY uk_memory_source_policy
    (source_kind, source_identity, extraction_policy_version, memory_type),
  KEY idx_memory_scope_active
    (scope_type, scope_id, status, deleted, update_time),
  KEY idx_memory_index_revision (index_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;
```

```sql
CREATE TABLE IF NOT EXISTS memory_index_outbox (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(64) NOT NULL,
  logical_memory_id VARCHAR(64) NOT NULL,
  memory_version_id VARCHAR(64) NOT NULL,
  memory_version INT NOT NULL,
  index_revision BIGINT NOT NULL,
  operation VARCHAR(16) NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  available_at DATETIME(3) NOT NULL,
  claimed_at DATETIME(3) NULL,
  claim_owner VARCHAR(128) NULL,
  claim_token VARCHAR(64) NULL,
  lease_until DATETIME(3) NULL,
  last_error VARCHAR(2048) NULL,
  create_time DATETIME(3) NOT NULL,
  update_time DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_memory_outbox_event (event_id),
  UNIQUE KEY uk_memory_outbox_revision
    (logical_memory_id, index_revision, operation),
  KEY idx_memory_outbox_claim
    (status, available_at, lease_until, id),
  KEY idx_memory_outbox_logical_revision
    (logical_memory_id, index_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;
```

Add nullable `event_key VARCHAR(192)` to `message_event`, backfill
`legacy:<id>`, then add `UNIQUE KEY uk_message_event_event_key(event_key)`.
The migration must use information-schema guards so it is idempotent on an
existing database.

- [ ] **Step 4: Implement explicit entity conversion**

Do not use implicit JSON type handlers. Follow `ToolInvocationProposalEntity`:
encode/decode lists explicitly and fail loudly on corrupt security/provenance JSON.
Do not extend `BaseEntity`; memory version and outbox timestamps participate in
CAS/lease semantics and must be controlled explicitly.

- [ ] **Step 5: Implement fenced mapper operations**

`MemoryIndexOutboxMapper.claimNext(...)` must select only an event for which no
lower outstanding revision exists for the same logical memory, and update it to:

```text
status=CLAIMED
claim_owner=<worker>
claim_token=<new token>
lease_until=<now + lease>
attempts=attempts+1
```

Completion and failure updates must include both `event_id` and `claim_token`.

- [ ] **Step 6: Run integration tests and commit**

```bash
set -a
source /Users/hanbingzheng/springclaw/.env.local
set +a
mvn -q -Dtest=MemorySchemaInitializerTest,MySqlMemoryStoresIT test
git add src/main/resources/sql \
  src/main/java/com/springclaw/config/MemorySchemaInitializer.java \
  src/main/java/com/springclaw/domain/entity \
  src/main/java/com/springclaw/mapper \
  src/main/java/com/springclaw/service/memory/store \
  src/test/java/com/springclaw/config/MemorySchemaInitializerTest.java \
  src/test/java/com/springclaw/service/memory/store/MySqlMemoryStoresIT.java
git commit -m "feat: persist versioned memory and index outbox"
```

---

### Task 4: Add atomic memory lifecycle management

**Owner:** Codex

**Files:**

- Create: `src/main/java/com/springclaw/service/memory/MemoryManagementService.java`
- Create: `src/main/java/com/springclaw/service/memory/MemoryWriteCommand.java`
- Create: `src/main/java/com/springclaw/service/memory/MemoryVersionFactory.java`
- Create: `src/main/java/com/springclaw/service/memory/InMemoryMemoryTransactionBoundary.java`
- Test: `src/test/java/com/springclaw/service/memory/MemoryManagementServiceTest.java`
- Test: `src/test/java/com/springclaw/service/memory/MemoryManagementServiceIT.java`

- [ ] **Step 1: Write failing lifecycle tests**

Cover:

```java
@Test
void createActiveWritesVersionAndUpsetOutboxAtomically() {
    MemoryRecordVersion created = service.create(command(ACTIVE));

    assertThat(recordStore.findActive(created.logicalMemoryId()))
            .contains(created);
    assertThat(outboxEvents())
            .singleElement()
            .extracting(MemoryIndexOutboxEntry::operation)
            .isEqualTo(MemoryIndexOperation.UPSERT);
}

@Test
void supersedeWritesDeleteBeforeNewUpsertRevision() {
    MemoryRecordVersion old = service.create(command(ACTIVE));
    MemoryRecordVersion next = service.supersede(
            old.memoryVersionId(), replacementCommand()
    );

    assertThat(outboxEvents())
            .extracting(
                    MemoryIndexOutboxEntry::operation,
                    MemoryIndexOutboxEntry::indexRevision
            )
            .containsExactly(
                    tuple(MemoryIndexOperation.UPSERT, 1L),
                    tuple(MemoryIndexOperation.DELETE, 2L),
                    tuple(MemoryIndexOperation.UPSERT, 3L)
            );
    assertThat(recordStore.findActive(old.logicalMemoryId())).contains(next);
}

@Test
void repeatedAutomaticSourceReturnsExistingVersion() {
    MemoryRecordVersion first = service.create(autoCommand("run-1"));
    MemoryRecordVersion second = service.create(autoCommand("run-1"));
    assertThat(second.memoryVersionId()).isEqualTo(first.memoryVersionId());
}
```

- [ ] **Step 2: Verify RED**

```bash
mvn -q -Dtest=MemoryManagementServiceTest test
```

Expected: compilation failure because the management service does not exist.

- [ ] **Step 3: Implement deterministic commands**

`MemoryWriteCommand` must contain:

```text
logicalMemoryId
memoryType
scope
content
summary
sourceRunId
sourceEventIds
evidenceRefs
tags
importance
confidence
requestedStatus
validFrom
validUntil
sourceKind
sourceIdentity
extractionPolicyVersion
```

`MemoryVersionFactory` normalizes text, computes SHA-256 content hash, assigns
version and index revision from the previous version, and derives stable version
IDs from logical ID plus version.

- [ ] **Step 4: Keep record and outbox in one transaction**

Annotate public lifecycle methods with `@Transactional` for MySQL-backed stores.
For in-memory stores, use a shared per-logical-memory transaction boundary that
can roll back both record and outbox mutations if either write fails; locking
alone is insufficient because it can expose or retain a partial record/outbox
state. Implement:

```java
MemoryRecordVersion create(MemoryWriteCommand command);
MemoryRecordVersion supersede(
        String currentVersionId,
        MemoryWriteCommand replacement
);
MemoryRecordVersion transition(
        String versionId,
        MemoryStatus expected,
        MemoryStatus next,
        Instant at
);
```

Rules:

- `CANDIDATE` creates no vector `UPSERT`;
- transition to `ACTIVE` writes `UPSERT`;
- transition from `ACTIVE` writes `DELETE`;
- supersede performs old deactivation and new activation atomically;
- retries by automatic source identity return the existing version;
- no method deletes raw audit facts.

- [ ] **Step 5: Run unit and MySQL integration tests**

```bash
set -a
source /Users/hanbingzheng/springclaw/.env.local
set +a
mvn -q -Dtest=MemoryManagementServiceTest,MemoryManagementServiceIT test
```

Expected: zero failures and zero errors.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/service/memory \
  src/test/java/com/springclaw/service/memory
git commit -m "feat: manage memory versions and index events atomically"
```

---

### Task 5: Add stable chat-event receipts and persistence intent

**Owner:** Claude after Task 4

**Files:**

- Create: `src/main/java/com/springclaw/service/event/MessageEventWrite.java`
- Create: `src/main/java/com/springclaw/service/event/MessageEventReceipt.java`
- Modify: `src/main/java/com/springclaw/service/event/MessageEventService.java`
- Modify: `src/main/java/com/springclaw/service/event/impl/MessageEventServiceImpl.java`
- Modify: `src/main/java/com/springclaw/service/session/AgentSessionService.java`
- Modify: `src/main/java/com/springclaw/service/session/impl/AgentSessionServiceImpl.java`
- Create: `src/main/java/com/springclaw/service/chat/impl/ChatPersistenceIntent.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatResultPersister.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java`
- Modify terminal call sites in:
  - `src/main/java/com/springclaw/service/chat/impl/BasicStreamEngine.java`
  - `src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java`
  - `src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java`
- Test: `src/test/java/com/springclaw/service/event/MessageEventServiceImplTest.java`
- Test: `src/test/java/com/springclaw/service/session/AgentSessionServiceImplTest.java`
- Test: `src/test/java/com/springclaw/service/chat/impl/ChatResultPersisterTest.java`
- Test: `src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPendingApprovalTest.java`

- [ ] **Step 1: Write failing receipt and suspension tests**

```java
@Test
void appendWithSameEventKeyIsIdempotent() {
    MessageEventReceipt first = service.append(write("chat:req-1:user"));
    MessageEventReceipt second = service.append(write("chat:req-1:user"));

    assertThat(second.eventId()).isEqualTo(first.eventId());
    assertThat(service.listSessionEvents(
            "s1", "u1", null, "CHAT", 10, true
    )).hasSize(1);
}

@Test
void confirmationSuspensionDoesNotWriteAssistantSemanticMemory() {
    persister.persist(
            context,
            "请确认",
            result,
            ChatPersistenceIntent.CONFIRMATION_SUSPENSION
    );

    verify(memoryService, never()).storeConversationTurn(
            any(), any(), any(), any(), any()
    );
    verify(agentSessionService).persistUserMessage(
            eq(context.session()),
            eq(context.effectiveUserMessage()),
            anyString()
    );
    verify(messageEventService).append(argThat(write ->
            write.eventKey().equals("chat:" + context.requestId() + ":user")
    ));
}
```

- [ ] **Step 2: Verify RED**

```bash
mvn -q -Dtest=MessageEventServiceImplTest,ChatResultPersisterTest,ChatServiceImplPendingApprovalTest test
```

Expected: compilation failure because receipt and intent APIs do not exist.

- [ ] **Step 3: Add explicit event writes**

Implement:

```java
public record MessageEventWrite(
        String eventKey,
        String sessionKey,
        String channel,
        String userId,
        String role,
        String eventType,
        String content,
        String requestId
) {}

public record MessageEventReceipt(
        long eventId,
        String eventKey,
        Instant occurredAt
) {}
```

Add:

```java
MessageEventReceipt append(MessageEventWrite write);
```

Keep `recordSingle(...)` as a compatibility method that calls `append(...)` with a
generated non-memory event key. For memory-eligible `CHAT` turns, callers supply:

```text
chat:<requestId>:user
chat:<requestId>:assistant:terminal
chat:<requestId>:suspension
```

The DB implementation inserts by unique `event_key`; on duplicate key it loads and
returns the existing row. The local implementation uses one map by event key.

- [ ] **Step 4: Add explicit persistence intent**

```java
public enum ChatPersistenceIntent {
    TERMINAL_RESULT,
    CONFIRMATION_SUSPENSION
}
```

Change `ChatResultPersister.persist(...)` to require the intent. All existing
successful/fallback terminal sites pass `TERMINAL_RESULT`. Only
`ChatServiceImpl.streamActionRequired(...)` passes
`CONFIRMATION_SUSPENSION`.

Add this service method:

```java
void persistUserMessage(
        AgentSession session,
        String userMessage,
        String soulVersion
);
```

Its implementation updates `lastUserMessage`, `soulVersion`, and `status`, then
uses the same DB/local fallback behavior as `persistConversation(...)`. It does
not modify `lastAssistantMessage`.

Suspension behavior:

- call a new `AgentSessionService.persistUserMessage(...)` method that updates the
  user message, soul version, and active status while preserving
  `lastAssistantMessage`;
- append `chat:<runId>:user` and suspension audit facts;
- do not call `MemoryService.storeConversationTurn`;
- do not create episodic memory;
- do not append a terminal assistant short-term entry.

- [ ] **Step 5: Verify all persister call sites are explicit**

Run:

```bash
rg -n "chatResultPersister\\.persist\\(" src/main/java
mvn -q -Dtest=MessageEventServiceImplTest,AgentSessionServiceImplTest,ChatResultPersisterTest,ChatServiceImplPendingApprovalTest,ChatServiceImplPersistenceTest,PromptInjectionTest test
```

Expected: every call has the intent argument; test compilation proves every
engine call site was migrated; all listed tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/service/event \
  src/main/java/com/springclaw/domain/entity/MessageEvent.java \
  src/main/java/com/springclaw/service/session \
  src/main/java/com/springclaw/service/chat/impl \
  src/test/java/com/springclaw/service/event \
  src/test/java/com/springclaw/service/session \
  src/test/java/com/springclaw/service/chat/impl
git commit -m "feat: distinguish terminal and suspended memory writes"
```

---

### Task 6: Add Redis short-term shadow storage and recovery

**Owner:** Claude

**Files:**

- Create: `src/main/java/com/springclaw/service/memory/store/RedisShortTermMemoryStore.java`
- Create: `src/main/java/com/springclaw/service/memory/ShortTermMemoryRecoveryService.java`
- Create: `src/main/java/com/springclaw/service/memory/ShortTermMemoryWriter.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatResultPersister.java`
- Test: `src/test/java/com/springclaw/service/memory/store/RedisShortTermMemoryStoreTest.java`
- Test: `src/test/java/com/springclaw/service/memory/ShortTermMemoryRecoveryServiceTest.java`
- Test: `src/test/java/com/springclaw/service/chat/impl/ChatResultPersisterTest.java`

- [ ] **Step 1: Write failing ordering and recovery tests**

```java
@Test
void delayedAppendKeepsDatabaseIdOrder() {
    store.append(scope, entry(12, "event-12"));
    store.append(scope, entry(10, "event-10"));
    store.append(scope, entry(11, "event-11"));

    assertThat(store.readRecent(scope, 10))
            .extracting(ShortTermMemoryEntry::eventId)
            .containsExactly(10L, 11L, 12L);
}

@Test
void recoveryMergePreservesConcurrentAppendAboveWatermark() {
    store.append(scope, entry(13, "event-13"));
    recovery.rebuild(scope, 12, List.of(
            entry(10, "event-10"),
            entry(11, "event-11"),
            entry(12, "event-12")
    ));

    assertThat(store.readRecent(scope, 10))
            .extracting(ShortTermMemoryEntry::eventId)
            .containsExactly(10L, 11L, 12L, 13L);
}
```

- [ ] **Step 2: Verify RED**

```bash
mvn -q -Dtest=RedisShortTermMemoryStoreTest,ShortTermMemoryRecoveryServiceTest test
```

Expected: compilation failure because Redis storage does not exist.

- [ ] **Step 3: Implement one Lua append/trim/TTL operation**

Use one ZSET key plus one recovery-lock key per scope. Score is persisted
`eventId`; member is canonical JSON containing `eventKey`. The Lua script performs:

```text
ZADD NX key eventId json
ZREMRANGEBYRANK key 0 -(maxEntries + 1)
EXPIRE key ttlSeconds
```

Do not use arrival time as order and do not delete/rewrite the key during normal
recovery.

- [ ] **Step 4: Implement owner-filtered MySQL recovery**

`ShortTermMemoryRecoveryService`:

1. acquires a per-scope lease;
2. reads the maximum eligible `message_event.id`;
3. loads only authorized `CHAT` user/assistant rows through that ID;
4. calls `mergeRecovery(...)`;
5. releases the lease with token-checked Lua.

If the persisted personal-session owner does not match the claim, throw before
querying chat events.

- [ ] **Step 5: Shadow-write terminal and suspension events**

`ChatResultPersister` passes returned event receipts to
`ShortTermMemoryWriter`. Terminal intent appends user and assistant. Suspension
intent appends user only. Redis failure logs a bounded warning and leaves MySQL as
the recovery source; it does not fail the already-persisted conversation.

- [ ] **Step 6: Run tests and commit**

```bash
mvn -q -Dtest=RedisShortTermMemoryStoreTest,ShortTermMemoryRecoveryServiceTest,ChatResultPersisterTest test
git add src/main/java/com/springclaw/service/memory \
  src/main/java/com/springclaw/service/chat/impl/ChatResultPersister.java \
  src/test/java/com/springclaw/service/memory \
  src/test/java/com/springclaw/service/chat/impl/ChatResultPersisterTest.java
git commit -m "feat: project ordered short-term memory into redis"
```

---

### Task 7: Add fenced vector projection and rebuild primitives

**Owner:** Claude

**Files:**

- Create: `src/main/java/com/springclaw/service/memory/index/MemoryVectorIndex.java`
- Create: `src/main/java/com/springclaw/service/memory/index/SpringAiMemoryVectorIndex.java`
- Create: `src/main/java/com/springclaw/service/memory/index/MemoryIndexWorker.java`
- Create: `src/main/java/com/springclaw/service/memory/index/MemoryIndexReconciler.java`
- Create: `src/main/java/com/springclaw/service/memory/index/MemoryIndexGenerationStore.java`
- Create: `src/main/java/com/springclaw/service/memory/index/MemoryIndexRebuildService.java`
- Test: `src/test/java/com/springclaw/service/memory/index/MemoryIndexWorkerTest.java`
- Test: `src/test/java/com/springclaw/service/memory/index/MemoryIndexReconcilerTest.java`
- Test: `src/test/java/com/springclaw/service/memory/index/MemoryIndexRebuildServiceTest.java`

- [ ] **Step 1: Write stale-write and rebuild tests**

```java
@Test
void staleUpsertIsNotAppliedAfterDeleteRevision() {
    when(recordStore.findActive("logical-1")).thenReturn(Optional.empty());

    worker.process(claimedUpsert("version-1", 1));

    verify(index, never()).upsert(any(), any());
    verify(outboxStore).complete(eq("event-1"), anyString(), any());
}

@Test
void workerCrashLeaseCanBeRetriedWithoutRevivingOldVersion() {
    when(recordStore.findActive("logical-1"))
            .thenReturn(Optional.of(activeVersion("version-2", 3)));

    worker.process(claimedUpsert("version-1", 1));

    verify(index).delete("version-1", activeGeneration());
    verify(index, never()).upsert(eq("version-1"), any());
}

@Test
void rebuildCopiesOnlyCurrentActiveVersionsThenAppliesTail() {
    rebuild.rebuild();

    verify(index).createGeneration("gen-2");
    verify(index).upsert(active1, "gen-2");
    verify(index).upsert(active2, "gen-2");
    verify(generationStore).activate("gen-2");
}
```

- [ ] **Step 2: Verify RED**

```bash
mvn -q -Dtest=MemoryIndexWorkerTest,MemoryIndexReconcilerTest,MemoryIndexRebuildServiceTest test
```

Expected: compilation failure because index components do not exist.

- [ ] **Step 3: Wrap Spring AI VectorStore**

Use `memoryVersionId` as `Document.id`. Store metadata:

```text
logicalMemoryId
memoryVersionId
memoryType
scopeType
scopeId
ownerUserId
version
indexRevision
generation
contentHash
```

`SpringAiMemoryVectorIndex.delete(...)` calls
`VectorStore.delete(List.of(memoryVersionId))`. This adapter exposes no raw
similarity-search API to controllers or engines.

- [ ] **Step 4: Fence worker execution**

For every claimed event:

1. reload authoritative active memory;
2. reject stale `UPSERT` by deleting its vector ID;
3. execute valid sink operation;
4. reload active memory and claim token;
5. if authority changed, compensate with delete;
6. complete only with matching claim token.

The worker must not mark success after losing its lease.

- [ ] **Step 5: Implement reconciliation and generation rebuild**

Reconciler scans indexed IDs in bounded batches and deletes any ID that is not the
current active version in MySQL.

Rebuild service:

- marks vector retrieval degraded;
- creates a new generation;
- captures the maximum authoritative index revision;
- scans active records through the watermark;
- applies newer outbox changes;
- compares active count and sampled hashes;
- switches one generation pointer;
- leaves old generation cleanup asynchronous.

Do not replay historical successful outbox events into the new generation.

- [ ] **Step 6: Run tests and commit**

```bash
mvn -q -Dtest=MemoryIndexWorkerTest,MemoryIndexReconcilerTest,MemoryIndexRebuildServiceTest test
git add src/main/java/com/springclaw/service/memory/index \
  src/test/java/com/springclaw/service/memory/index
git commit -m "feat: fence memory vector projection and rebuild"
```

---

### Task 8: Add typed Markdown project and procedural memory

**Owner:** Claude

**Files:**

- Create: `src/main/java/com/springclaw/service/memory/ProjectMemorySourceType.java`
- Create: `src/main/java/com/springclaw/service/memory/MarkdownProjectMemorySource.java`
- Modify: `src/main/java/com/springclaw/service/memory/MemoryBankService.java`
- Modify: `src/main/java/com/springclaw/service/memory/AgentLearningService.java`
- Test: `src/test/java/com/springclaw/service/memory/MarkdownProjectMemorySourceTest.java`
- Test: `src/test/java/com/springclaw/service/memory/MemoryBankServiceTest.java`
- Test: `src/test/java/com/springclaw/service/memory/AgentLearningServiceTest.java`

- [ ] **Step 1: Write failing typed-source and candidate tests**

```java
@Test
void returnsTypedSectionsWithoutGlobalTruncation() {
    List<ProjectMemoryItem> items = source.read();

    assertThat(items)
            .extracting(ProjectMemoryItem::sourceType)
            .contains(
                    "PROJECT_BRIEF",
                    "ARCHITECTURE_DECISION",
                    "APPROVED_LEARNING"
            );
    assertThat(items).allSatisfy(item ->
            assertThat(item.contentHash()).hasSize(64)
    );
}

@Test
void capturedFailureStartsAsCandidate() {
    AgentLearningEntry entry =
            service.captureTraceFailure(failedTrace()).orElseThrow();
    assertThat(service.listEntries(10))
            .filteredOn(item -> item.signature().equals(entry.signature()))
            .singleElement()
            .extracting(AgentLearningReviewItem::status)
            .isEqualTo("candidate");
}
```

- [ ] **Step 2: Verify RED**

```bash
mvn -q -Dtest=MarkdownProjectMemorySourceTest,MemoryBankServiceTest,AgentLearningServiceTest test
```

Expected: source class missing and captured learning currently starts `active`.

- [ ] **Step 3: Implement typed reads**

Map files:

```text
project-brief.md          -> PROJECT_BRIEF
current-state.md          -> CURRENT_STATE
architecture-decisions.md -> ARCHITECTURE_DECISION
agent-learnings.md        -> APPROVED_LEARNING entries
progress.md               -> PROGRESS
user-preferences.md       -> USER_PREFERENCE
other reviewed markdown   -> OTHER_REVIEWED_PROJECT_MEMORY
```

Return individual `ProjectMemoryItem` values with path, type, content, SHA-256
hash, review status, and file modification time. Do not apply one global
character limit in this source adapter.

- [ ] **Step 4: Change automatic learning default**

Render newly captured entries with:

```text
- status: candidate
```

Only `approved` and explicitly retained legacy `active` entries are eligible for
runtime project memory. Preserve all statuses in the review API.

- [ ] **Step 5: Keep current MemoryBank compatibility**

`MemoryBankService.renderSnapshot()` may continue producing the existing bounded
string for the active Phase 2B/legacy context path, but build it from typed items.
Do not change current answers or prompt shape in Phase 3A1.

- [ ] **Step 6: Run tests and commit**

```bash
mvn -q -Dtest=MarkdownProjectMemorySourceTest,MemoryBankServiceTest,AgentLearningServiceTest,ContextAssemblerTest test
git add src/main/java/com/springclaw/service/memory \
  src/test/java/com/springclaw/service/memory \
  src/test/java/com/springclaw/service/context/ContextAssemblerTest.java
git commit -m "feat: expose reviewed project memory as typed sources"
```

---

### Task 9: Wire shadow mode and close Phase 3A1 evidence

**Owner:** Codex

**Files:**

- Modify: `src/main/resources/application.yml`
- Modify: `.env.example`
- Create: `src/test/java/com/springclaw/service/memory/MemoryCoreShadowIT.java`
- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`

- [ ] **Step 1: Add disabled-by-default activation properties**

Add:

```yaml
springclaw:
  memory:
    core:
      enabled: ${SPRINGCLAW_MEMORY_CORE_ENABLED:false}
      short-term-shadow-enabled: ${SPRINGCLAW_MEMORY_SHORT_TERM_SHADOW_ENABLED:false}
      index-worker-enabled: ${SPRINGCLAW_MEMORY_INDEX_WORKER_ENABLED:false}
      schema-auto-init: ${SPRINGCLAW_MEMORY_SCHEMA_AUTO_INIT:true}
      short-term-max-messages: ${SPRINGCLAW_MEMORY_SHORT_TERM_MAX_MESSAGES:40}
      short-term-ttl-days: ${SPRINGCLAW_MEMORY_SHORT_TERM_TTL_DAYS:7}
```

The active context path remains unchanged when these flags are false.

- [ ] **Step 2: Write shadow-mode integration tests**

Prove:

- legacy context and Advisor behavior remain the active model input;
- terminal persistence creates deterministic shadow event receipts;
- suspension does not create terminal memory;
- Redis shadow failure does not overwrite successful MySQL persistence;
- vector worker is inactive when disabled;
- database-disabled mode uses bounded local stores and does not write a shared
  vector index;
- REST forged group strings retain a personal claim.

- [ ] **Step 3: Run Phase 3A1 focused suites**

```bash
set -a
source /Users/hanbingzheng/springclaw/.env.local
set +a
mvn -q -Dtest=SessionAccessClaimTest,MemoryContractTest,InMemoryMemoryStoresTest,MySqlMemoryStoresIT,MemoryManagementServiceTest,MemoryManagementServiceIT,MessageEventServiceImplTest,ChatResultPersisterTest,ChatServiceImplPendingApprovalTest,RedisShortTermMemoryStoreTest,ShortTermMemoryRecoveryServiceTest,MemoryIndexWorkerTest,MemoryIndexReconcilerTest,MemoryIndexRebuildServiceTest,MarkdownProjectMemorySourceTest,MemoryBankServiceTest,AgentLearningServiceTest,MemoryCoreShadowIT test
```

Required: zero failures and zero errors.

- [ ] **Step 4: Run compatibility gates**

```bash
mvn -q -Dtest=ContextPropagationCharacterizationTest,RuntimeRouteCharacterizationTest,FinalAnswerOwnershipCharacterizationTest,TransportParityCharacterizationTest,ChatContextFactoryTest,EngineSelectorTest,PromptInjectionTest,ToolRuntimeAspectInterceptionIT,WorkspaceGitGuardTest test
```

Required:

- no active context-input change;
- no route or answer ownership change;
- no P0 tool-safety regression.

- [ ] **Step 5: Run the full suite**

```bash
mvn -q test
```

Record exact testcase, failure, error, and skip counts from Surefire XML.

- [ ] **Step 6: Update the collaboration ledger**

Record:

- all Phase 3A1 commit SHAs;
- owners and modified paths;
- schema and local-mode behavior;
- shadow flags;
- exact focused and full-suite counts;
- known limitations:
  - no canonical `MemoryFrame` read activation;
  - no `ContextSnapshotFactory`;
  - no automatic semantic extraction;
  - no restart durability when DB is disabled;
- rollback order:
  - disable workers and shadow writes;
  - revert Markdown adapter;
  - revert vector projection;
  - revert Redis short-term projection;
  - revert persistence intent/event receipts;
  - revert memory stores/schema adapters;
  - revert memory contracts;
  - revert access claim only if no later phase depends on it.

- [ ] **Step 7: Commit evidence**

```bash
git add src/main/resources/application.yml \
  .env.example \
  src/test/java/com/springclaw/service/memory/MemoryCoreShadowIT.java \
  docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
git commit -m "docs: close phase 3a1 memory core evidence"
```

---

## Final Phase 3A1 gate

Phase 3A1 is complete only when:

- the worktree is clean;
- all nine tasks have separate reviewable commits;
- shared access cannot be minted from REST-provided strings;
- MySQL enforces one active version per logical memory;
- automatic writes are source-idempotent;
- outbox claims are leased and fenced;
- Redis short-term order comes from persisted event IDs;
- recovery preserves concurrent appends;
- stale vector entries cannot enter an authorized read;
- index rebuild uses a new generation and revision watermark;
- confirmation suspension creates no terminal memory;
- current ContextAssembler/Advisor route remains active and characterized;
- full tests pass.

Phase 3A2 may begin only after this gate and a new implementation plan.
