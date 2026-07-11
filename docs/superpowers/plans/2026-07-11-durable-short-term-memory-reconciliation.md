# Durable Short-Term Memory Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make canonical snapshots observe the latest authorized durable chat events while Redis is an identity-safe, rebuildable cache.

**Architecture:** Add a `MemoryScope` event reader, replace Redis JSON-member storage with an event-key-indexed v2 projection, reconcile durable and cache windows in `MemoryCoordinator`, then correct write scope and text parity.

**Tech Stack:** Java 17, Spring Boot 3.5, MyBatis-Plus, Flyway, MySQL, Redisson Lua, JUnit 5, AssertJ, Mockito, Maven.

## Global Constraints

- MySQL `message_event` is the durable fact source; Redis is a bounded projection.
- Only positive durable event IDs enter Redis or `ShortTermMemoryEntry`.
- Scope queries constrain channel and session; personal queries additionally constrain authorization principal.
- Dependency failure must not break persistence or the user response.
- Do not add a message bus, change long-term memory, or refactor engines.

---

### Task 1: Scope-aware durable CHAT reader and Flyway index

**Files:**

- Create: `src/main/java/com/springclaw/service/event/ShortTermChatEventRead.java`
- Modify: `src/main/java/com/springclaw/service/event/MessageEventService.java`
- Modify: `src/main/java/com/springclaw/service/event/impl/MessageEventServiceImpl.java`
- Create: `src/main/resources/db/migration/V4__message_event_short_term_scope_index.sql`
- Modify: `src/test/java/com/springclaw/service/event/MessageEventServiceImplTest.java`

**Interfaces:**

```java
public record ShortTermChatEventRead(List<MessageEvent> events, Source source) {
    public enum Source { DURABLE, LOCAL_FALLBACK }
}

ShortTermChatEventRead readShortTermChatEvents(MemoryScope scope, int limit);
```

- [ ] **Step 1: Write failing reader tests**

Append CHAT events for one session key across `api` and `feishu`, two personal users, and two shared users. Assert a personal `api` scope returns only Alice's `api` USER/ASSISTANT events; assert a verified shared `api` scope returns shared participants but never `feishu` rows. Assert database-disabled reads report `LOCAL_FALLBACK`.

- [ ] **Step 2: Verify RED**

Run `mvn -q -Dtest=MessageEventServiceImplTest test`.

Expected: test compilation fails because the typed reader does not exist.

- [ ] **Step 3: Implement the reader and index**

Implement the record with immutable list and non-null source. Query exact channel, session, `CHAT`, allowed roles, and positive/nonblank identity fields. Add a personal user predicate only for `PERSONAL_SESSION`; select the newest bounded rows and return ascending ID order. On persistence-disabled/read error, filter local cache and return `LOCAL_FALLBACK`.

Create exactly:

```sql
CREATE INDEX idx_message_event_short_term_scope_id
    ON message_event (channel, session_key, user_id, event_type, deleted, id);
```

- [ ] **Step 4: Verify GREEN and commit**

Run `mvn -q -Dtest=MessageEventServiceImplTest,ToolInvocationProposalRepositoryTest test`.

Expected: all pass and Flyway validates four migrations.

Run `git add src/main/java/com/springclaw/service/event/ShortTermChatEventRead.java src/main/java/com/springclaw/service/event/MessageEventService.java src/main/java/com/springclaw/service/event/impl/MessageEventServiceImpl.java src/main/resources/db/migration/V4__message_event_short_term_scope_index.sql src/test/java/com/springclaw/service/event/MessageEventServiceImplTest.java && git diff --cached --check && git commit -m "feat: scope durable short-term event reads"`.

### Task 2: Redis v2 projection keyed by event identity

**Files:**

- Modify: `src/main/java/com/springclaw/service/memory/store/RedisShortTermMemoryStore.java`
- Modify: `src/test/java/com/springclaw/service/memory/store/RedisShortTermMemoryStoreTest.java`

**Interfaces:** Existing `ShortTermMemoryStore.append`, `readRecent`, and `mergeRecovery` signatures stay unchanged.

