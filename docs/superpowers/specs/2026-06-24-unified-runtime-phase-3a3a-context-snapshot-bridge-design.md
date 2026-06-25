# Unified Runtime Phase 3A3a ContextSnapshot Bridge Design

## 1. Goal

Phase 3A3a connects the new `MemoryFrame` to the canonical `ContextSnapshot`
without deleting the old chat engines or rewriting the whole prompt path in one
step.

In plain terms: the new memory result becomes part of the official run snapshot,
and the existing `AssembledContext` / `ContextInjection` objects become
compatibility views derived from that snapshot. The old classes may still exist,
but they no longer need to be the only place where memory is retrieved.

## 2. Selected approach

Use a conservative bridge cutover:

1. extend `ContextSnapshot` with a required structured `MemoryFrame`;
2. add `ContextSnapshotFactory` to build the snapshot from accepted run metadata,
   provider metadata, capabilities, system prompt, and one `MemoryCoordinator`
   retrieval;
3. add `LegacyContextViewAdapter` to derive `AssembledContext` and
   `ContextInjection` from the saved snapshot;
4. gate activation with a default-off flag;
5. when the canonical snapshot path is enabled, prevent Advisor-side memory
   retrieval from adding a second independently retrieved semantic memory block.

This is not the final cleanup. It creates the safe bridge required before Phase
3A3b removes or rewrites the old memory readers.

## 3. Alternatives considered

### 3.1 Full cutover now

Directly replace `ContextAssembler`, disable `SemanticMemoryAdvisor`, and make all
engines read only the canonical snapshot.

Rejected for this step. It is the correct end state, but it changes prompt shape,
Advisor behavior, and context ownership at the same time. That makes regressions
harder to isolate.

### 3.2 Keep MemoryFrame as shadow-only

Leave Phase 3A2 as diagnostics and do not add it to `ContextSnapshot`.

Rejected. It avoids risk but does not move ownership forward. The project would
remain stuck with two memory concepts: shadow frame and production legacy context.

### 3.3 Conservative bridge

Selected. It gives the run a structured canonical memory snapshot while keeping
existing chat surfaces and engines compatible.

## 4. Non-goals

Phase 3A3a must not:

- remove any engine;
- delete `ContextAssembler`;
- delete `SemanticMemoryAdvisor`;
- delete `ConversationAdvisorSupport`;
- change final-answer ownership;
- change routing or model selection policy;
- change stream termination;
- change tool approval, workspace guard, or proposal lifecycle;
- introduce automatic LLM extraction of new semantic facts;
- require MySQL, Redis, or vector storage for default startup.

Phase 3A3a may modify those classes only to add a reversible bridge or a
canonical-mode guard.

## 5. Configuration

Add a new default-off flag:

```properties
SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false
```

Mapped property:

```yaml
springclaw:
  context:
    snapshot:
      factory-enabled: ${SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED:false}
```

When false:

- current production behavior remains unchanged;
- `ChatContextFactory` still calls `ContextAssembler`;
- `ConversationAdvisorSupport` behaves as it does today;
- Phase 3A3a classes may exist but are inactive.

When true:

- `ChatContextFactory` uses `ContextSnapshotFactory`;
- `LegacyContextViewAdapter` derives the legacy `AssembledContext` and
  `ContextInjection`;
- Advisor-side semantic retrieval is suppressed or replaced by projection-only
  behavior so the same model request does not receive two independently retrieved
  long-term memory blocks.

## 6. ContextSnapshot contract change

`ContextSnapshot` gains:

```java
MemoryFrame memoryFrame
```

Rules:

- `memoryFrame` is required.
- `memoryFrame.runId()` must equal `ContextSnapshot.runId()`.
- `memoryFrame.scope()` must correspond to the accepted `SessionAccessClaim`.
- Flat fields remain compatibility projections:
  - `memoryBankText`;
  - `shortTermEvents`;
  - `semanticRecallItems`;
  - `activeLearningRules`.
- New production code must not populate those flat fields from an independent
  memory retrieval when canonical mode is enabled.
- `snapshotHash` includes the embedded `memoryFrame.frameHash()`.
- `capturedAt` is stored but excluded from deterministic content hash rules.

Compatibility impact:

- Existing tests and constructors must be migrated through test helpers or a
  compatibility constructor that builds an explicit empty `MemoryFrame`.
- The long-term target is no empty frame fallback in production paths.

## 7. ContextSnapshotFactory

Create `ContextSnapshotFactory` with a narrow responsibility:

```text
accepted run metadata + session + routing context + provider + capabilities
        |
        v
MemoryCoordinator.retrieve(...)
        |
        v
ContextSnapshot
```

Inputs should include:

- run ID;
- accepted immutable session access claim or a `MemoryScope` derived from it;
- session key, channel, user ID, accepted owner user ID;
- role code at acceptance;
- original and effective messages;
- system prompt;
- allowed capability IDs;
- active provider metadata.

The factory must not:

- call `ContextAssembler`;
- call `MemoryService`;
- call `MessageEventService` directly;
- read Memory Bank files directly;
- query vector search directly;
- decide routing.

It delegates all memory retrieval to `MemoryCoordinator`.

## 8. LegacyContextViewAdapter

Add an adapter that derives legacy views from the saved snapshot:

