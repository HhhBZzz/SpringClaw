# Unified Runtime Phase 2B Task 6 Observation Wiring Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire already-produced legacy context, decision, selected engine, confirmation, failure, and returned result facts into the frozen canonical lifecycle observer without changing legacy execution behavior.

**Architecture:** Codex commit `8ae12ee` provides `LegacyLifecycleObserver`; callers report facts but never construct canonical contracts. Legacy answers, persistence, locks, routing, and SSE completion remain unchanged. Because Phase 2B has no `CompletionVerifier`, every successful legacy return is recorded as `DEGRADED / LEGACY_UNVERIFIED_RESULT`, never `COMPLETED`.

**Tech Stack:** Java 17, Spring Boot constructor injection, JUnit 5, Mockito, Maven Surefire.

---

## Ownership

Claude may modify:

```text
src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java
src/main/java/com/springclaw/service/chat/impl/BasicStreamEngine.java
src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java
src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java
their directly affected tests
docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
```

Claude must not modify:

```text
src/main/java/com/springclaw/runtime/**
src/main/java/com/springclaw/service/agent/EngineSelector.java
src/main/java/com/springclaw/service/agent/AgentEngine.java
src/main/java/com/springclaw/tool/runtime/**
src/main/java/com/springclaw/service/proposal/**
src/main/java/com/springclaw/service/workspace/**
transport DTOs
configuration
```

## Required ordering

For blocking execution:

```text
ChatContextFactory.build
observer.contextAndDecisionObserved
EngineSelector.select
observer.executionStarted
legacy engine execution
legacy answer composition
existing persistence when enabled
observer.resultReturned
return existing response
```

For SSE:

```text
ChatContextFactory.build
observer.contextAndDecisionObserved
EngineSelector.select
observer.executionStarted
legacy stream/engine branch
existing persistence
observer.resultReturned
existing lock release and emitter completion
```

`stream(AcceptedChatCommand)` returning an emitter is not a terminal boundary.

## Task 6.1: Blocking and non-streamable ChatServiceImpl wiring

- [ ] Inject `LegacyLifecycleObserver` into `ChatServiceImpl`.
- [ ] Preserve all compatibility constructors by passing a mockable/no-op-free real
  dependency from test fixtures; do not instantiate a second lifecycle store.
- [ ] In `executeInternal`, call `contextAndDecisionObserved` immediately after
  `ChatContextFactory.build`.
- [ ] Select the engine once, call `executionStarted(context, engine.name(), now)`,
  and execute that selected instance. Do not select again.
- [ ] After final answer composition and existing optional persistence, call
  `resultReturned(context, executionResult, finalAnswer, now)` even when
  `persistResult` is false.
- [ ] If blocking execution throws after acceptance, call
  `failed(runId, "LEGACY_EXECUTION_FAILED", ex, now)` and rethrow.

Add focused tests proving the final canonical status is `DEGRADED`, the answer is
unchanged, and `persistResult=false` still reaches a terminal lifecycle state.

## Task 6.2: SSE start and confirmation wiring

- [ ] In `executeStream`, call `contextAndDecisionObserved` after context build.
- [ ] After the one existing `EngineSelector.select`, call
  `executionStarted(context, engine.name(), now)`.
- [ ] If `streamActionRequired` creates an `AgentActionProposal`, call
  `confirmationRequired(runId, proposalId, now)` before emitter completion.
- [ ] In the `PendingToolApprovalException` catch, do not call
  `confirmationRequired`; persisted tool-proposal creation is the unique owner of
  that transition in Task 7. Continue to render the existing pending UI through
  `handlePendingApproval`.
- [ ] Do not call `resultReturned` for confirmation messages.
- [ ] If stream startup fails and no fallback succeeds, call
  `failed(runId, "LEGACY_STREAM_FAILED", ex, now)`.

Add tests proving confirmation ends at `WAITING_CONFIRMATION` and does not create a
terminal result.

## Task 6.3: ChatServiceImpl SSE terminal points

Call `resultReturned` exactly once after existing persistence at:

```text
streamImmediateAnswer
streamAgentRuntimeAnswer success
streamBlockingFallback success
streamReflectAnswer doOnComplete
streamReflectAnswer doOnError after fallback persistence
```

If `streamBlockingFallback` itself fails, call `failed` instead. Do not mark the
original stream transport error failed when its blocking fallback succeeds.

## Task 6.4: Streamable engine terminal points

Inject the same Spring `LegacyLifecycleObserver` bean into:

```text
BasicStreamEngine
ModelLedStreamEngine
AutonomousLoopEngine
```

After each engine's existing successful persistence and before lock release/emitter
completion, call:

```java
observer.resultReturned(context, executionResult, answer, Instant.now());
```

Use the exact `ChatExecutionResult` already passed to persistence. For partial-answer
fallbacks that persist an answer, report that returned result once. Do not report a
terminal event before the fallback handler has decided whether it can recover.

Constructor migration must update all direct test constructions discovered by:

```bash
rg -n "new BasicStreamEngine\\(|new ModelLedStreamEngine\\(|new AutonomousLoopEngine\\(" src/test/java
```

## Task 6.5: Verification

Run:

```bash
mvn -q -Dtest=LegacyRuntimeAdaptersTest,LegacyLifecycleObserverTest,RunCoordinatorTest,ChatServiceImplModeTest,ChatServiceImplPersistenceTest,ChatServiceImplPendingApprovalTest,PromptInjectionTest,RuntimeRouteCharacterizationTest,FinalAnswerOwnershipCharacterizationTest,TransportParityCharacterizationTest test
mvn -q -Dtest=EngineSelectorTest,ChatContextFactoryTest,AgentRuntimeEngineTest,ToolRuntimeAspectInterceptionIT,ToolInvocationProposalServiceConfirmTest test
git diff --check
```

Prohibited diff:

```bash
git diff --exit-code 8ae12ee..HEAD -- \
  src/main/java/com/springclaw/runtime \
  src/main/java/com/springclaw/service/agent/EngineSelector.java \
  src/main/java/com/springclaw/service/agent/AgentEngine.java \
  src/main/java/com/springclaw/tool/runtime \
  src/main/java/com/springclaw/service/proposal \
  src/main/java/com/springclaw/service/workspace \
  src/main/java/com/springclaw/dto \
  src/main/resources \
  .env.example
```

Commit:

```text
feat: project legacy execution into canonical lifecycle
```

Report commit SHA, changed files, test counts, and any environmental warning.