- [ ] **Step 1: Write failing Redis tests**

Append two entries with the same `eventKey` but different timestamp/payload metadata; assert one returned event. Append `maxEntries + 1` events; assert the evicted event has no ZSET member or hash payload. Assert recovery replay remains idempotent and reads return durable ID order.

- [ ] **Step 2: Verify RED**

Run `mvn -q -Dtest=RedisShortTermMemoryStoreTest test`.

Expected: same-event-key replay returns two entries because current ZSET members are JSON.

- [ ] **Step 3: Implement v2 Lua storage**

Use `springclaw:memory:short-term:v2:<type>:<scope>:order` ZSET with `eventKey` member and ID score, plus `:entry` HASH with JSON payload. A single Lua script must `ZADD NX`, store payload for newly added key, trim both structures together, and refresh TTL on both. `readRecent` uses `ZRANGE` plus `HMGET`. Reject `eventId <= 0` before Lua.

- [ ] **Step 4: Verify GREEN and commit**

Run `mvn -q -Dtest=RedisShortTermMemoryStoreTest,ShortTermMemoryRecoveryServiceTest test`.

Expected: replay, trim, ordered read, and recovery tests pass.

Run `git add src/main/java/com/springclaw/service/memory/store/RedisShortTermMemoryStore.java src/test/java/com/springclaw/service/memory/store/RedisShortTermMemoryStoreTest.java && git diff --cached --check && git commit -m "fix: key short-term redis projection by event"`.

### Task 3: Read-through durable reconciliation

**Files:**

- Modify: `src/main/java/com/springclaw/service/memory/frame/MemoryCoordinator.java`
- Modify: `src/main/java/com/springclaw/service/memory/ShortTermMemoryRecoveryService.java`
- Modify: `src/test/java/com/springclaw/service/memory/frame/MemoryCoordinatorTest.java`
- Modify: `src/test/java/com/springclaw/service/memory/ShortTermMemoryRecoveryServiceTest.java`

**Interfaces:** Consume `readShortTermChatEvents(scope, limit)`. On `DURABLE`, return union keyed by event key, durable rows overriding cache, then call `mergeRecovery` best-effort.

- [ ] **Step 1: Write failing reconciliation tests**

Seed cache event 10 and durable events 10/11. Assert the current `MemoryFrame` includes both and cache receives 11. Seed stale cache content for a durable event key and assert durable content wins. For `LOCAL_FALLBACK`, assert cached entries remain usable, no recovery call occurs, and trace warning contains `durable short-term reconciliation unavailable`.

- [ ] **Step 2: Verify RED**

Run `mvn -q -Dtest=MemoryCoordinatorTest test`.

Expected: partial-cache test fails because current code queries persistence only when cache is empty.

- [ ] **Step 3: Implement deterministic union**

Always read cache and typed event source. For `DURABLE`, use:

```java
Map<String, ShortTermMemoryEntry> merged = new LinkedHashMap<>();
cachedEntries.forEach(entry -> merged.put(entry.eventKey(), entry));
durableEntries.forEach(entry -> merged.put(entry.eventKey(), entry));
List<ShortTermMemoryEntry> ordered = merged.values().stream()
        .sorted(Comparator.comparingLong(ShortTermMemoryEntry::eventId))
        .skip(Math.max(0, merged.size() - SHORT_TERM_ENTRY_LIMIT))
        .toList();
```

Use the maximum durable ID as recovery watermark. Refactor recovery service to use the typed reader and skip projection when source is local fallback.

- [ ] **Step 4: Verify GREEN and commit**

Run `mvn -q -Dtest=MemoryCoordinatorTest,ShortTermMemoryRecoveryServiceTest test`.

Expected: scope, reconciliation, and recovery tests pass.

