# Durable Short-Term Memory Reconciliation Design

**Date:** 2026-07-11
**Status:** Approved by the user's continuous-delivery authorization; implementation planning in progress
**Branch:** `codex/memory-durable-reconciliation`
**Base:** `392917b` (`codex/flyway-schema-migration`)

## 1. Objective

Make `message_event` the authoritative source for short-term conversation memory and make Redis a reconstructable, scope-correct hot projection. A context snapshot must not omit a durable chat turn merely because Redis contains a non-empty but stale subset.

The user-facing result is simple: recent conversations remain correctly scoped to the user or verified shared session, survive Redis loss or restart, and do not silently mix channels that reuse a session key.

## 2. Audit findings

The current implementation already has valuable building blocks—stable chat event keys, a durable MySQL event table, a Redis shadow store, recovery code, and `MemoryFrame`—but their contracts do not completely converge.

1. `RedisShortTermMemoryStore` stores a complete JSON payload as the ZSET member. `ZADD NX` therefore deduplicates identical JSON, not `eventKey`; a replay with the same event key but a different timestamp can create a second entry.
2. `MemoryCoordinator` reads MySQL only when Redis is empty. A non-empty Redis set missing a newly committed event is treated as complete and produces a stale `ContextSnapshot`.
3. MySQL short-term queries are keyed by session and, for personal scope, user, but do not include channel. A shared `sessionKey` across channels can return unrelated events.
4. `ShortTermMemoryWriter` reconstructs a personal scope from `ChatContext`, even when canonical context already contains a shared-session `MemoryScope` frozen from acceptance.
5. A local fallback `MessageEventReceipt` has a negative event ID. Passing it to `ShortTermMemoryEntry`, which requires a positive durable event ID, can throw during a non-durable outage path.
6. The assistant text stored in Redis can differ from the persisted CHAT event because the persistence path prefixes the durable event while the shadow writer receives the unprefixed value.

## 3. Scope

### In scope

- A typed, scope-aware short-term event read API that applies channel, session, event type, roles, and personal/shared authorization together.
- A versioned Redis v2 projection that uses `eventKey` as the identity and durable event ID as ordering score.
- Read-through reconciliation: each canonical `MemoryFrame` retrieval merges the durable window with the Redis window and rehydrates Redis opportunistically.
- Canonical scope use in `ShortTermMemoryWriter` whenever `ChatContext` carries a `ContextSnapshot`.
- Explicit non-durable receipt handling: local fallback events do not enter the distributed durable projection and do not throw.
- Exact content parity between persisted CHAT event content and its Redis projection.
- A focused Flyway index for the new channel-aware durable read path.
- Tests for partial Redis loss, duplicate event-key replay, channel isolation, personal/shared scope behavior, and non-durable fallback.

### Out of scope

- A cross-instance durable event journal for database outages.
- Replacing MySQL `message_event` with Kafka, RabbitMQ, or a new event bus.
- Long-term semantic extraction, vector ranking, project-memory budgeting, or learning-rule changes.
- Changing `ContextSnapshot` identity or the accepted-run validation completed in the previous phase.
- Rewriting generic event/audit APIs that do not read short-term CHAT memory.

## 4. Alternatives

### A. Guard only the negative receipt

Skip `ShortTermMemoryWriter` when `eventId <= 0`.

This avoids an outage-path exception but leaves stale Redis reads, event-key duplicate risk, shared-scope drift, and cross-channel reads unresolved. It is rejected.

### B. Durable source plus Redis v2 projection

Keep MySQL immutable chat events as the fact source. Redis holds a bounded projection identified by `eventKey`; each frame reads both sources, returns a deterministic merged window, and repairs Redis from durable rows.

This is selected. It fixes correctness without adding a separate platform dependency.

### C. New asynchronous event-projection service

Publish every event to a durable bus and let a consumer build Redis.

