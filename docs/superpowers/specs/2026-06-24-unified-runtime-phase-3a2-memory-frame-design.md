# Unified Runtime Phase 3A2 MemoryFrame Design

## 1. Goal

Phase 3A2 builds the canonical memory retrieval layer and emits one immutable
`MemoryFrame` candidate per accepted run, while keeping the active Phase 2B/3A1
prompt-input path unchanged.

The objective is to prove that unified memory retrieval is authorized,
deterministic, budgeted, explainable, and regression-testable before Phase 3A3
uses it as the sole source for `ContextSnapshot` and Advisor projection.

## 2. Non-goals and hard boundaries

Phase 3A2 must not:

- activate `ContextSnapshotFactory` as the production context owner;
- add a required `MemoryFrame` field to `ContextSnapshot`;
- disable or rewrite `ContextAssembler`;
- remove `SemanticMemoryAdvisor` or `MessageChatMemoryAdvisor`;
- change model prompt shape, routing, engine selection, tool safety, stream
  termination, final-answer ownership, proposal lifecycle, or workspace guards;
- perform new automatic semantic extraction from arbitrary transcripts;
- require Redis, vector search, or MySQL to be available for default startup.

Phase 3A2 may add shadow-only components, contracts, tests, and bounded
diagnostics. Any production bean must be disabled by default or safe when its
backing storage is absent.

## 3. Current baseline from Phase 3A1

Phase 3A1 delivered:

- immutable `SessionAccessClaim` captured at run acceptance;
- typed canonical memory contracts and bounded local fallback stores;
- MySQL `memory_record` and `memory_index_outbox` authority stores;
- atomic lifecycle management for memory records and index events;
- stable message event receipts for terminal and suspension persistence;
- Redis short-term memory shadow storage and recovery;
- fenced vector projection and rebuild primitives;
- typed Markdown project memory and candidate procedural learning entries;
- disabled-by-default memory core shadow flags and local-store default wiring.

Active model input still comes from:

- `ChatContextFactory`;
- `ContextAssembler`;
- `MemoryBankService.renderSnapshot()`;
- `SemanticMemoryAdvisor`;
- optional Spring AI chat memory Advisor.

Phase 3A2 treats those as compatibility producers. They remain authoritative for
model input until Phase 3A3.

## 4. Architecture

Phase 3A2 introduces three new conceptual units:

1. `MemoryFrame` — immutable output of one authorized retrieval.
2. `MemoryCoordinator` — retrieves, ranks, budgets, deduplicates, and renders a
   frame candidate.
3. `MemoryRetrievalTrace` — bounded diagnostic record explaining included and
   omitted memory sources without leaking excluded private content.

The flow is:

```text
accepted run + SessionAccessClaim
        |
        v
MemoryCoordinator.retrieve(...)
        |
        +-- short-term source
        +-- durable memory record source
        +-- project memory source
        +-- procedural learning source
        +-- optional vector candidate source
        |
        v
MemoryFrame + MemoryRetrievalTrace
        |
        v
shadow comparison only in Phase 3A2
```

No engine or Advisor reads the `MemoryFrame` as active prompt input in Phase 3A2.

## 5. Runtime contract: MemoryFrame

`MemoryFrame` is a pure runtime contract under `com.springclaw.runtime.memory.contract`.

Fields:

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

Rules:

- `runId`, `scope`, `capturedAt`, and `frameHash` are required.
- Lists are immutable and preserve final ranking order.
- Each item carries a stable source identity:
  - memory records use `logicalMemoryId` + `memoryVersionId`;
  - short-term turns use `eventId` + `eventKey`;
  - project/procedural Markdown uses `sourcePath` + `contentHash`;
  - vector candidates use the underlying authoritative `memoryVersionId`.
- `frameHash` excludes `capturedAt`; it includes run identity, scope identity,
  ordered item source identities, versions, content hashes, scores, and omission
  categories.
- Two frames built from the same inputs in the same order must have the same
  `frameHash`.

## 6. Memory item model

Phase 3A2 should add a small common item contract instead of forcing every
source into a single persistence-row shape.

