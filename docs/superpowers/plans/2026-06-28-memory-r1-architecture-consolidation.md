# Memory R1 Architecture Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Define the target memory architecture and migration order so SpringClaw has one canonical memory retrieval path instead of parallel legacy/canonical context assembly paths.

**Architecture:** This phase is documentation-only. It audits the current MySQL, Redis short-term, Redis VectorStore, Memory Bank, Knowledge Source, and Agent Learning responsibilities, then splits implementation into small future phases. MySQL remains the authority and recovery source; Redis is used for hot short-term windows and vector indexes; `MemoryFrame` / `ContextSnapshot` become the canonical read contract.

**Tech Stack:** Java 17, Spring Boot, MySQL 8, Redis / Redisson, Redis VectorStore, Spring AI, Maven, Markdown plans.

---

## 1. Current memory map

### 1.1 Existing write paths

| Data | Current writer | Current store | Notes |
|---|---|---|---|
| Chat event history | `ChatResultPersister`, `MessageEventService` | MySQL `message_event` | Authority for chat history, audit, short-term recovery, trace fallback. |
| Redis short-term shadow | `ShortTermMemoryWriter` | Redis ZSET via `RedisShortTermMemoryStore` | Optional behind `springclaw.memory.core.short-term-shadow-enabled=true`; failure does not block chat persistence. |
| Legacy semantic memory | `VectorMemoryService.storeConversationTurn` | Redis VectorStore or local fallback | Directly stores turn documents; not versioned by `memory_record`. |
| Canonical durable memory | `MemoryManagementService` | MySQL `memory_record` + `memory_index_outbox` | Versioned authority, currently not the only long-term memory path. |
| Project memory | `MemoryBankService`, `MarkdownProjectMemorySource` | Markdown under `docs/memory-bank` | Reviewed project facts/rules; can enter `MemoryFrame.projectItems`. |
| Agent learning | `AgentLearningService` | `docs/memory-bank/agent-learnings.md` | Requires status governance; must not become unbounded automatic prompt pollution. |
| Knowledge Source | `MarkdownKnowledgeSourceService` | reviewed Markdown snapshot | Governed but not injected into runtime prompt yet. |

### 1.2 Existing read paths

| Runtime path | Current reader | Sources | Problem |
|---|---|---|---|
| Legacy rollback/context path | `ContextAssembler` | MySQL `message_event`, `VectorMemoryService`, `MemoryBankService` | Still owns fallback mode and some prompt shape compatibility. |
| Canonical context path | `ContextSnapshotFactory` -> `MemoryCoordinator` | `ShortTermMemoryStore`, `MemoryRecordStore`, `ProjectMemorySource` | Correct direction, but long-term semantic vector path is not fully unified with `memory_record`. |
| Spring AI chat memory advisor | `MessageChatMemoryAdvisor` / `MessageEventChatMemory` | MySQL `message_event` | Kept only for rollback/legacy advisor mode; canonical mode quarantines retrieval advisors. |
| Vector recall | `VectorMemoryService` | Redis VectorStore + local fallback | Useful as retrieval index, but it currently behaves like a store authority too. |

---

## 2. Target responsibility model

### 2.1 Storage responsibilities

| Layer | Use | Authority? | Default backing |
|---|---|---:|---|
| MySQL `message_event` | Conversation event log, audit, short-term recovery, historical UI | Yes | MySQL |
| Redis short-term ZSET | Hot recent turns for prompt assembly | No | Redis with TTL and bounded length |
| MySQL `memory_record` | Durable user/project/procedural memory versions | Yes | MySQL |
| MySQL `memory_index_outbox` | Fenced index work queue | Yes | MySQL |
| Redis VectorStore | Semantic retrieval index | No | Redis Stack vector index |
| Markdown Memory Bank | Project rules and reviewed agent learning | Yes for repo-local project memory | Git-tracked Markdown |
| Knowledge Source Markdown | Reviewed external/project docs | Yes for source governance | Files or later Wiki/Obsidian export |

### 2.2 Runtime read responsibilities

The target prompt/context read flow should be:

```text
Accepted run
  -> ContextSnapshotFactory
  -> MemoryCoordinator
     -> Redis short-term first
     -> MySQL message_event recovery if Redis is empty/stale
     -> MySQL memory_record durable records
     -> Redis VectorStore only as index for semantic candidates
     -> ProjectMemorySource for Memory Bank / approved Knowledge Source
  -> MemoryFrame
  -> ContextSnapshot
  -> engine prompt renderer
```