This is a possible future scale-out architecture, but it delays the current user-visible correctness fixes and adds operational risk. It is deferred.

## 5. Target architecture

```text
ChatResultPersister
    -> MessageEventService.append (durable CHAT event + stable eventKey)
    -> ShortTermMemoryWriter (only durable receipts)
    -> Redis short-term v2 projection

ContextSnapshotFactory
    -> MemoryCoordinator.retrieve(accepted MemoryScope)
    -> Redis v2 recent window
    -> MessageEventService.readShortTermChatEvents(scope)
    -> deterministic union, durable row wins by eventKey
    -> Redis mergeRecovery(best effort)
    -> MemoryFrame / ContextSnapshot
```

MySQL controls identity, authorization, durable ordering, and recovery. Redis controls only latency and bounded hot retention.

## 6. Contracts and components

### 6.1 `MessageEventReceipt`

Add:

```java
public boolean isDurable() {
    return eventId > 0;
}
```

Positive IDs are database-assigned durable events. Negative local-cache IDs remain useful for same-process audit fallback but are not valid distributed short-term memory positions.

### 6.2 `ShortTermChatEventRead`

Create `com.springclaw.service.event.ShortTermChatEventRead`:

```java
public record ShortTermChatEventRead(
        List<MessageEvent> events,
        Source source
) {
    public enum Source { DURABLE, LOCAL_FALLBACK }
}
```

Add to `MessageEventService`:

```java
ShortTermChatEventRead readShortTermChatEvents(MemoryScope scope, int limit);
```

The method is the only MySQL reader used by short-term recovery and `MemoryCoordinator`. Its query semantics are fixed:

- `channel == scope.channel()`;
- `session_key == scope.sessionKey()`;
- `event_type == CHAT`;
- role is `USER` or `ASSISTANT`;
- personal scope additionally requires `user_id == scope.authorizationPrincipal()`;
- shared scope intentionally has no user filter;
- results are ordered by durable `id` ascending after selecting the newest bounded window;
- only positive IDs and nonblank event key, request ID, user ID, and content are returned.

When durable persistence is disabled or a database read fails, return `LOCAL_FALLBACK` with the bounded local events. Callers may use those events for same-process audit views but must not project negative IDs to Redis or claim durable reconciliation.

### 6.3 Redis v2 short-term projection

Replace JSON-as-ZSET-member storage with two versioned keys per `MemoryScope`:

```text
springclaw:memory:short-term:v2:<scope-type>:<scope-id>:order   ZSET
springclaw:memory:short-term:v2:<scope-type>:<scope-id>:entry   HASH
```

- ZSET member: `eventKey`.
- ZSET score: positive durable `eventId`.
- HASH field: `eventKey`; value: canonical JSON payload.
- One Lua script performs `ZADD NX`, `HSET`, bounded trimming, matching `HDEL`, and TTL refresh for both keys.
- Existing v1 keys are ignored. The first durable read-through reconciliation repopulates v2; old keys expire naturally.
- A replay with the same `eventKey` cannot create another ordered item even if `occurredAt` differs.

Redis stores only `ShortTermMemoryEntry` values with positive event IDs. A store implementation must reject invalid entries before invoking Lua, while `ShortTermMemoryWriter` prevents ordinary application flow from reaching that guard.

### 6.4 Read-through reconciliation in `MemoryCoordinator`

For every short-term read:

1. Read Redis v2 recent entries.
2. Call `readShortTermChatEvents(scope, limit)`.
3. If source is `DURABLE`, convert the durable events, merge by `eventKey`, prefer durable data for equal keys, sort by positive event ID, keep the newest configured limit, and call `ShortTermMemoryStore.mergeRecovery` best-effort.
4. If source is `LOCAL_FALLBACK`, use Redis entries only for distributed short-term context. Add a bounded trace warning explaining that durable reconciliation was unavailable; do not map negative local IDs into `ShortTermMemoryEntry`.
5. If Redis is empty and durable rows exist, the returned frame still contains the durable rows in that same request.