Required fields:

```text
sourceId
sourceKind
logicalMemoryId
memoryVersionId
memoryType
scopeType
scopeId
content
contentHash
evidenceRefs
importance
confidence
score
version
updatedAt
```

Applicability:

- `logicalMemoryId` and `memoryVersionId` are present for durable memory records.
- `sourceId` is always present and unique inside a frame.
- `contentHash` is always present and calculated over normalized content.
- `score` is coordinator-owned. Raw source scores may be preserved in
  `sourceSummary`, but final ranking uses the coordinator score.

## 7. Retrieval sources

### 7.1 Short-term turns

Source priority:

1. Redis `ShortTermMemoryStore` when short-term shadow is explicitly enabled and
   readable.
2. MySQL `message_event` recovery source when available.
3. Empty list when neither source is available.

Short-term retrieval must use the accepted run scope. It must not accept a
caller-supplied forged group/shared scope from REST strings.

Included roles:

- `USER`;
- terminal `ASSISTANT`;
- no `SYSTEM`, `OPAR`, proposal audit, or tool trace rows.

### 7.2 Durable episodic and semantic records

Read active memory versions from `MemoryRecordStore` for the authorized scope.

Initial classification:

- `EPISODIC` records go to `episodicItems`;
- `SEMANTIC` records go to `semanticFacts`;
- unsupported or future types are omitted with category `UNSUPPORTED_TYPE`.

When the DB-backed store is disabled, the bounded local store is a valid source.
No shared vector index write is required for local mode.

### 7.3 Procedural rules

Procedural rules come from reviewed learning entries:

- include `approved`;
- include legacy `active` only for existing explicitly retained sections;
- exclude `candidate`, `disabled`, `rejected`, and `superseded`.

The coordinator must record excluded learning counts by status, not the excluded
content.

### 7.4 Project memory

Project memory comes from `ProjectMemorySource`.

Typed source mapping from Phase 3A1 remains:

- `PROJECT_BRIEF`;
- `CURRENT_STATE`;
- `ARCHITECTURE_DECISION`;
- `APPROVED_LEARNING`;
- `PROGRESS`;
- `USER_PREFERENCE`;
- `OTHER_REVIEWED_PROJECT_MEMORY`.

Only reviewed project files are read. Empty files are ignored.

### 7.5 Optional vector candidates

Vector search is optional in Phase 3A2.

If present, vector candidates may contribute candidate `memoryVersionId` values,
but the coordinator must re-read the authoritative active version from
`MemoryRecordStore` before including content. Stale vector hits are omitted with
category `STALE_VECTOR_HIT`.

If vector infrastructure is absent or disabled, retrieval still succeeds with a
bounded omission category `VECTOR_UNAVAILABLE`.

## 8. Authorization

`MemoryCoordinator` receives an accepted run identity and an immutable
`SessionAccessClaim`.

Authorization rules:

- `PERSONAL` claim may read only the personal scope for the accepted
  session/channel/user.
- `SHARED_SESSION` claim may read shared-session scope only when origin is trusted
  webhook/ingress evidence.
- REST authenticated API calls always remain `PERSONAL`, even when the session key
  resembles a group string.
- A source that cannot prove it is scoped to the claim is skipped and recorded as
  `AUTHORIZATION_SCOPE_MISMATCH`.

The coordinator must not reconstruct authorization from raw request strings.

## 9. Budgeting and ranking

Initial memory budget is character-based. Token counting may be added later, but
Phase 3A2 uses deterministic normalized character limits.

Default allocation:

| Layer | Share |
|---|---:|
| short-term turns | 35% |
| episodic memory | 15% |
| semantic facts | 20% |
| project memory | 20% |
| procedural rules | 10% |

Rules:

- unused budget may flow to other layers;
- no layer may consume more than 50% of the total memory budget;
- item truncation must be deterministic and recorded as `BUDGET_TRUNCATED`;
- duplicate content hashes across layers are included once and recorded as
  `DUPLICATE_CONTENT`;
