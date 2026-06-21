# Unified Runtime Phase 2B Core and Handoff Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the canonical lifecycle core with atomic state/event commits, then hand broad legacy and transport integration to Claude behind frozen interfaces.

**Architecture:** `RunCoordinator` is the only lifecycle writer. Query-only `RunStateRepository` and `RunEventStore` are backed by one atomic `RunLifecycleStore`; `LegacyRuntimeBridge` is a transitional observer API and does not implement `RuntimeStrategy`. Codex owns Tasks 1–4. Claude owns Tasks 5–8 only after the Codex core commit is published.

**Tech Stack:** Java 17, Spring Boot 3.5, JUnit 5, AssertJ, `ConcurrentHashMap`, Maven Surefire.

---

## Fixed file ownership

Codex may create or modify:

```text
src/main/java/com/springclaw/runtime/lifecycle/**
src/main/java/com/springclaw/runtime/bridge/**
src/test/java/com/springclaw/runtime/lifecycle/**
src/test/java/com/springclaw/runtime/bridge/**
docs/superpowers/specs/2026-06-18-unified-agent-runtime-design.md
docs/superpowers/specs/2026-06-21-unified-runtime-phase-2b-core-design.md
docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
```

Claude may modify only after Task 4, except `EngineSelector.java` and its directly
affected tests, which may be completed immediately as the isolated early Task 4A:

```text
src/main/java/com/springclaw/controller/ChatController.java
src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java
src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java
src/main/java/com/springclaw/service/chat/async/AsyncChatResultStore.java
src/main/java/com/springclaw/service/webhook/WebhookRouterService.java
src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java
src/main/java/com/springclaw/service/agent/EngineSelector.java
src/main/java/com/springclaw/service/agent/AgentRunTraceService.java
src/main/java/com/springclaw/service/chat/impl/SseEventBridge.java
src/main/java/com/springclaw/service/proposal/ToolInvocationProposalService.java
src/main/java/com/springclaw/service/proposal/ToolProposalExecutionService.java
src/main/java/com/springclaw/service/proposal/ToolInvocationProposalRepository.java
src/main/java/com/springclaw/scheduled/ToolProposalCleanupTask.java
their directly affected tests
```

Neither worker may modify transport DTOs, `runtime/contract/**`,
`ToolRuntimeAspect`, workspace guards, engine `supports()` methods, engine
`priority()` methods, or configuration in Phase 2B.

### Task 1: Define query and atomic lifecycle store ports

**Owner:** Codex

**Files:**
- Create: `src/main/java/com/springclaw/runtime/lifecycle/RunStateRepository.java`
- Create: `src/main/java/com/springclaw/runtime/lifecycle/RunEventStore.java`
- Create: `src/main/java/com/springclaw/runtime/lifecycle/RunLifecycleStore.java`
- Test: `src/test/java/com/springclaw/runtime/lifecycle/InMemoryRunLifecycleStoreTest.java`

- [ ] **Step 1: Write failing API and atomicity tests**

The tests must instantiate `InMemoryRunLifecycleStore` and prove:

```java
assertThat(store.findByRunId(runId)).isEmpty();
store.create(createdState, createdDraft);
assertThat(store.requireByRunId(runId)).isEqualTo(createdState);
assertThat(store.findEventsByRunId(runId))
        .extracting(RunEvent::sequence)
        .containsExactly(1L);
```

Add a stale-revision test where two commits use the same expected revision. Exactly
one commit succeeds; the final state revision is `1` and the event sequences are
`1, 2`. Add conflicting duplicate creation and identical idempotent creation tests.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=InMemoryRunLifecycleStoreTest test
```

Expected: test compilation fails because lifecycle store types do not exist.

- [ ] **Step 3: Implement the ports**

Use these exact public shapes:

```java
public interface RunStateRepository {
    Optional<RunState> findByRunId(String runId);
    RunState requireByRunId(String runId);
}

public interface RunEventStore {
    List<RunEvent> findEventsByRunId(String runId);
}

public interface RunLifecycleStore extends RunStateRepository, RunEventStore {
    RunState create(RunState initialState, RunEvent.Draft creationEvent);
    RunState commit(long expectedRevision, RunState nextState, RunEvent.Draft event);
}
```

`create` accepts an identical duplicate based on immutable acceptance fields and
returns the existing state without a second event. `commit` checks the current
revision, calls `RunTransitionPolicy.validate`, assigns one event ID and the next
sequence, and installs state plus event under one per-run critical section.

- [ ] **Step 4: Verify GREEN**

```bash
mvn -q -Dtest=InMemoryRunLifecycleStoreTest test
mvn -q -DskipTests compile
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/springclaw/runtime/lifecycle \
  src/test/java/com/springclaw/runtime/lifecycle/InMemoryRunLifecycleStoreTest.java
