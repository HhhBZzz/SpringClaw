# Runtime Context Identity Convergence Design

**Date:** 2026-07-11
**Status:** Implemented and verified
**Branch:** `codex/runtime-context-identity-convergence`
**Base:** `7a77ce5` (`codex/flyway-schema-migration`)

## 1. Objective

Make the accepted canonical `RunState` the only identity authority after request acceptance, and guarantee that one run uses exactly one persisted `ContextSnapshot` for initial execution, retries, and later resume paths.

This phase fixes two related correctness boundaries:

1. `ChatContextFactory` currently derives session, user, role, routing inputs, and persistence identity from the mutable `ChatRequest` before loading the accepted run.
2. `ChatContextFactory` currently retrieves memory and constructs a new snapshot on every build even when the canonical run already owns a snapshot.

The phase does not merge execution engines or redesign memory retrieval. It makes the existing execution paths consume one authoritative identity and one authoritative context snapshot.

## 2. Scope

### 2.1 In scope

- Load and validate the accepted `RunState` before creating an `AgentSession`, resolving role/tool policy, routing, system prompt, provider metadata, or memory.
- Reject any `AcceptedChatCommand` whose request identity or immutable request fields differ from the accepted run.
- Derive `ChatContext.session`, `channel`, `userId`, `roleCode`, original message, response mode, and request ID from the accepted run.
- Create a `ContextSnapshot` only while the run is `CREATED` and has no snapshot.
- Reuse the exact canonical snapshot for every non-terminal run that already has one.
- Return the snapshot persisted by `RunCoordinator`, not an uncommitted locally-created candidate.
- Fail closed when a run state violates the snapshot lifecycle invariant.
- Detect a provider/model mismatch when reusing a frozen snapshot instead of silently claiming that the current provider matches the frozen metadata.
- Preserve the explicit legacy rollback path when `springclaw.context.snapshot.factory-enabled=false`.
- Add focused tests for identity mismatches, role freezing, snapshot reuse, race handling, and transport parity.

### 2.2 Out of scope

- Fixing short-term event IDs, Redis/MySQL reconciliation, or project-memory budgeting.
- Changing `AgentSession` database uniqueness from `session_key` to a composite owner key.
- Merging or deleting any of the six legacy engines.
- Redesigning `AgentDecision`, deterministic risk floors, tool authorization, or confirmation resume.
- Reconstructing a historical model client after provider configuration changes. This phase detects the mismatch and fails closed; a provider-registry resume API belongs to a later phase.
- Changing lifecycle persistence defaults.
- Changing the contents or ranking algorithm of `MemoryFrame`.

## 3. Current behavior and failure modes

The current canonical path in `ChatContextFactory.build(...)` performs work in this order:

1. Create or load `AgentSession` from the incoming request.
2. Resolve the current role and allowed tool packs from the incoming request user.
3. Run decision and routing services.
4. Build the current system prompt and select the active provider.
5. Load the accepted `RunState` only when constructing the snapshot request.
6. Retrieve memory and create a new `ContextSnapshot` unconditionally.
7. Ask `CanonicalContextReadyProjector` to project the snapshot.
8. Ignore the projector's returned canonical state and give the locally-created snapshot to the engine.

This allows a single `ChatContext` to contain conflicting facts:

- accepted run identity inside `ContextSnapshot`;
- request identity inside `ChatContext`, `AgentSession`, routing, role, and persistence;
- canonical stored snapshot inside `RunState`;
- newly-retrieved snapshot used by the engine.

It also violates the `ContextSnapshot` contract that retries and confirmation resumes reuse the original snapshot.

## 4. Alternatives considered

### 4.1 Validation-only patch

Add equality checks at the start of `ChatContextFactory`, then leave the remaining construction order unchanged.

**Advantages:** smallest diff and easiest rollback.
**Disadvantages:** still re-retrieves memory, still uses a local snapshot candidate, and leaves snapshot ownership distributed across factory and projector.

This option is rejected because it fixes forged request fields but not replay consistency.

### 4.2 Normalize requests at each transport

Have HTTP, SSE, MQ, webhook, and scheduled-task adapters rebuild a `ChatRequest` from `RunState` before calling `ChatService`.

**Advantages:** transport code can reject malformed messages early.
**Disadvantages:** duplicates security logic across transports, leaves internal callers unprotected, and makes future transports repeat the same rules.