- exact conflict handling prefers higher trust in this order:
  project decision > procedural approved rule > semantic fact > episodic item >
  short-term turn.

## 10. Retrieval trace

`MemoryRetrievalTrace` is diagnostic, not prompt input.

Required fields:

```text
runId
scopeType
scopeId
frameHash
sourceCounts
includedCounts
omissionCounts
sourceWarnings
capturedAt
```

Trace rules:

- include counts and source IDs for included items;
- do not include excluded private content;
- bound warnings to a fixed maximum count;
- represent infrastructure degradation as warnings, not thrown exceptions, unless
  the accepted run claim itself is invalid.

## 11. Shadow comparison in Phase 3A2

Phase 3A2 may compare the `MemoryFrame` candidate with the current
`ContextAssembler` output.

Comparison output:

- current legacy event/semantic/project text presence;
- frame layer counts;
- omitted categories;
- whether active learning counts match;
- whether project memory source hashes changed.

Comparison must not alter prompt input. It is valid for comparison to show
differences; Phase 3A2 does not require byte-identical output.

## 12. Configuration

Add disabled-by-default Phase 3A2 activation flags:

```yaml
springclaw:
  memory:
    frame:
      enabled: ${SPRINGCLAW_MEMORY_FRAME_ENABLED:false}
      shadow-compare-enabled: ${SPRINGCLAW_MEMORY_FRAME_SHADOW_COMPARE_ENABLED:false}
      max-chars: ${SPRINGCLAW_MEMORY_FRAME_MAX_CHARS:6000}
      trace-max-warnings: ${SPRINGCLAW_MEMORY_FRAME_TRACE_MAX_WARNINGS:20}
```

`springclaw.memory.frame.enabled=false` means:

- no active retrieval is required for normal chat startup;
- tests can instantiate coordinator directly;
- no prompt or Advisor behavior changes.

## 13. Testing strategy

Required tests:

- `MemoryFrameContractTest`
  - rejects blank run/scope/hash;
  - copies all collections immutably;
  - computes stable hashes independent of `capturedAt`.
- `MemoryCoordinatorTest`
  - assembles all layers from in-memory sources;
  - enforces personal scope;
  - excludes candidate/rejected learning;
  - records vector-unavailable and budget omissions;
  - deduplicates by content hash.
- `MemoryFrameShadowComparisonTest`
  - proves comparison is read-only;
  - proves current `ContextAssembler` output remains the active model input.
- Compatibility gates:
  - `ContextPropagationCharacterizationTest`;
  - `RuntimeRouteCharacterizationTest`;
  - `FinalAnswerOwnershipCharacterizationTest`;
  - `TransportParityCharacterizationTest`;
  - `PromptInjectionTest`;
  - `ToolRuntimeAspectInterceptionIT`;
  - `WorkspaceGitGuardTest`.

Required outcome:

- Phase 3A2 focused tests pass;
- compatibility gates pass;
- full suite passes with no new failures/errors/skips;
- active prompt shape remains unchanged when frame flags are false.

## 14. Rollback

Rollback order:

1. set `SPRINGCLAW_MEMORY_FRAME_ENABLED=false`;
2. set `SPRINGCLAW_MEMORY_FRAME_SHADOW_COMPARE_ENABLED=false`;
3. revert the shadow comparison wiring;
4. revert `MemoryCoordinator`;
5. revert `MemoryFrame` contracts.

Phase 3A1 storage and shadow persistence can remain in place after Phase 3A2
rollback.

## 15. Exit criteria

Phase 3A2 is complete when:

- `MemoryFrame` and `MemoryRetrievalTrace` contracts exist and are tested;
- `MemoryCoordinator` can assemble a deterministic authorized frame from Phase
  3A1 sources;
- frame retrieval is disabled by default and safe without MySQL/Redis/vector;
- optional shadow comparison proves no active prompt-input change;
- focused, compatibility, and full suites pass;
- the collaboration ledger records commits, owners, test counts, limitations, and
  rollback order.

Phase 3A3 may begin only after Phase 3A2 completes and a separate implementation
plan is approved.