This means:

- MySQL is not removed.
- Redis short-term becomes the hot read path, not the authority.
- Redis VectorStore is an index, not the memory source of truth.
- `ContextAssembler` remains rollback-only until engine prompt rendering stops requiring `AssembledContext` and `ContextInjection`.

---

## 3. Design decisions

### Decision 1: Keep MySQL

MySQL remains required because it provides:

- durable chat/event history;
- deterministic event ids for Redis short-term ordering;
- recovery when Redis is flushed or unavailable;
- memory version governance through `memory_record`;
- outbox-based vector index consistency;
- admin audit and user-facing historical views.

Do not replace MySQL with Redis.

### Decision 2: Promote Redis short-term, but not as authority

`RedisShortTermMemoryStore` is the right primitive for short-term memory because it already provides:

- per-scope keying;
- ZSET ordering by persisted event id;
- bounded max entries;
- TTL;
- idempotent append by `ZADD NX`;
- non-blocking failure behavior.

The missing piece is canonical default activation and recovery evidence, not a new store.

### Decision 3: Collapse long-term memory authority into `memory_record`

`VectorMemoryService` should stop acting like the long-term memory authority. The durable source should be:

```text
MemoryManagementService -> memory_record -> memory_index_outbox -> Redis VectorStore
```

The vector store should return candidates that are checked against current active `memory_record` versions before entering `MemoryFrame`.

### Decision 4: Keep Memory Bank separate from user memory

Memory Bank is project-level memory. It should not be mixed into user long-term memory. It should continue to be reviewed, bounded, and visible through `MemoryFrame.projectItems`.

### Decision 5: Agent Learning must remain governed

Agent Learning can affect future runs, so it must stay:

- short;
- status-filtered;
- reviewable;
- deduplicated by signature;
- visible in context summaries.

Do not let failure traces automatically create unlimited active prompt rules.

---

## 4. Recommended implementation phases

### B-target amendment

After review, the primary memory objective is product differentiation: make the
agent visibly understand the user. The R1-R5 stability route remains valid, but
must be amended by
[`2026-06-28-memory-b-product-differentiation-roadmap.md`](2026-06-28-memory-b-product-differentiation-roadmap.md).

Updated order:

```text
R1 -> R2 -> R3 -> R3.5 -> R4 -> R5 -> R6
```

R3.5 adds semantic write L1 and terminal reflection L2. R6 adds effectiveness
redlines and consolidation L3. Do not skip R3.5 after retiring
`VectorMemoryService.storeConversationTurn(...)` authority behavior in R3,
otherwise the system becomes safer but stops learning durable user preferences.

### Memory R2: Promote Redis short-term as canonical hot context

**Goal:** Make canonical `MemoryCoordinator` prefer Redis short-term by default while keeping MySQL `message_event` as the recovery source.

**Files:**

- Modify: `src/main/java/com/springclaw/config/MemoryShortTermShadowConfig.java`
- Modify: `src/main/java/com/springclaw/service/memory/ShortTermMemoryRecoveryService.java`
- Modify: `src/main/java/com/springclaw/service/memory/frame/MemoryCoordinator.java`
- Test: `src/test/java/com/springclaw/service/memory/frame/MemoryCoordinatorTest.java`
- Test: `src/test/java/com/springclaw/service/memory/ShortTermMemoryRecoveryServiceTest.java`
- Test: `src/test/java/com/springclaw/service/memory/store/RedisShortTermMemoryStoreTest.java`

- [ ] **Step 1: Add a failing canonical hot-read test**

Add a test proving `MemoryCoordinator` reads short-term entries from `ShortTermMemoryStore` and reports source counts:

```java
@Test
void retrieveUsesShortTermStoreAsCanonicalHotWindow() {
    InMemoryMemoryRecordStore recordStore = new InMemoryMemoryRecordStore();
    InMemoryShortTermMemoryStore shortTermStore = new InMemoryShortTermMemoryStore();
    MemoryScope scope = MemoryScope.from(SessionAccessClaim.personal(
            SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
            "api",
            "session-1",
            "alice"
    ));
    shortTermStore.append(scope, new ShortTermMemoryEntry(
            10L,
            "chat:req-1:user",
            "req-1",
            "USER",
            "alice",
            "What happened yesterday?",
            Instant.parse("2026-06-28T00:00:00Z")
    ));

    MemoryCoordinator coordinator = new MemoryCoordinator(
            recordStore,
            () -> shortTermStore,
            ignored -> List.of(),
            Clock.fixed(Instant.parse("2026-06-28T00:01:00Z"), ZoneOffset.UTC),
            6000,
            20
    );

    MemoryFrameResult result = coordinator.retrieve(new MemoryFrameRequest(
            "run-1",
            scope,
            "question"
    ));

    assertThat(result.frame().shortTermTurns())
            .extracting(MemoryFrameItem::content)
            .containsExactly("What happened yesterday?");
    assertThat(result.trace().sourceCounts()).containsEntry("shortTerm", 1);
}
```

