# Unified Runtime Phase 3A3b Canonical Context Ownership Design

## 1. Goal

Phase 3A3b makes canonical `ContextSnapshot` ownership the default runtime path.

Phase 3A3a proved that SpringClaw can build a `ContextSnapshot` containing a
structured `MemoryFrame` and project it back into legacy `AssembledContext` and
`ContextInjection`. Phase 3A3b changes the default production owner: model input
is derived from the canonical snapshot by default, not independently retrieved by
`ContextAssembler`.

This phase still does not delete the old code. It changes ownership first, then
leaves cleanup for a later phase.

## 2. Selected approach

Use a reversible default cutover:

1. make canonical snapshot mode default-on;
2. create a dedicated bridge from accepted `RunState` to
   `ContextSnapshotRequest`;
3. use the real `RunState.sessionAccessClaim()` instead of reconstructing a
   personal REST claim inside `ChatContextFactory`;
4. ensure `ContextSnapshotFactory` is a real Spring bean when memory-frame
   retrieval is enabled;
5. split legacy lifecycle observation so `LegacyLifecycleObserver` no longer
   creates a second context snapshot when canonical mode is active;
6. keep rollback via `SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false`.

In plain terms: after acceptance, the run's frozen authorization claim becomes the
only authority for memory scope, and the saved snapshot becomes the only memory
view used to prepare model input.

## 3. Alternatives considered

### 3.1 Delete the old retrieval code now

Rejected for this phase.

Deleting `ContextAssembler`, `SemanticMemoryAdvisor`, and legacy context
translation at the same time as default cutover would make failures harder to
diagnose. The immediate objective is ownership, not cleanup.

### 3.2 Keep 3A3a as opt-in forever

Rejected.

Opt-in bridge mode proves compatibility but does not remove production ambiguity.
The product still has two competing context owners until canonical mode is the
default.

### 3.3 Default canonical ownership with legacy fallback

Selected.

This makes real progress while retaining an operational rollback switch. The old
path remains available only when the flag is explicitly disabled.

## 4. Non-goals

Phase 3A3b must not:

- remove `ContextAssembler`;
- remove `SemanticMemoryAdvisor`;
- remove `LegacyRunContextAdapter`;
- remove or merge engines;
- change routing policy;
- change final-answer ownership;
- change stream termination;
- change tool approval, proposal, workspace guard, or tool runtime safety;
- introduce automatic semantic extraction;
- require vector search for startup.

Phase 3A3b may modify these classes to route default behavior through canonical
snapshot ownership and to prevent duplicate context projection.

## 5. Configuration

Change the context snapshot factory flag default:

```properties
SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=true
```

Mapped property:

```yaml
springclaw:
  context:
    snapshot:
      factory-enabled: ${SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED:true}
```

Rollback:

```properties
SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false
```

Memory-frame retrieval should still be operationally controllable. If
`SPRINGCLAW_MEMORY_FRAME_ENABLED=false`, startup may fall back to local bounded
memory stores, but the canonical snapshot path must not silently reconstruct
memory through `ContextAssembler` unless the context snapshot factory flag is also
disabled.

## 6. Accepted run as context authority

`ChatContextFactory` currently receives only `acceptedRunId`. In 3A3a canonical
mode it reconstructs a personal `SessionAccessClaim` from request strings. That
was acceptable only as a bridge.

Phase 3A3b introduces an accepted-run lookup:

```text
acceptedRunId
        |
        v
RunLifecycleStore.requireByRunId(...)
        |
        v
RunState.sessionAccessClaim()
```

Rules:

- canonical mode requires an accepted `RunState`;
- the `RunState` must be `CREATED` or later and non-terminal;
- `RunState.sessionKey`, `channel`, `userId`, `roleCodeAtAcceptance`, and
  `sessionAccessClaim` are the source of truth for snapshot identity;
- raw request strings may provide the original/effective user message, but they
  do not authorize memory scope;
- REST callers cannot obtain shared memory by forging session strings;
- verified ingress-created shared claims can select shared scope.

## 7. New adapter: RunStateContextSnapshotRequestFactory

Add a focused factory:

```text
RunState + routing/system/provider/capability fields
        |
        v
ContextSnapshotRequest
```

Responsibilities:

- read identity and authorization from `RunState`;
- copy original message from `RunState.originalMessage()`;
- use routing effective message from `ChatContextFactory`;
- use `RunState.roleCodeAtAcceptance()`;
- use `RunState.sessionAccessClaim()`;
- add provider metadata and selected capability IDs;
- not call `MemoryCoordinator`;
- not call `ContextAssembler`;
- not call `MemoryService`.

This keeps `ChatContextFactory` from knowing how to translate accepted lifecycle
state into canonical memory scope.

## 8. ChatContextFactory default flow

Default canonical flow:

```text
ChatContextFactory.build(...)
        |
        +-- existing session/routing/system-prompt decisions
        |
        +-- load RunState by acceptedRunId
        |
        +-- RunStateContextSnapshotRequestFactory.create(...)
        |
        +-- ContextSnapshotFactory.create(...)
        |
        +-- LegacyContextViewAdapter.adapt(...)
        |
        v
ChatContext with legacy-compatible assembled/contextInjection
```

Legacy fallback flow:

```text
if springclaw.context.snapshot.factory-enabled=false:
    existing ContextAssembler path
```

Fail-fast rules:

- if canonical mode is enabled and `RunLifecycleStore` has no accepted run for
  `acceptedRunId`, fail before model execution;
- if canonical mode is enabled and required factory beans are missing, fail before
  model execution;
- do not fall back to `ContextAssembler` on authorization or canonical wiring
  failure unless the flag is explicitly disabled.

## 9. Spring wiring

`ContextSnapshotFactory` and `LegacyContextViewAdapter` must be Spring beans.

`ContextSnapshotFactory` depends on:

- `MemoryCoordinator`;
- `Clock` provider or `Clock.systemUTC()`.

Because `MemoryCoordinator` is already controlled by
`springclaw.memory.frame.enabled`, the implementation must ensure canonical mode
has a clear startup contract:

- if `springclaw.context.snapshot.factory-enabled=true`, the required snapshot
  beans must exist;
- if memory-frame retrieval is disabled and no `MemoryCoordinator` exists, startup
  or context build fails loudly instead of reverting to old retrieval silently;
- rollback is to disable canonical context mode, not to fabricate an unscoped
  context.

## 10. LegacyLifecycleObserver split

Current production observation calls:

```text
LegacyRunContextAdapter.adapt(context, at)
bridge.contextObserved(...)
bridge.decisionObserved(...)
```

In canonical mode, the context snapshot has already been built by
`ContextSnapshotFactory`. The observer must not create another snapshot from
legacy `AssembledContext`.

Phase 3A3b behavior:

- canonical mode:
  - skip `LegacyRunContextAdapter -> contextObserved`;
  - keep `decisionObserved`;
- legacy mode:
  - keep current behavior for rollback.

This prevents two context producers from writing `CONTEXT_READY` for the same run.

## 11. Advisor behavior

3A3a already suppresses `SemanticMemoryAdvisor` when canonical snapshot mode is
enabled. 3A3b keeps that behavior.

Rules:

- canonical mode must not attach any Advisor that calls `MemoryService`;
- optional Spring AI chat-memory Advisor may remain disabled by default;
- projection-only Advisor is still deferred unless needed for parity.

## 12. Testing strategy

Focused tests:

- `RunStateContextSnapshotRequestFactoryTest`
  - uses `RunState.sessionAccessClaim()` as the request claim;
  - preserves shared-session claims;
  - rejects mismatched accepted identity.
- `ContextSnapshotFactorySpringWiringTest`
  - snapshot factory bean exists when canonical context and memory frame are
    enabled;
  - missing memory frame wiring fails clearly when canonical context is enabled.
- `ChatContextFactoryCanonicalOwnershipTest`
  - default path uses accepted `RunState.sessionAccessClaim()`;
  - default path does not call `ContextAssembler`;
  - explicit rollback flag false uses legacy `ContextAssembler`;
  - missing accepted run fails fast.
- `LegacyLifecycleObserverCanonicalModeTest`
  - canonical mode skips context observation;
  - canonical mode still observes decision;
  - legacy mode keeps old context+decision observation.

Compatibility gates:

- context propagation characterization;
- route characterization;
- final-answer ownership characterization;
- stream/transport parity;
- prompt-injection tests;
- tool runtime safety tests;
- full `mvn test`.

## 13. Rollback

Rollback path:

1. set `SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false`;
2. if needed, set `SPRINGCLAW_MEMORY_FRAME_ENABLED=false`;
3. restore legacy `ContextAssembler` default path;
4. re-enable legacy context observation in `LegacyLifecycleObserver`;
5. keep `ContextSnapshot.memoryFrame` contract in place unless a later rollback
   explicitly reverts 3A3a.

## 14. Acceptance criteria

Phase 3A3b is complete when:

- canonical context snapshot mode is default-on;
- canonical context mode reads authorization from accepted `RunState`;
- `ChatContextFactory` no longer reconstructs personal claims in canonical mode;
- canonical mode does not call `ContextAssembler`;
- canonical mode does not attach independent semantic retrieval Advisor;
- legacy lifecycle observer does not create a second context snapshot in
  canonical mode;
- explicit rollback flag restores old `ContextAssembler` behavior;
- focused tests, compatibility gates, and full suite pass;
- collaboration ledger records evidence and rollback.

## 15. Deferred cleanup

Later phases should:

- remove production dependency on `ContextAssembler`;
- remove or rewrite `SemanticMemoryAdvisor` as projection-only;
- remove `LegacyRunContextAdapter` from production wiring;
- remove shadow comparison after canonical ownership is stable;
- remove flat compatibility memory fields once all engines consume structured
  snapshot projections.