Run `git add src/main/java/com/springclaw/service/memory/frame/MemoryCoordinator.java src/main/java/com/springclaw/service/memory/ShortTermMemoryRecoveryService.java src/test/java/com/springclaw/service/memory/frame/MemoryCoordinatorTest.java src/test/java/com/springclaw/service/memory/ShortTermMemoryRecoveryServiceTest.java && git diff --cached --check && git commit -m "fix: reconcile short-term memory from durable events"`.

### Task 4: Preserve canonical scope and exact persisted content

**Files:**

- Modify: `src/main/java/com/springclaw/service/event/MessageEventReceipt.java`
- Modify: `src/main/java/com/springclaw/service/memory/ShortTermMemoryWriter.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatResultPersister.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatResultPersisterTest.java`
- Create: `src/test/java/com/springclaw/service/memory/ShortTermMemoryWriterTest.java`

**Interfaces:** `MessageEventReceipt.isDurable()` is `eventId > 0`. Canonical writer scope is snapshot `MemoryFrame.scope`; legacy retains personal scope.

- [ ] **Step 1: Write failing write-path tests**

Create a shared canonical snapshot and assert writer calls the store with its `SHARED_SESSION` scope. Pass negative receipts and assert no throw/store interaction. Capture terminal assistant `MessageEventWrite.content()` and assert it equals the writer's assistant content.

- [ ] **Step 2: Verify RED**

Run `mvn -q -Dtest=ShortTermMemoryWriterTest,ChatResultPersisterTest test`.

Expected: shared scope and exact-content assertions fail.

- [ ] **Step 3: Implement scope/content convergence**

Add `public boolean isDurable() { return eventId > 0; }`. Skip null/non-durable receipts. Resolve writer scope with snapshot `memoryFrame().scope()` when available, otherwise legacy personal scope. In persister, place exact assistant CHAT text in `assistantEventContent` and use that variable both for `MessageEventWrite` and `shadowTerminal`.

- [ ] **Step 4: Verify GREEN and commit**

Run `mvn -q -Dtest=ShortTermMemoryWriterTest,ChatResultPersisterTest,ChatControllerSpringBootCanonicalSmokeIT test`.

Expected: scope, non-durable, content parity, and canonical smoke tests pass.

Run `git add src/main/java/com/springclaw/service/event/MessageEventReceipt.java src/main/java/com/springclaw/service/memory/ShortTermMemoryWriter.java src/main/java/com/springclaw/service/chat/impl/ChatResultPersister.java src/test/java/com/springclaw/service/chat/impl/ChatResultPersisterTest.java src/test/java/com/springclaw/service/memory/ShortTermMemoryWriterTest.java && git diff --cached --check && git commit -m "fix: preserve canonical short-term memory scope"`.

### Task 5: Complete verification and documentation

**Files:**

- Modify: `docs/superpowers/specs/2026-07-11-durable-short-term-memory-reconciliation-design.md`

- [ ] **Step 1: Run focused suite**

Run `mvn -q -Dtest=MessageEventServiceImplTest,RedisShortTermMemoryStoreTest,ShortTermMemoryRecoveryServiceTest,MemoryCoordinatorTest,ShortTermMemoryWriterTest,ChatResultPersisterTest,ChatControllerSpringBootCanonicalSmokeIT test`.

Expected: exit code 0.

- [ ] **Step 2: Run complete suite**

Run `mvn -q test`.

Expected: exit code 0; summarize totals from `target/surefire-reports/TEST-*.xml`.

- [ ] **Step 3: Update evidence and commit**

Mark design `Implemented and verified`, append focused/full result, then run `git add docs/superpowers/specs/2026-07-11-durable-short-term-memory-reconciliation-design.md && git diff --cached --check && git commit -m "docs: verify durable short-term memory reconciliation"`.

## Acceptance Checklist

- [ ] Same event key appears once in Redis v2.
- [ ] Partial Redis loss cannot omit durable CHAT events from canonical frame.
- [ ] Durable reads enforce channel and accepted scope.
- [ ] Shared canonical context writes to shared scope.
- [ ] Negative local receipts do not crash or enter Redis.
- [ ] Redis/MySQL assistant content matches for one event key.
- [ ] Flyway V4 and full Maven suite pass.
