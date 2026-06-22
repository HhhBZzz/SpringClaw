# Unified Runtime Memory and Context Design

**Status:** Approved design direction on 2026-06-22; written specification pending final user review.

## 1. Goal

Refactor SpringClaw's current memory module into an explicit
`Write -> Manage -> Read` system, then make one immutable
`ContextSnapshot` the only memory view used by a run.

The design keeps the current storage products:

- MySQL for authoritative facts, audit, versions, and recovery;
- Redis ordinary data structures for hot short-term memory;
- Redis VectorStore for a rebuildable semantic index;
- Markdown plus Git for reviewed project and procedural memory.

The problem is not the choice of database. The problem is that current memory
responsibilities are split across `ContextAssembler`, `VectorMemoryService`,
`SemanticMemoryAdvisor`, `MessageChatMemoryAdvisor`, `MemoryBankService`, and
`AgentLearningService`, without one lifecycle, one authorization boundary, or one
retrieval result.

## 2. Current-state findings

### 2.1 Existing memory layers

| Current source | Intended role | Current problem |
|---|---|---|
| `message_event` | ordered conversation and audit facts | read on the hot model path; some reads use only `sessionKey` |
| `AgentSession` | session metadata | `getOrCreate` returns an existing `sessionKey` without validating its owner |
| `VectorMemoryService` | session/user semantic recall | stores raw turn fragments; no durable memory record, version, conflict, or expiry |
| local vector fallback | temporary availability fallback | process-local and cannot be treated as long-term memory |
| `MemoryBankService` | project memory | concatenates heterogeneous files, then truncates one global string |
| `agent-learnings.md` | reviewed experience/procedural memory | new captured failures default to `active` and may affect later runs before review |
| `ContextAssembler` | prompt-oriented memory assembly | retrieves event and semantic memory before canonical snapshot creation |
| `SemanticMemoryAdvisor` | model-call augmentation | performs a second semantic retrieval that may disagree with the first |
| `MessageChatMemoryAdvisor` | optional recent conversation injection | can perform a second event-history read |
| `LegacyRunContextAdapter` | Phase 2B snapshot translation | observes context after retrieval and cannot recover separately typed memory fields |

### 2.2 Consequences

- One run can receive two different semantic memory views.
- Advisor-wrapped model calls can consume duplicate short-term and semantic
  context.
- A session-key collision can expose another user's history before ownership is
  validated.
- Raw conversation fragments accumulate as if they were stable semantic facts.
- Old or conflicting memories remain searchable without an authoritative active
  version.
- Memory retrieval cannot explain why an item was selected or omitted.
- Redis vector data cannot be rebuilt from one curated source of truth.
- Memory Bank truncation can discard a critical rule because a less important
  source consumed the shared character budget first.

## 3. Alternatives considered

### 3.1 Replace the stack with Mem0, Zep, MemGPT, or a graph database

Rejected for this phase.

These products add another runtime, storage model, and operational boundary before
SpringClaw has stable memory ownership or evaluation data. MemGPT-style virtual
context also introduces silent orchestration and summarization failure modes.
Graph memory is not justified until real workloads require multi-hop entity
reasoning.

### 3.2 Keep the current module and only remove duplicate Advisor retrieval

Rejected.

This would reduce duplicate calls but would not fix session authorization,
long-term record provenance, conflicts, versioning, index recovery, or forgetting.

### 3.3 Keep the storage products and introduce a canonical memory core

Selected.

This preserves existing infrastructure while adding typed memory records,
scope-enforced access, lifecycle management, a rebuildable index, and one retrieval
frame per run.

## 4. Scope and phase boundaries

### Phase 3A1 — Memory core and storage authority

- introduce typed memory records and lifecycle states;
- introduce MySQL `memory_record` and `memory_index_outbox`;
- introduce a Redis short-term session window;
- make semantic vector entries projections of durable memory records;
- make session/shared-channel access policy explicit;
- retain compatibility façades for existing callers.

### Phase 3A2 — Canonical retrieval and MemoryFrame

- introduce one `MemoryCoordinator`;
- retrieve each authorized layer once;
- apply scope filters, conflict/version filters, ranking, and per-layer budgets;
- emit one immutable `MemoryFrame` and retrieval trace.

### Phase 3A3 — Canonical ContextSnapshot ownership

- introduce `ContextSnapshotFactory`;
- create the snapshot at `CREATED -> CONTEXT_READY`;
- adapt the snapshot into legacy `AssembledContext` and `ContextInjection`;
- change Advisors to project the saved snapshot only;
- forbid engine-specific, retry-specific, and Advisor-side memory retrieval.