This option is rejected as the primary boundary. Transports may retain defensive checks, but correctness must not depend on them.

### 4.3 Central accepted-run resolver plus canonical snapshot resolver

Introduce one component that validates and exposes accepted identity and another component that creates or reuses the canonical snapshot. `ChatContextFactory` calls both before constructing legacy views.

**Advantages:** one authority boundary, testable lifecycle semantics, safe reuse, transport independence, and small enough changes to avoid engine refactoring.
**Disadvantages:** adds two focused runtime-bridge components and requires restructuring `ChatContextFactory.build(...)`.

This is the selected approach.

## 5. Proposed components

### 5.1 `AcceptedRunContextResolver`

Create `com.springclaw.runtime.bridge.AcceptedRunContextResolver`.

Its public contract is:

```java
public final class AcceptedRunContextResolver {
    public AcceptedRunContext resolve(String runId, ChatRequest request);
}

public record AcceptedRunContext(RunState runState) {
    public String runId();
    public String sessionKey();
    public String channel();
    public String userId();
    public String roleCode();
    public String originalMessage();
    public String responseMode();
    public SessionAccessClaim sessionAccessClaim();
}
```

The resolver loads `RunState` from `RunStateRepository` and validates all immutable request fields:

| Request field | Canonical comparison |
|---|---|
| supplied run ID | `runState.runId()` and `runState.requestId()` |
| `sessionKey` | `runState.sessionKey()` |
| normalized `channel` | `runState.channel()` |
| `userId` | `runState.userId()` |
| message | `runState.originalMessage()` |
| normalized response mode | `runState.responseMode()` |

The resolver also verifies that `SessionAccessClaim` still matches the run identity. Contract constructors already enforce this, but the boundary retains an explicit assertion so deserialization or legacy persistence corruption fails close to ingress.

Terminal runs cannot create a new `ChatContext`. They fail with a typed `CanonicalRunContextException` using code `RUN_ALREADY_TERMINAL`.

Mismatch failures use `CanonicalRunContextException` with code `ACCEPTED_REQUEST_MISMATCH`. Error messages identify the mismatched field but do not include message content or credentials.

### 5.2 `CanonicalContextSnapshotResolver`

Create `com.springclaw.runtime.bridge.CanonicalContextSnapshotResolver`.

Its public contract is:

```java
public final class CanonicalContextSnapshotResolver {
    public ContextSnapshot resolve(
            AcceptedRunContext accepted,
            Supplier<ContextSnapshot> snapshotCandidate
    );
}
```

The state behavior is:

| Run state | Behavior |
|---|---|
| `CREATED`, no snapshot | Evaluate the supplier once, project `CONTEXT_READY`, return the snapshot from the committed `RunState` |
| `CREATED`, snapshot present | Fail `CANONICAL_SNAPSHOT_INVARIANT` |
| non-terminal state, snapshot present | Return `runState.contextSnapshot()` without evaluating the supplier |
| non-terminal state after `CREATED`, no snapshot | Fail `CANONICAL_SNAPSHOT_INVARIANT` |
| terminal state | Rejected by `AcceptedRunContextResolver` |

For a concurrent `CREATED -> CONTEXT_READY` race, both callers may construct candidates, but only the committed state is authoritative. If projection loses a revision race, the resolver reloads the run and returns its stored snapshot when the state is now `CONTEXT_READY` or later. It never returns the losing local candidate.

The resolver is the only caller that may invoke `ContextSnapshotFactory.create(...)` from `ChatContextFactory`.

### 5.3 `CanonicalRunContextException`

Create `com.springclaw.runtime.bridge.CanonicalRunContextException` with a stable code and a safe message.

Supported codes in this phase:

```text
ACCEPTED_REQUEST_MISMATCH
RUN_ALREADY_TERMINAL
CANONICAL_SNAPSHOT_INVARIANT
CANONICAL_PROVIDER_MISMATCH
```

The exception remains a runtime exception so existing transport error handling continues to work. Tests assert codes rather than parsing free-form messages.

### 5.4 `ChatContextFactory` orchestration

Restructure canonical mode in this order:

1. Resolve `AcceptedRunContext` from `acceptedRunId` and the incoming request.
2. Create/load `AgentSession` using accepted `sessionKey`, `channel`, and `userId`.
3. Use accepted `roleCode`; do not call `AuthService.resolveRoleByUserId(...)` for canonical mode.
4. If the accepted run already contains a snapshot, reuse it before invoking memory, skill, prompt, or routing dependencies that define snapshot content.
5. If the run is `CREATED`, compute routing, decision, allowed capabilities, system prompt, and provider metadata from accepted identity and original message, then lazily create the snapshot candidate.
6. Adapt the canonical snapshot to the legacy `AssembledContext` and `ContextInjection` views.
7. Build `ChatContext` using accepted identity and snapshot fields.

For a reused snapshot:

- `effectiveUserMessage` comes from `snapshot.effectiveMessage()`;
- `systemPrompt` comes from `snapshot.systemPrompt()`;
- role, session, channel, user, and request ID come from accepted `RunState`;
- allowed capability metadata comes from the snapshot;
- the active provider ID and model must match `snapshot.providerSnapshot()` when those values are present.

Routing and legacy `AgentDecision` may be recomputed from the frozen effective message during this phase because the canonical `ExecutionDecision` reverse adapter does not yet preserve `requiresConfirmation`. The recomputation must use accepted identity, never incoming request identity. Freezing and restoring the complete decision is a separate phase because it requires an explicit canonical confirmation field and migration semantics.

### 5.5 Legacy rollback mode

When `springclaw.context.snapshot.factory-enabled=false`, retain the existing legacy `ContextAssembler` path.

The rollback path does not gain canonical snapshot reuse in this phase. It must remain clearly named and covered by the existing rollback test so operators understand that disabling canonical snapshots also disables replay guarantees.

## 6. Data flow

### 6.1 First execution

```text
AcceptedChatCommand
    -> AcceptedRunContextResolver
    -> accepted RunState (CREATED)
    -> routing/decision/prompt from accepted identity
    -> ContextSnapshotFactory (one candidate)
    -> CanonicalContextSnapshotResolver
    -> RunCoordinator.contextReady
    -> committed RunState.contextSnapshot
    -> LegacyContextViewAdapter
    -> ChatContext
    -> EngineSelector
```

### 6.2 Retry or resume

```text
AcceptedChatCommand
    -> AcceptedRunContextResolver
    -> accepted non-terminal RunState with snapshot
    -> CanonicalContextSnapshotResolver
    -> existing RunState.contextSnapshot
    -> no MemoryCoordinator retrieval
    -> LegacyContextViewAdapter
    -> ChatContext
```

## 7. Error handling

- Identity mismatch is deterministic and non-retryable for the supplied command.
- Terminal-run rebuild is deterministic and non-retryable.
- Missing snapshot after the run has advanced beyond `CREATED` is a lifecycle corruption error and must not fall back to new retrieval.
- A provider/model mismatch on snapshot reuse fails closed. The runtime must not silently label a different provider as the frozen provider.
- Missing canonical beans while canonical mode is enabled remains a startup/wiring error.
- Legacy rollback mode remains available only through the existing explicit feature flag.

No error path may build a second snapshot as a fallback.

## 8. Concurrency and idempotency

- The accepted run ID remains the request correlation ID.
- `RunLifecycleStore` revision fencing remains the commit authority.
- Snapshot candidates are side-effect-free reads until projection; only the committed snapshot is returned.
- A race loser reloads canonical state rather than retrying memory retrieval indefinitely.
- Repeated `resolve(...)` calls after `CONTEXT_READY` do not invoke the candidate supplier.
- The design does not add a process-local lock; correctness relies on canonical compare-and-set semantics so it works across instances.

## 9. Security properties

After this phase:

- A caller cannot combine another run ID with a different session, user, channel, message, or response mode.
- Role changes after acceptance do not alter the role captured for that run.
- Memory scope and tool/persistence identity originate from the same accepted run.
- A retry cannot observe newly-created memory through a newly-built snapshot.
- Error messages do not echo prompts, API keys, provider credentials, or full serialized snapshots.

This phase does not replace final tool authorization. `ToolRuntimeAspect` remains the last execution guard.

## 10. Test strategy

### 10.1 Accepted-run boundary tests

Add tests proving that each mismatch is rejected independently:

- run ID;
- session key;
- channel after normalization;
- user ID;
- original message;
- response mode after normalization.

Add a test proving accepted `roleCodeAtAcceptance` wins when `AuthService` would now return another role.

### 10.2 Snapshot lifecycle tests

Add tests proving:

- `CREATED` creates and commits one snapshot;
- the returned object is the committed snapshot;
- `CONTEXT_READY` reuses the same object/hash and never invokes the candidate supplier;
- later non-terminal states reuse the stored snapshot;
- a missing snapshot after `CREATED` fails closed;
- terminal states cannot build context;
- a projection race reloads and returns the winner's snapshot;
- provider/model mismatch fails closed.

### 10.3 Factory interaction tests

Add Mockito interaction assertions proving that snapshot reuse does not call:

- `ContextSnapshotFactory.create(...)`;
- `MemoryCoordinator.retrieve(...)` indirectly through the factory;
- `SoulPromptService.buildSystemPrompt(...)` to replace the frozen prompt;
- current-role resolution for canonical identity.

Retain a test proving explicit rollback still uses `ContextAssembler`.

### 10.4 Transport characterization

- Sync and SSE continue to pass the controller-accepted request unchanged.
- MQ retains its existing acceptance equality guard.
- Webhook personal and shared claims both reach the same resolver.
- Scheduled tasks retain `SCHEDULED_TASK` acceptance origin.

The transport tests should assert shared resolver behavior rather than duplicate field-comparison implementations.

### 10.5 Verification commands

```bash
mvn -q -Dtest=AcceptedRunContextResolverTest,CanonicalContextSnapshotResolverTest test
mvn -q -Dtest=ChatContextFactoryCanonicalOwnershipTest,ChatContextFactoryCanonicalSnapshotTest,ChatContextFactoryCanonicalLifecycleProjectionTest test
mvn -q -Dtest=ChatMessageConsumerTest,WebhookRouterServiceTest,TaskExecutionServiceTest test
mvn -q test
```

The full suite requires the ignored local `.env.local` database credentials in an isolated worktree. No credential file is committed.

## 11. Rollout and compatibility

- No database migration is required.
- No public HTTP response shape changes.
- No engine priority or `supports(...)` behavior changes.
- Existing canonical runs with a valid stored snapshot remain readable.
- Existing non-terminal canonical runs without a snapshot fail closed instead of silently retrieving new context.
- Disabling `springclaw.context.snapshot.factory-enabled` restores the legacy assembly path, but operators lose snapshot replay guarantees.

## 12. Acceptance criteria

The phase is complete only when all of the following are true:

1. Canonical mode loads and validates `RunState` before deriving any request identity.
2. Every field in the returned `ChatContext` agrees with the accepted run or its stored snapshot.
3. A reused run does not call `ContextSnapshotFactory.create(...)`.
4. The snapshot returned to engines is exactly the snapshot stored in canonical `RunState`.
5. Concurrent first-build races return the committed winner's snapshot.
6. Identity mismatch and missing-snapshot invariants fail closed with stable error codes.
7. Legacy rollback behavior remains explicitly tested.
8. Focused tests and the complete Maven suite pass.

## 13. Follow-up phases

After this phase is merged, separate specifications should address:

1. Redis/MySQL short-term memory reconciliation and scope-correct writes.
2. Entry-level project-memory review and bounded chunking.
3. Long-term relevance, expiry, conflict handling, and vector retrieval.
4. Canonical `ExecutionDecision` replay including explicit confirmation semantics.
5. Unified execution strategies and legacy-engine retirement.

## 14. Implementation evidence

- `AcceptedRunContextResolver` validates the accepted run ID, immutable command fields,
  normalized defaults, terminal status, and access-claim identity before canonical context
  construction.
- `CanonicalContextSnapshotResolver` creates only from `CREATED`, reuses stored snapshots
  for later non-terminal states, and reloads the committed winner after a projection race.
- `ChatContextFactory` now branches explicitly between legacy rollback and canonical
  construction; canonical construction derives identity and role from `RunState`, reuses
  frozen prompt/message data, and rejects provider or model drift.
- Focused verification completed successfully:
  `mvn -q -Dtest=AcceptedRunContextResolverTest,CanonicalContextSnapshotResolverTest,CanonicalContextReadyProjectorTest,RunStateContextSnapshotRequestFactoryTest,ChatContextFactoryTest,ChatContextFactoryCanonicalOwnershipTest,ChatContextFactoryCanonicalSnapshotTest,ChatContextFactoryCanonicalLifecycleProjectionTest,ChatMessageConsumerTest,WebhookRouterServiceTest,TaskExecutionServiceTest test`.
- Full verification completed successfully: `mvn -q test` reported 893 tests, 0 failures,
  0 errors, and 0 skipped tests from Surefire XML reports.
