# Unified Runtime Phase 2B Core Design

**Status:** Approved direction from the collaboration thread on 2026-06-21.

## Goal

Introduce the canonical lifecycle write authority and freeze stable integration
interfaces so broad transport, trace, proposal, and legacy-execution wiring can be
implemented independently without changing routing, answers, persistence, locks,
stream termination, DTOs, or P0 tool safety.

## Decision

Three approaches were considered:

1. Make the legacy executor implement `RuntimeStrategy`. Rejected because
   `RuntimeStrategy` explicitly forbids persistence, answer composition, transport
   termination, and lock ownership while the legacy path still owns all four.
2. Let each transport write `RunStateRepository` and `RunEventStore` directly.
   Rejected because state and event writes could diverge and transports would become
   lifecycle authorities.
3. Use a dedicated transitional bridge over a single atomic lifecycle commit port.
   Selected because it preserves the Phase 1 strategy contract, keeps the
   coordinator authoritative, and gives integration workers a small frozen API.

## Core ownership

### `RunCoordinator`

The coordinator is the only component allowed to:

- create a canonical `RunState`;
- validate transitions with `RunTransitionPolicy`;
- assign the next revision;
- request an atomic state-plus-event commit;
- reject stale revisions and terminal-state mutation.

It exposes explicit lifecycle operations rather than a generic state mutator:

```text
accept
contextReady
decided
running
waitingConfirmation
confirmationApproved
verifying
completed
degraded
failed
```

Each operation accepts typed contract evidence required for that transition.

### Store ports

`RunStateRepository` is a query view:

```text
findByRunId
requireByRunId
```

`RunEventStore` is a query view:

```text
findByRunId
```

`RunLifecycleStore` is the only write port:

```text
create(initialState, creationEvent)
commit(expectedRevision, nextState, transitionEvent)
```

The in-memory implementation uses one per-run critical section. It assigns event
identity and sequence inside the same section that installs the state revision.
Duplicate creation returns the existing run only when the immutable acceptance
fields are identical; conflicting acceptance fails. A stale revision fails without
writing either state or event.

This implementation is process-local. It does not claim durability or
exactly-once behavior across restart.

### `LegacyRuntimeBridge`

The bridge is not a `RuntimeStrategy`. It is a narrow adapter used by legacy
integration points to report already-observed facts to `RunCoordinator`.

It may:

- construct acceptance metadata;
- translate already-created legacy context and decision values into canonical
  contract values;
- report the selected legacy engine identifier;
- report confirmation and terminal boundaries.

It may not:

- select or execute an engine;
- compose or repair an answer;
- persist a conversation result;
- complete an emitter or release a lock;
- invoke a tool;
- convert transport delivery failure into business failure.

## Integration split

Codex owns:

- this design and the corrected architecture section;
- store interfaces and in-memory atomic implementation;
- `RunCoordinator`;
- `LegacyRuntimeBridge` interface and immutable command values;
- transition, concurrency, idempotency, and terminal-immutability tests.

Claude owns after the core commit:

- sync, SSE, async enqueue/Rabbit, webhook, and scheduled-task ingress wiring;
- legacy context, decision, selected-engine, and result adapters;
- async result-store projection ordering;
- trace status projection and removal of diagnostic status authority;
- proposal create/approve/reject/expiry/tool-result observers;
- constructor and fixture migration;
- cross-transport compatibility tests and documentation updates.

Claude must not modify the core interfaces without returning the change to the
architecture gate.

## Confirmation limit

Phase 2B records lifecycle facts available in the current product:

- proposal persisted → `WAITING_CONFIRMATION`;
- approval before frozen invocation → `RUNNING`;
- rejection or individually observable expiry → `FAILED`;
- frozen tool outcome → tool lifecycle event.

It does not claim restoration of the original model continuation after the frozen
tool call. Durable continuation, dispatch claiming, and full same-run resume remain
Phase 4A.

The current count-only bulk expiry API must return affected proposal identities
before per-run expiry events can be accepted.

## Compatibility constraints

- Engine order is frozen as `(priority, legacyRank)` with ranks:
  `basic-stream=10`, `agent-runtime=20`, `autonomous-loop=30`,
  `opar-loop=40`, `model-led-stream=50`, `simplified=60`.
- Missing legacy rank fails initialization.
- Engine `priority()` and `supports()` behavior do not change.
- Existing REST, SSE, Rabbit, STOMP, and proposal DTOs do not change.
- Existing legacy routing, final-answer, persistence, lock, and stream owners remain
  active until their later migration phases.
- Trace and async stores may project canonical state but cannot mutate it.

## Acceptance

- State revision and lifecycle event are committed atomically.
- Concurrent stale transitions produce one accepted revision and one event.
- Terminal state cannot be overwritten by trace or transport observations.
- Every integration path can create or claim one canonical run through the frozen
  bridge API.
- Tests explicitly state the process-local durability limitation.
- Existing contract, characterization, identity, routing, tool-safety, and transport
  tests remain passing, except documented external MySQL authentication failures in
  the full repository suite.

## Rollback

Revert lifecycle integration wiring first, then revert the core coordinator/store
commit. Retain Phase 2A canonical identity propagation.