git commit -m "feat: add atomic canonical lifecycle store"
```

### Task 2: Implement explicit lifecycle coordinator

**Owner:** Codex

**Files:**
- Create: `src/main/java/com/springclaw/runtime/lifecycle/RunAcceptance.java`
- Create: `src/main/java/com/springclaw/runtime/lifecycle/RunCoordinator.java`
- Test: `src/test/java/com/springclaw/runtime/lifecycle/RunCoordinatorTest.java`

- [ ] **Step 1: Write failing transition tests**

Cover:

```text
accept -> CREATED
CREATED -> CONTEXT_READY
CONTEXT_READY -> DECIDED
DECIDED -> RUNNING
RUNNING -> WAITING_CONFIRMATION
WAITING_CONFIRMATION -> RUNNING
RUNNING -> VERIFYING
VERIFYING -> COMPLETED
VERIFYING -> DEGRADED
any legal nonterminal -> FAILED
terminal mutation rejected
```

Assert every accepted transition increments revision exactly once and appends the
matching event type. Use real contract fixtures, not mocked `RunState`.

- [ ] **Step 2: Verify RED**

```bash
mvn -q -Dtest=RunCoordinatorTest test
```

Expected: compilation fails because coordinator types do not exist.

- [ ] **Step 3: Implement acceptance and coordinator**

`RunAcceptance` carries all immutable `RunState` acceptance fields:

```text
runId, sessionKey, channel, userId, roleCodeAtAcceptance, originalMessage,
responseMode, acceptedAt, deadlineAt
```

`RunCoordinator` exposes only explicit methods:

```text
accept, contextReady, decided, running, waitingConfirmation,
confirmationApproved, verifying, completed, degraded, failed
```

Every method reads the current state, constructs the next immutable `RunState`, and
delegates one atomic commit. It never calls a transport, engine, model, tool,
persister, trace service, or lock service.

- [ ] **Step 4: Verify GREEN**

```bash
mvn -q -Dtest=InMemoryRunLifecycleStoreTest,RunCoordinatorTest test
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/springclaw/runtime/lifecycle/RunAcceptance.java \
  src/main/java/com/springclaw/runtime/lifecycle/RunCoordinator.java \
  src/test/java/com/springclaw/runtime/lifecycle/RunCoordinatorTest.java
git commit -m "feat: coordinate canonical run lifecycle"
```

### Task 3: Freeze the transitional legacy bridge API

**Owner:** Codex

**Files:**
- Create: `src/main/java/com/springclaw/runtime/bridge/LegacyRuntimeBridge.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/DefaultLegacyRuntimeBridge.java`
- Test: `src/test/java/com/springclaw/runtime/bridge/LegacyRuntimeBridgeTest.java`

- [ ] **Step 1: Write failing bridge-boundary tests**

Prove the bridge delegates typed facts to the coordinator and exposes no method
whose signature references:

```text
SseEmitter, ChatResponse, RabbitTemplate, AgentEngine, ChatResultPersister,
ToolInvoker, WorkspaceGitGuard
```

Also assert `LegacyRuntimeBridge` is not assignable to `RuntimeStrategy`.

- [ ] **Step 2: Verify RED**

```bash
mvn -q -Dtest=LegacyRuntimeBridgeTest test
```

- [ ] **Step 3: Implement the bridge**

The interface mirrors the coordinator lifecycle methods but uses observation names:

```text
accepted, contextObserved, decisionObserved, executionStarted,
confirmationRequired, confirmationApproved, verificationStarted,
completed, degraded, failed
```

`DefaultLegacyRuntimeBridge` is a thin delegator. It contains no route selection,
answer repair, persistence, transport completion, lock release, or tool invocation.

- [ ] **Step 4: Verify GREEN**

```bash
mvn -q -Dtest=InMemoryRunLifecycleStoreTest,RunCoordinatorTest,LegacyRuntimeBridgeTest test
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/springclaw/runtime/bridge \
  src/test/java/com/springclaw/runtime/bridge