### Deferred to Phase 5B

- moving the terminal conversation/memory invocation owner out of
  `ChatResultPersister`;
- LLM-based extraction of stable user facts from every terminal run;
- advanced clustering and automatic memory consolidation;
- automatic promotion from episodic to semantic memory;
- large-scale archival and offline index compaction.

Phase 3A may persist deterministic episodic records and manually/review-approved
semantic or procedural records. It does not automatically promote every statement
made in a conversation into a stable user fact.

## 5. Canonical memory taxonomy

### 5.1 Working memory

Current-run state:

- accepted request;
- current goal and attempt;
- selected strategy;
- tool observations and references;
- pending confirmation;
- bounded intermediate execution notes.

Owner: `RunState`, ordered run events, and strategy-local immutable views.

Working memory is not placed in the long-term vector index. Large tool results are
offloaded to a file or artifact reference; the run stores only identity, summary,
hash, size, and access-controlled location.

### 5.2 Short-term memory

Recent ordered messages for one session:

- user and assistant chat messages;
- stable timestamps and event IDs;
- bounded count and TTL;
- no semantic similarity search.

Primary hot store: Redis.

Recovery source: MySQL `message_event`.

### 5.3 Episodic memory

What happened in a previous run:

- request and outcome summary;
- selected strategy;
- important tool outcomes;
- evidence and failure codes;
- timestamp and source run/event references.

Primary store: MySQL `memory_record`.

Episodic memory may be indexed for semantic retrieval, but remains tied to its
original time and evidence.

### 5.4 Semantic memory

Stable, de-contextualized facts:

- explicit user preferences;
- durable environment facts;
- reviewed historical decisions;
- stable project facts that are not shared knowledge-source documents.

Primary store: MySQL `memory_record`.

Search projection: Redis VectorStore.

Semantic memory must have provenance, confidence, validity, and version state. A
new conflicting fact supersedes an old record instead of silently coexisting with
it.

### 5.5 Procedural and project memory

How SpringClaw should behave:

- architecture decisions;
- project workflow and commands;
- reviewed Agent learnings;
- reusable Skills and safe operating rules.

Primary store: Markdown plus Git.

Only `approved` or explicitly `active` reviewed learning rules enter runtime
context. Newly captured failures start as `candidate`.

Shared knowledge sources such as company policy or product documentation remain a
separate RAG/Knowledge Source domain. They must not be written into personalized
user memory.

## 6. Storage design

### 6.1 MySQL remains authoritative

Existing tables remain authoritative for raw facts:

- `message_event`;
- canonical run state/event data when durable lifecycle storage is introduced;
- tool invocation, proposal, trace, and audit records.

The new `memory_record` table stores curated memory, not a duplicate copy of every
raw event.

Logical fields:

```text
id
memory_id                 stable external identifier
memory_type               EPISODIC | SEMANTIC | PROCEDURAL
scope_type                PERSONAL_SESSION | SHARED_SESSION | USER | PROJECT
scope_id                  canonical scope identifier
owner_user_id             nullable only for shared/project scope
content                   normalized memory text
content_hash              deterministic normalized hash
summary                   bounded display/retrieval summary
source_run_id
source_event_ids_json
evidence_refs_json
tags_json
importance                0.0 .. 1.0
confidence                0.0 .. 1.0
status                    CANDIDATE | ACTIVE | SUPERSEDED | EXPIRED | REJECTED
valid_from
valid_until
supersedes_memory_id
version
index_version
created_at
updated_at
deleted
```

Required constraints:

- `memory_id` is unique;
- `(scope_type, scope_id, content_hash, version)` is unique;
- only `ACTIVE` memory is eligible for normal retrieval;
- `SUPERSEDED`, `EXPIRED`, and `REJECTED` records remain queryable for audit;
- an update that changes semantic meaning creates a new version;
- `source_run_id` or source event references are required for automatically
  generated records;
- project/procedural memory originating in Git stores its repository path and
  content hash in evidence references.

### 6.2 Index outbox

`memory_index_outbox` makes MySQL-to-vector synchronization retryable and
observable.

Logical fields:

```text
id
event_id
memory_id
memory_version
operation                  UPSERT | DELETE
status                     PENDING | CLAIMED | SUCCEEDED | FAILED
attempts
available_at
claimed_at
last_error
created_at
updated_at
```

The memory-record transaction writes the record and outbox event together. The
worker may retry indexing without duplicating the logical memory because the
vector identity is `memory_id + version`. Supersede, expire, reject, and delete
operations create a `DELETE` event for the previously active vector.