```text
ContextSnapshot
        |
        +-- AssembledContext
        +-- ContextInjection
```

Rendering rules:

- project memory comes from `memoryFrame.projectItems()`;
- short-term context comes from `memoryFrame.shortTermTurns()`;
- semantic context comes from `memoryFrame.semanticFacts()`;
- procedural/learning context comes from `memoryFrame.proceduralRules()`;
- omissions are not inserted into the model prompt by default;
- source IDs and counts go into metadata, not prose prompt text.

The adapter is deterministic: the same snapshot produces the same
`AssembledContext.observePrompt()`.

## 9. ChatContextFactory bridge

`ChatContextFactory` remains the place that collects routing-era fields for
`ChatContext`, but it stops being the memory owner when canonical mode is enabled.

Pseudo-flow:

```text
build session, role, allowed tools, routing decision, matched skills, system prompt
        |
        v
if springclaw.context.snapshot.factory-enabled=false:
    current ContextAssembler path
else:
    ContextSnapshotFactory builds snapshot
    LegacyContextViewAdapter projects snapshot to AssembledContext/ContextInjection
```

This keeps `ChatContext` stable for existing engines while allowing tests to prove
the snapshot is now the single source of memory content.

## 10. Advisor behavior

Current risk: `ContextAssembler` can include semantic memory, and
`SemanticMemoryAdvisor` can independently retrieve semantic memory again.

Phase 3A3a rule:

- when canonical snapshot mode is disabled, existing Advisor behavior remains;
- when canonical snapshot mode is enabled, `ConversationAdvisorSupport` must not
  attach an Advisor that calls `MemoryService` for semantic retrieval;
- projection-only Advisor behavior may be introduced later, but Phase 3A3a can
  simply suppress independent semantic retrieval in canonical mode.

This satisfies the immediate safety requirement: no model request should receive
two independently retrieved long-term memory views.

## 11. Authorization

The factory must use the same accepted scope model introduced in Phase 3A1 and
Phase 3A2.

Rules:

- do not infer shared scope from raw REST session strings;
- derive `MemoryScope` from the frozen `SessionAccessClaim`;
- fail context construction if required accepted identity is missing;
- do not downgrade an authorization failure into an empty successful memory
  snapshot.

For compatibility tests that do not have a full accepted run object, test fixtures
may construct a personal `SessionAccessClaim`, but production code must not
reconstruct authority from free-form request strings.

## 12. Testing strategy

Required focused tests:

- `ContextSnapshotMemoryFrameContractTest`
  - `ContextSnapshot` requires a `MemoryFrame`;
  - run ID mismatch is rejected;
  - collections remain immutable.
- `ContextSnapshotFactoryTest`
  - factory calls `MemoryCoordinator` exactly once;
  - snapshot hash changes when `memoryFrame.frameHash()` changes;
  - flat fields are projections, not independent source reads.
- `LegacyContextViewAdapterTest`
  - deterministic `AssembledContext` rendering from a snapshot;
  - project, short-term, semantic, and procedural layers map to the expected
    legacy sections;
  - metadata includes frame hash and layer counts.
- `ChatContextFactoryCanonicalSnapshotTest`
  - with flag disabled, legacy path still calls `ContextAssembler`;
  - with flag enabled, factory uses `ContextSnapshotFactory` and does not call
    `ContextAssembler`;
  - produced `ChatContext.assembled()` remains non-null for old engines.
- `ConversationAdvisorSupportCanonicalModeTest`
  - with canonical mode disabled, existing semantic Advisor wiring remains;
  - with canonical mode enabled, semantic retrieval Advisor is not attached.

Compatibility gates:

- existing route characterization;
- final-answer ownership characterization;
- stream/transport parity;
- prompt injection tests;
- tool runtime safety tests;
- full `mvn test`.

## 13. Rollback

Rollback is intentionally simple:

1. set `SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false`;
2. if necessary, set `SPRINGCLAW_MEMORY_FRAME_ENABLED=false`;
3. revert ChatContextFactory bridge wiring;
4. revert Advisor canonical-mode guard;
5. revert `ContextSnapshotFactory` and `LegacyContextViewAdapter`;
6. revert the `ContextSnapshot.memoryFrame` contract change only if downstream
   tests have been migrated back.

## 14. Acceptance criteria

Phase 3A3a is complete when:

- `ContextSnapshot` embeds a complete `MemoryFrame`;
- canonical mode can build `ChatContext` from a snapshot-derived legacy view;
- default startup behavior is unchanged;
- canonical mode performs one `MemoryCoordinator` retrieval for a context build;
- canonical mode does not call `ContextAssembler`;
- canonical mode does not attach independent semantic retrieval Advisor;
- old engines still receive `AssembledContext` and `ContextInjection`;
- focused tests, compatibility gates, and full suite pass;
- the collaboration ledger records evidence and rollback.

## 15. Deferred to Phase 3A3b

Phase 3A3b should:

- make canonical snapshot mode the default;
- remove production dependency on `LegacyRunContextAdapter`;
- replace `SemanticMemoryAdvisor` with a projection-only Advisor if needed;
- stop runtime reads from `ContextAssembler`;
- remove shadow comparison once canonical ownership is proven;
- clean up legacy compatibility fields after all engines consume structured
  snapshot/context projections directly.