git commit -m "feat: freeze legacy lifecycle bridge boundary"
```

### Task 4: Codex core acceptance and Claude handoff

**Owner:** Codex

- [ ] Run contract and core tests:

```bash
mvn -q -Dtest=RunStatusTest,ContextAndDecisionContractTest,ToolCompletionAndResultContractTest,RunEventContractTest,RunStateContractTest,RuntimeStrategyContractTest,LegacyRuntimeContractFixturesTest,InMemoryRunLifecycleStoreTest,RunCoordinatorTest,LegacyRuntimeBridgeTest test
```

- [ ] Run characterization and Phase 2A tests:

```bash
mvn -q -Dtest=RuntimeRouteCharacterizationTest,ContextPropagationCharacterizationTest,ToolSafetyPathCharacterizationTest,FinalAnswerOwnershipCharacterizationTest,TransportParityCharacterizationTest,DefaultRunIdentityFactoryTest,CanonicalTransportIdentityTest,CanonicalToolOwnershipTest test
```

- [ ] Record core commit SHAs, frozen APIs, process-local limitation, rollback order,
and Claude file ownership in the collaboration ledger.

- [ ] Commit:

```bash
git add docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
git commit -m "docs: hand off phase 2b integration to Claude"
```

### Task 4A: Freeze legacy engine order early

**Owner:** Claude

This task may run in parallel with Codex Tasks 2–4 because it does not depend on
the lifecycle APIs.

Change only the `EngineSelector` constructor comparator and directly affected tests.
Sort by `(priority, legacyRank)` with:

```text
basic-stream=10
agent-runtime=20
autonomous-loop=30
opar-loop=40
model-led-stream=50
simplified=60
```

A missing name must fail initialization. Do not change any engine `priority()` or
`supports()` method.

Verification:

```bash
mvn -q -Dtest=EngineSelectorTest,RuntimeRouteCharacterizationTest test
mvn -q -Dtest=EngineSelectorTest,ChatContextFactoryTest,PromptInjectionTest,AgentRuntimeEngineTest test
git diff --check
```

Commit: `fix: freeze legacy engine ordering`

### Task 5: Wire all accepted ingress paths

**Owner:** Claude

Execute the detailed plan:

```text
docs/superpowers/plans/2026-06-22-unified-runtime-phase-2b-task5-ingress-wiring.md
```

The detailed plan resolves Q1–Q7, gives exact production edits for
`ChatController`, `ChatMessageConsumer`, `WebhookRouterService`, and
`TaskExecutionService`, enumerates all 13 existing test construction sites, and
creates the missing `ChatMessageConsumerTest`.

Verification:

```bash
mvn -q -Dtest=ChatControllerAuthTest,ChatMessageConsumerTest,WebhookRouterServiceTest,TaskExecutionServiceTest,CanonicalTransportIdentityTest,TransportParityCharacterizationTest test
```

Commit: `feat: wire canonical lifecycle ingress`

### Task 6: Wire legacy observations

**Owner:** Claude

Translate already-produced context, decision, selected engine, verification, and
terminal results into bridge calls. Engine ordering was already frozen by Task 4A
in commit `b7bb77f`; Task 6 must not modify `EngineSelector`, engine `supports()`,
or engine `priority()`.

Verification:

```bash
mvn -q -Dtest=EngineSelectorTest,RuntimeRouteCharacterizationTest,ChatServiceImplModeTest,ChatServiceImplPersistenceTest test
```

Commit: `feat: project legacy execution into canonical lifecycle`

### Task 7: Convert async, trace, and proposal statuses to projections

**Owner:** Claude

Order async result writes after canonical transitions. Prevent trace events from
mutating canonical state. Observe proposal create, approval, rejection, individually
identified expiry, and frozen tool outcome. Change bulk expiry to return affected
proposal identities before emitting per-run expiry events.

Do not claim durable strategy continuation, restart exactly-once behavior, or
transport-notification isolation; those remain later phases.

Verification:

```bash
mvn -q -Dtest=AgentRunTraceServiceTest,ToolInvocationProposalServiceConfirmTest,ToolProposalExecutionServiceTest,TransportParityCharacterizationTest test
```

Commit: `feat: project canonical lifecycle to legacy status stores`

### Task 8: Claude integration acceptance

**Owner:** Claude

Run all commands from Tasks 4–7 plus:

```bash
mvn -q -Dtest=EngineSelectorTest,ChatContextFactoryTest,PromptInjectionTest,AgentRuntimeEngineTest,ToolRuntimeAspectInterceptionIT,ToolInvocationProposalServiceConfirmTest test
git diff --check
```

Report all commits, changed files, test counts, environmental failures, and any
required core-interface change. Do not modify Codex core files to resolve an
integration mismatch.