Redis VectorStore is therefore a derived index, never the sole copy of a memory.

### 6.3 Redis short-term window

One logical list exists per authorized session scope:

```text
springclaw:memory:short:v1:{scopeHash}
```

`scopeHash` is derived from normalized scope type, channel, session key, and
authorization principal. The value contains bounded records:

```text
eventId
requestId
role
userId
content
occurredAt
```

The store contract must provide an idempotent append by `eventId`. The Redis
implementation performs append, duplicate rejection, list trim, and TTL refresh in
one atomic operation.

Initial defaults:

- maximum 40 messages;
- seven-day sliding TTL;
- maximum 4,000 normalized characters per message;
- Redis miss or corruption falls back to an owner-filtered MySQL event query and
  repopulates the window.

These are configuration values with hard upper bounds, not unbounded user input.

### 6.4 Markdown sources

`MemoryBankService` stops returning one undifferentiated truncated string. A
project-memory reader returns typed source sections:

```text
PROJECT_BRIEF
CURRENT_STATE
ARCHITECTURE_DECISION
APPROVED_LEARNING
PROGRESS
USER_PREFERENCE
OTHER_REVIEWED_PROJECT_MEMORY
```

Each item includes:

```text
sourcePath
sourceType
content
contentHash
reviewStatus
updatedAt
```

`agent-learnings.md` is parsed as procedural entries. Only `approved` and
explicitly retained `active` entries are eligible. New automatic captures use
`candidate`, not `active`.

## 7. Scope and authorization

Memory access uses a typed `MemoryScope`, not loose `sessionKey` and `userId`
strings:

```text
scopeType
scopeId
channel
sessionKey
requestingUserId
authorizationPrincipal
crossSessionUserMemoryAllowed
```

Rules:

- API and personal/P2P sessions use `PERSONAL_SESSION`; the persisted session owner
  must equal the requesting user before any event or memory read.
- Feishu group sessions use `SHARED_SESSION`; group history is shared within that
  channel session, while cross-session personal user memory is disabled.
- `USER` memory requires an authenticated matching user.
- `PROJECT` memory is repository-scoped and contains no user-private facts.
- scope filters are injected by the memory data-access layer; callers cannot omit
  them.
- authorization failure occurs before MySQL event reads, Redis window reads,
  vector search, or Markdown user-preference reads.

For compatibility with the existing `ContextSnapshot.sessionOwnerUserId` field:

- personal sessions store the persisted owner user ID;
- shared sessions store the reserved authorization principal
  `shared:<channel>:<sessionKey>`.

The snapshot source summary also records the explicit scope type.

## 8. Write–Manage–Read lifecycle

### 8.1 Write

The deterministic Phase 3A write path is:

```text
terminal conversation owner
  -> existing raw MySQL event persistence
  -> short-term Redis window append
  -> deterministic episodic memory record when policy allows
  -> memory index outbox
```

Automatic semantic fact extraction is not performed synchronously on the response
path.

Idempotency uses source run/event identity, not generated text. Retrying terminal
persistence cannot create a second logical episodic memory.

### 8.2 Manage

`MemoryManagementService` owns:

- duplicate detection by normalized hash and provenance;
- active-version selection;
- supersede and conflict transitions;
- expiry;
- candidate review transitions;
- index outbox creation;
- bounded retention policies;
- audit events for every state change.

Initial control policy is deterministic and heuristic:

- no reinforcement-learned memory controller;
- no autonomous deletion of raw audit facts;
- automatic memories without external evidence remain `CANDIDATE`;
- expired or superseded records leave normal retrieval immediately but remain
  auditable;
- safety-related evidence is retained even when excluded from model context;
- project learning rules require reviewable evidence references.

### 8.3 Read

`MemoryCoordinator.readFrame(request)` performs exactly one authorized retrieval
per run.

Order:

1. resolve and authorize `MemoryScope`;
2. read Redis short-term window, falling back to MySQL;
3. read active episodic and semantic candidates under hard scope filters;
4. read typed project/procedural items;
5. exclude expired, superseded, rejected, and incompatible versions;
6. rank candidates;
7. apply independent per-layer budgets;
8. produce `MemoryFrame` and retrieval trace.

The initial candidate signals are:

- structured scope/type/tag matches from MySQL;
- dense similarity candidates from Redis VectorStore;
- recent and important active records from MySQL.

The first implementation uses reciprocal-rank fusion across available candidate
lists, followed by deterministic adjustment:

```text
finalScore =
    rrfScore
    * importance
    * confidence
    * timeDecay
```

`timeDecay` applies to episodic and preference memory. Reviewed project and
procedural rules do not decay automatically; they are versioned or explicitly
disabled.

No memory is admitted solely because its embedding is similar.

## 9. MemoryFrame

`MemoryFrame` is the immutable result of one authorized retrieval:

```text
runId
scope
workingMemoryRefs
shortTermTurns
episodicItems
semanticFacts
proceduralRules
projectItems
sourceSummary
omissions
capturedAt
frameHash
```

Every item contains:

```text
memoryId or source identity
memoryType
scope
content
evidenceRefs
importance
confidence
score
version
```

`omissions` records budget, authorization, conflict, expiry, duplicate, and
low-score exclusions without storing private excluded content in ordinary trace
payloads.

Initial context-budget allocation is percentage-based:

| Layer | Budget |
|---|---:|
| short-term turns | 35% |
| episodic memory | 15% |
| semantic facts | 20% |
| project memory | 20% |
| procedural/learning rules | 10% |

Unused budget may flow to another layer, but no layer may exceed 50% of the total
memory budget. The current question, system prompt, and tool observations are
budgeted separately from persistent memory.

## 10. ContextSnapshot ownership

`ContextSnapshotFactory` becomes the only producer of a canonical context snapshot.

It:

1. receives the accepted run and authenticated principal;
2. authorizes the session scope;
3. resolves role, allowed capabilities, provider metadata, and system prompt;
4. asks `MemoryCoordinator` for one `MemoryFrame`;
5. builds one complete `ContextSnapshot`;
6. commits `CREATED -> CONTEXT_READY` through `RunCoordinator`.

The snapshot hash is calculated from canonical identity, authorization scope,
prompt, capabilities, provider metadata, and ordered memory item identities,
versions, hashes, and content. `capturedAt` is stored but excluded from the content
hash.

Retries, confirmation resumes, stream projections, and sub-round question rewrites
reuse the saved snapshot. A strategy may derive a new prompt from the same memory
items, but cannot perform another long-term retrieval.

## 11. Legacy compatibility

Phase 3A does not combine or delete the six legacy engines.

A `LegacyContextViewAdapter` derives:

- `AssembledContext`;
- `ContextInjection`;
- legacy event/semantic strings;
- source summary metadata;

from the canonical `ContextSnapshot`.

`ChatContextFactory` temporarily remains responsible for routing-era fields needed
by Phase 3B, but it no longer owns memory retrieval or session authorization.

`ConversationAdvisorSupport` receives the saved snapshot/context view. Advisors may:

- format ordered short-term turns as Spring AI messages;
- attach saved semantic and project memory to a system prompt;
- attach source IDs for trace.

Advisors may not:

- call `MemoryService`;
- query `message_event`;
- read Redis;
- read Memory Bank files;
- change the memory set;
- independently summarize or rerank memory.

`SemanticMemoryAdvisor` is either replaced by a projection-only Advisor or reduced
to a compatibility wrapper with no `MemoryService` dependency.

## 12. Retrieval trace and observability

Every frame emits a bounded diagnostic record:

```text
runId
scopeType
shortTermSource            REDIS | MYSQL_RECOVERY | EMPTY
candidateCountsByLayer
selectedCountsByLayer
selectedMemoryIds
excludedReasonCounts
budgetCharsByLayer
vectorLatencyMs
databaseLatencyMs
totalLatencyMs
frameHash
```

The trace does not expose private memory content to users who cannot access the
underlying scope.

Required metrics:

- short-term Redis hit/miss/recovery;
- candidate and selected count by memory type;
- vector and database retrieval latency;
- stale/superseded exclusion count;
- index outbox lag and failure count;
- frame size and budget saturation;
- retrieval failure/degradation route.

## 13. Failure and degradation policy

- Session authorization failure stops context construction and model execution.
- Redis short-term failure falls back to owner-filtered MySQL events.
- VectorStore failure falls back to active MySQL episodic/semantic candidates and
  records degraded retrieval.
- MySQL memory-record failure does not silently use unscoped vector results.
- Markdown read failure omits that project source and records the omission.
- Outbox failure never rolls back an already committed authoritative memory record;
  it remains retryable.
- Memory retrieval failure cannot fabricate an empty successful snapshot when
  authorization or authoritative-store consistency is unknown.
- No delivery or Advisor failure may mutate the selected memory set for a saved
  snapshot.

## 14. Compatibility and migration

Migration uses narrow reversible steps:

1. add schema and stores without changing runtime reads;
2. add Redis short-term shadow writes and verify against MySQL;
3. route the existing `MemoryService` compatibility façade through the new memory
   core;
4. build and verify vector projections through the outbox;
5. activate `MemoryCoordinator` in shadow-read mode and compare frames;
6. activate `ContextSnapshotFactory` as the read owner;
7. replace Advisor retrieval with snapshot projection;
8. remove direct runtime reads from `ContextAssembler`,
   `SemanticMemoryAdvisor`, and `MessageEventChatMemory`.

At no point may the old and new path both inject independently retrieved memory
into one model call.

Existing API payloads, engine routing, answers, tool safety, persistence ownership,
locks, and stream termination remain unchanged during Phase 3A.

## 15. Acceptance criteria

### Authorization

- a personal session cannot be read with another user ID;
- shared group memory never includes private cross-session user memory;
- authorization runs before all memory storage reads;
- all data-access calls require a typed scope.

### Single retrieval

- each accepted run performs at most one canonical persistent-memory retrieval;
- retries and confirmation resumes reuse the same frame;
- Advisors perform zero memory, event, Redis, vector, or file reads;
- all six legacy engines receive equivalent memory content.

### Storage and recovery

- Redis short-term order matches MySQL event order;
- duplicate event delivery does not duplicate a short-term message;
- Redis loss can rebuild the window from MySQL;
- every searchable vector maps to an active durable memory version;
- vector index loss can be rebuilt from MySQL and outbox state.

### Management

- candidate learning is excluded until approved/activated;
- a new conflicting fact can supersede an old fact;
- superseded and expired memory is excluded but remains auditable;
- source/evidence identity is preserved;
- index deletion follows status invalidation.

### Retrieval quality and observability

- retrieval is deterministic for a fixed candidate set and time;
- selected and omitted counts are traceable by layer and reason;
- per-layer budgets prevent one source from consuming the entire frame;
- vector failure has a tested authoritative-store fallback;
- no unscoped fallback is permitted.

### Compatibility

- current context characterization cases remain passing unless an approved test
  explicitly changes duplicate-retrieval behavior;
- current route characterization remains unchanged;
- current answer, persistence, lock, stream, and P0 tool-safety tests remain
  passing;
- full repository tests pass with the documented local infrastructure.

## 16. Evaluation strategy

Phase 3A adds deterministic engineering evaluations before considering a learned
memory policy:

- exact retrieval of a named recent fact;
- cross-session user preference retrieval;
- no private-memory retrieval in a shared group;
- long-range episodic retrieval;
- conflicting-fact replacement;
- selective forgetting;
- short-term Redis loss and MySQL recovery;
- duplicate write and index retry;
- irrelevant-memory rejection;
- token-budget saturation.

The project should record effectiveness, latency, selected-token cost, and capacity
separately. A larger memory frame is not automatically a better result.

## 17. Explicit non-goals

Phase 3A does not introduce:

- Neo4j or another graph database;
- MemGPT-style virtual context paging;
- reinforcement-learned memory control;
- LoRA or parameterized user memory;
- autonomous deletion of raw audit history;
- automatic trust in model-generated reflection;
- general Knowledge Source/RAG migration;
- multi-agent shared-memory consensus;
- terminal persistence ownership migration.

These require measured workload evidence and separate approved plans.

## 18. Ownership and implementation handoff

Codex owns:

- memory contracts, lifecycle rules, scope/authorization design;
- MySQL schema and outbox invariants;
- `MemoryFrame`, `MemoryCoordinator`, and `ContextSnapshotFactory` core APIs;
- authorization, versioning, deterministic ranking, and recovery tests;
- phase gates and final review.

Claude may own after the implementation plan is approved:

- repository and Redis adapters;
- compatibility façade migration;
- Markdown typed-source parsing;
- Advisor projection wiring;
- constructor and fixture migration;
- focused integration tests and evidence updates.

Claude must not change canonical memory contracts, scope rules, lifecycle states,
or ContextSnapshot ownership without returning the change to the architecture
gate.

## 19. Rollback

Rollback order:

1. disable snapshot projection Advisors and restore the previous compatibility
   Advisor configuration;
2. restore legacy context assembly reads;
3. disable canonical `MemoryCoordinator` activation;
4. retain MySQL memory records and outbox rows for audit but stop workers;
5. disable Redis short-term shadow writes;
6. leave Phase 2B canonical identity and lifecycle intact.

Schema removal is not required for an operational rollback. No rollback may
reactivate two independently retrieving memory paths in the same model call.