This makes the snapshot correct even before Redis repair finishes.

### 6.5 Canonical scope and event-content parity

`ShortTermMemoryWriter` must use:

```java
context.contextSnapshot() == null
        ? legacyPersonalScope(context)
        : context.contextSnapshot().memoryFrame().scope();
```

Canonical snapshots were already built from the accepted `SessionAccessClaim`, so this preserves verified shared scope. Legacy rollback retains its existing personal scope behavior.

`ChatResultPersister` must pass the exact persisted CHAT content to the short-term writer. The Redis entry and MySQL event must therefore have the same content hash for the same `eventKey`.

If either receipt is null or non-durable, the writer skips that entry and logs a safe diagnostic; persistence and the user response continue.

## 7. Persistence and migration

Add Flyway migration `V5__message_event_short_term_scope_index.sql`:

```sql
CREATE INDEX idx_message_event_short_term_scope_id
    ON message_event (channel, session_key, user_id, event_type, deleted, id);
```

The shared-scope query may use the left prefix `(channel, session_key, event_type, deleted, id)` through this index. No data rewrite is required because `event_key` is already unique for newly written CHAT events.

## 8. Failure handling

- Redis read/write/script failure: log a safe warning; return durable rows when available and keep the chat request alive.
- MySQL durable read failure: do not mark Redis as reconciled; use only existing Redis projection and record a trace warning.
- Local fallback receipt: do not throw from short-term projection; do not use it as a cross-instance memory position.
- Duplicate durable event key: the MySQL unique key remains the primary idempotency fence; Redis v2 event-key membership is the secondary projection fence.
- Scope mismatch: no fallback to a wider query. The typed reader queries only the given `MemoryScope`.

## 9. Security and consistency properties

After this phase:

- A personal user cannot retrieve another user's rows even when session keys match.
- A channel cannot retrieve another channel's rows even when session keys match.
- A verified shared session reads its accepted shared scope, not a reconstructed personal scope.
- The same durable event appears at most once in Redis v2 and at most once in a `MemoryFrame`.
- A partial Redis loss cannot omit a durable event from a newly built canonical snapshot.
- No local negative event ID is represented as durable or sent to Redis.

## 10. Test strategy

### Event read and scope tests

- personal scope filters by channel, session, user, CHAT, and allowed role;
- shared scope filters by channel and session but retains allowed participants;
- same session key in another channel is absent;
- local fallback is marked `LOCAL_FALLBACK`.

### Redis v2 tests

- same event key with a different timestamp creates one entry;
- trim removes both order and payload data;
- recovery replay is idempotent;
- entries read in durable ID order.

### Coordinator and writer tests

- non-empty Redis missing the newest MySQL event returns a merged result in the current frame and repairs Redis;
- durable entry overrides a stale Redis payload with the same event key;
- shared canonical snapshot writes to shared scope;
- legacy rollback writes to personal scope;
- non-durable receipts are skipped without interrupting persistence;
- Redis and MySQL content for a terminal assistant event match exactly.

### Integration and regression tests

- Flyway migration applies to the integration database;
- snapshot reuse from the preceding phase remains unchanged;
- full `mvn -q test` passes with MySQL and Redis available.

## 11. Delivery sequence

1. Add the typed scope-aware read contract and Flyway index.
2. Implement Redis v2 identity-safe projection with focused tests.
3. Add read-through reconciliation and memory trace behavior.
4. Correct canonical scope/content parity in the persistence writer.
5. Run focused, integration, and full suites; document verification; merge into the parent branch and update PR #45.

## 12. Deferred follow-up

This phase deliberately leaves two future pieces separate:

1. a durable cross-instance journal/outbox for accepting chat turns while MySQL is down;
2. semantic extraction, long-term ranking, project-memory budgets, and confirmation-decision replay.