Expected before implementation: fail if source accounting or fallback behavior does not match the canonical hot-window requirement.

- [ ] **Step 2: Keep MySQL recovery separate from normal retrieval**

Do not make `MemoryCoordinator` query `message_event` directly on every request. Recovery should be explicit:

```text
Redis miss/stale -> ShortTermMemoryRecoveryService uses MessageEventService to refill Redis up to watermark -> MemoryCoordinator reads Redis again
```

If recovery is not implemented in the same slice, record an omission:

```text
SOURCE_UNAVAILABLE / SHORT_TERM / "short-term" / "short-term store empty and recovery not attempted"
```

- [ ] **Step 3: Flip only the canonical flag in a later PR**

Do not change defaults until tests prove:

```bash
mvn -q -Dtest=MemoryCoordinatorTest,ShortTermMemoryRecoveryServiceTest,RedisShortTermMemoryStoreTest test
mvn -q -Dtest=ChatControllerSpringBootCanonicalSmokeIT test
```

Acceptance:

- Redis short-term is used by canonical context reads.
- MySQL remains the recovery source.
- Redis failure does not fail chat persistence.
- Rollback `ContextAssembler` behavior is unchanged.

### Memory R3: Unify durable long-term memory behind `memory_record`

**Goal:** Treat `memory_record` as the durable long-term memory authority and Redis VectorStore as a derived retrieval index.

**Files:**

- Modify: `src/main/java/com/springclaw/service/memory/impl/VectorMemoryService.java`
- Modify: `src/main/java/com/springclaw/service/memory/index/MemoryIndexWorker.java`
- Modify: `src/main/java/com/springclaw/service/memory/frame/MemoryCoordinator.java`
- Test: `src/test/java/com/springclaw/service/memory/index/MemoryIndexWorkerTest.java`
- Test: `src/test/java/com/springclaw/service/memory/frame/MemoryCoordinatorTest.java`
- Test: `src/test/java/com/springclaw/service/memory/store/MySqlMemoryStoresIT.java`

- [ ] **Step 1: Add failing stale-vector candidate test**

Add a test where a vector candidate references a superseded `memoryVersionId`. `MemoryCoordinator` must omit it instead of injecting stale content:

```java
assertThat(result.frame().semanticFacts()).isEmpty();
assertThat(result.frame().omissions())
        .anyMatch(omission -> omission.category()
                == MemoryFrameOmission.Category.STALE_VECTOR_HIT);
```

- [ ] **Step 2: Require vector candidates to carry memory ids**

Vector documents produced from `memory_record` must include:

```text
logicalMemoryId
memoryVersionId
memoryType
scopeType
scopeId
indexRevision
```

If those metadata fields are missing, omit the candidate.

- [ ] **Step 3: Stop direct conversation-turn vector writes from being the authority**

Deprecate this authority behavior:

```java
memoryService.storeConversationTurn(...)
```

Do not remove the method immediately. Route new durable memory extraction through `MemoryManagementService`.

Acceptance:

- `memory_record` is the source of truth.
- Vector hits are verified against current active records.
- Existing local fallback tests remain green.
- No rollback context behavior changes.

### Memory R4: Move engine prompt rendering from legacy view to typed context

**Goal:** Make engines consume `ContextSnapshot` / `MemoryFrame` sections directly so `AssembledContext` becomes rollback-only.

**Files:**

- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatContext.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java`
- Modify: engine prompt builders under `src/main/java/com/springclaw/service/chat/impl/`
- Test: `src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalLifecycleProjectionTest.java`
- Test: `src/test/java/com/springclaw/architecture/CanonicalRetrievalBoundaryTest.java`

- [ ] **Step 1: Add a failing engine-context contract test**

Add a test to `ChatContextFactoryCanonicalLifecycleProjectionTest` with a mocked
`LegacyContextViewAdapter`:

```java
@Test
void canonicalModeExposesTypedSnapshotBeforeLegacyViewProjection() {
    ContextSnapshot snapshot = snapshotWith(
            List.of("recent user turn"),
            List.of("verified semantic fact"),
            List.of("follow project rule")
    );
    when(contextSnapshotFactory.create(any())).thenReturn(snapshot);
    when(legacyContextViewAdapter.adapt(snapshot)).thenReturn(legacyView());

    ChatContext context = factory.build(request());

    assertThat(context.contextSnapshot()).isSameAs(snapshot);
    assertThat(context.contextSnapshot().shortTermEvents())
            .containsExactly("recent user turn");
    assertThat(context.contextSnapshot().semanticRecallItems())
            .containsExactly("verified semantic fact");
    assertThat(context.contextSnapshot().activeLearningRules())
            .containsExactly("follow project rule");
    verify(legacyContextViewAdapter).adapt(snapshot);
}
```

Expected before R4 implementation: fail to compile if `ChatContext` does not yet
carry the typed `ContextSnapshot`, or fail because engines still only receive
the legacy view.

- [ ] **Step 2: Add a prompt renderer**

Introduce a small renderer with explicit sections:

```text
# Current question
# System prompt
# Short-term turns
# Durable episodic memory
# Semantic facts
# Procedural rules
# Project memory
# Active learning rules
```

- [ ] **Step 3: Keep rollback adapter intact**

Do not delete:

```text
ContextAssembler
LegacyContextViewAdapter
LegacyContextView
AssembledContext
ContextInjection
```

until typed context is proven across all active engines.

Acceptance:

- Canonical mode can render prompts without legacy context assembly.
- Rollback mode still works.
- Retrieval still happens exactly once.

### Memory R5: Knowledge Source controlled injection

**Goal:** Allow approved Knowledge Source snapshots to enter `ProjectMemorySource` without writing them into user long-term memory.

**Files:**

- Modify: `src/main/java/com/springclaw/service/knowledge/MarkdownKnowledgeSourceService.java`
- Modify: `src/main/java/com/springclaw/service/memory/MarkdownProjectMemorySource.java`
- Test: `src/test/java/com/springclaw/service/knowledge/MarkdownKnowledgeSourceServiceTest.java`
- Test: `src/test/java/com/springclaw/service/memory/MarkdownProjectMemorySourceTest.java`

- [ ] **Step 1: Add approved-source injection test**

Create a Markdown source with front matter:

```markdown
---
status: approved
---
# Runtime rule
Use canonical run id for trace correlation.
```

Assert it appears as a project memory item, not as a user memory record.

- [ ] **Step 2: Add rejected-source exclusion test**

Create a source with:

```markdown
---
status: rejected
---
This must not enter runtime context.
```

Assert it is omitted with a review-status reason.

Acceptance:

- Approved project knowledge can enter `MemoryFrame.projectItems`.
- Disabled/rejected knowledge is visible in review UI but excluded from prompt.
- No user vector memory contamination.

---

## 5. Do-not-do list

Do not do these in Memory R1/R2:

- delete MySQL `message_event`;
- make Redis the authority;
- inject Knowledge Source into user long-term memory;
- let Agent Learning auto-grow without review/status limits;
- enable Spring AI `MessageChatMemoryAdvisor` in canonical mode;
- delete `ContextAssembler` before engine prompt rendering migrates;
- merge memory cleanup with trace double-write cleanup.

---

## 6. Acceptance criteria for Memory R1

- This document records the current memory components and target responsibilities.
- The plan explicitly answers:
  - MySQL stays as authority and recovery source.
  - Redis short-term is appropriate for hot short-term context.
  - Redis VectorStore is an index, not the durable authority.
  - Memory Bank and Knowledge Source stay separate from user long-term memory.
  - Agent Learning remains reviewed and status-filtered.
- No production Java, schema, or configuration behavior changes are made.
- The next implementation PR should be Memory R2, not more legacy cleanup.

---

## 7. Verification commands for this documentation phase

Run:

```bash
rg -n "Memory R1|Memory R2|Redis short-term|memory_record|ContextAssembler" docs/superpowers/plans/2026-06-28-memory-r1-architecture-consolidation.md
git diff --check
```

Expected:

- the first command finds the new plan sections;
- `git diff --check` passes.
