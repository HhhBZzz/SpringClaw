# Unified Runtime Phase 2B Task 7 Projection Wiring Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make legacy async, trace, and proposal state stores projections of the canonical process-local run while preserving existing outward payloads and P0 tool safety.

**Architecture:** Canonical state is read from `RunStateRepository`; legacy stores never mutate it directly. Proposal lifecycle callbacks use `LegacyLifecycleObserver`, whose tool-event and rejection APIs were frozen in commit `1f51ba2`. Database proposal status remains authoritative for authorization, while `RunCoordinator` remains the sole canonical run-status writer.

**Tech Stack:** Java 17, Spring transactions/events, MyBatis-Plus, JUnit 5, Mockito, Maven Surefire.

---

## Ownership

Claude may modify:

```text
src/main/java/com/springclaw/service/chat/async/AsyncChatResultStore.java
src/main/java/com/springclaw/service/agent/AgentRunTraceService.java
src/main/java/com/springclaw/service/chat/impl/SseEventBridge.java
src/main/java/com/springclaw/service/proposal/ToolInvocationProposalService.java
src/main/java/com/springclaw/service/proposal/ToolProposalExecutionService.java
src/main/java/com/springclaw/service/proposal/ToolInvocationProposalRepository.java
src/main/java/com/springclaw/scheduled/ToolProposalCleanupTask.java
new proposal lifecycle event/listener types under service/proposal
their directly affected tests
docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
```

Do not modify:

```text
src/main/java/com/springclaw/runtime/**
src/main/java/com/springclaw/tool/runtime/**
src/main/java/com/springclaw/service/workspace/**
transport DTOs
configuration
```

## Task 7.1: Async result projection guards

- [ ] Inject `RunStateRepository` into `AsyncChatResultStore`.
- [ ] `markQueued` requires the run to exist and to be nonterminal.
- [ ] `markCompleted` requires canonical `COMPLETED` or `DEGRADED`; retain legacy
  outward payload status `COMPLETED`.
- [ ] `markFailed` requires canonical `FAILED`.
- [ ] A notification failure after `markCompleted` must not permit `markFailed` to
  overwrite the payload because the canonical run is not `FAILED`. Phase 2B does
  not redesign the consumer broad catch or claim delivery isolation.

Add tests for accepted queue projection, degraded-to-legacy-completed projection,
failed projection, and rejected status mismatch.

## Task 7.2: Trace status becomes a canonical projection

- [ ] Inject query-only `RunStateRepository` into `AgentRunTraceService`.
- [ ] `recordRunMetadata` obtains status from canonical state instead of inserting
  literal `RUNNING`.
- [ ] `upsertAgentRun` obtains status from canonical state instead of
  `toRunStatus(event)`.
- [ ] Delete or make unused `toRunStatus`.
- [ ] Trace events, quality, steps, tool rows, and existing payload JSON remain
  unchanged.
- [ ] If no canonical run exists, structured status projection must use `UNKNOWN`
  and must not infer status from the trace event. Diagnostic trace persistence may
  continue.
- [ ] A final-success trace cannot overwrite canonical `DEGRADED`, and a failed
  trace cannot overwrite canonical terminal status.

Update all direct constructors while preserving convenience overloads used outside
Spring.

## Task 7.3: Proposal-created suspension owner

- [ ] Add a proposal-created event containing `proposalId` and `runId`.
- [ ] Make `createPending` transactional, insert the proposal, publish the event,
  and return the inserted row.
- [ ] Add an AFTER_COMMIT listener that calls
  `LegacyLifecycleObserver.confirmationRequired(runId, proposalId, now)`.
- [ ] This listener is the unique owner of persisted tool-proposal suspension.
  Task 6 PendingToolApproval rendering must not call it again.

Do not alter proposal snapshots, hashes, target paths, risk, expiry, or tool context.

## Task 7.4: Approval, rejection, and expiry

- [ ] In `ToolProposalExecutionService.onExecutionRequested`, after loading the
  committed EXECUTING proposal and before opening tool context, call:

```text
observer.confirmationApproved(runId, now)
observer.toolStarted(runId, now)
```

- [ ] Add a rejection event published by `reject` after successful CAS. Its
  AFTER_COMMIT listener calls `observer.confirmationRejected`.
- [ ] Change `expirePendingBefore` from count-only bulk update to return the exact
  proposals that this invocation successfully changed to EXPIRED. Use per-row CAS
  with the proposal version so concurrent confirmation cannot be reported expired.
- [ ] `ToolProposalCleanupTask` calls
  `observer.failed(runId, "CONFIRMATION_EXPIRED", ..., now)` for each returned
  expired proposal.
- [ ] Existing count logging derives from returned-list size.

## Task 7.5: Frozen tool outcomes

- [ ] When `toolInvoker.invoke` returns normally, call
  `observer.toolSucceeded(runId, now)`.
- [ ] On execution failure, preserve existing proposal `markFailed`, then append
  `tool.failed` and transition the run to `FAILED` with code
  `TOOL_EXECUTION_FAILED`.
- [ ] Make repeated failure handling idempotent: only the repository call that
  actually changes a nonterminal proposal to FAILED may emit canonical failure.
- [ ] Tool success does not mark the run terminal and does not claim strategy
  continuation. Durable continuation remains Phase 4A.
- [ ] Do not modify `ToolRuntimeAspect` or `WorkspaceGitGuard`.

## Task 7.6: Verification

Run:

```bash
mvn -q -Dtest=AgentRunTraceServiceTest,ToolInvocationProposalServiceConfirmTest,ToolProposalExecutionServiceTest,ToolInvocationProposalRepositoryTest,ToolProposalCleanupTaskTest,TransportParityCharacterizationTest test
mvn -q -Dtest=LegacyLifecycleObserverTest,RunCoordinatorTest,ToolRuntimeAspectInterceptionIT,WorkspaceGitGuardTest test
git diff --check
```

The repository integration test may retain the known local MySQL authentication
environment error; report it separately and run all non-database focused tests.

Prohibited diff:

```bash
git diff --exit-code 1f51ba2..HEAD -- \
  src/main/java/com/springclaw/runtime \
  src/main/java/com/springclaw/tool/runtime \
  src/main/java/com/springclaw/service/workspace \
  src/main/java/com/springclaw/dto \
  src/main/resources \
  .env.example
```

Commit:

```text
feat: project canonical lifecycle to legacy status stores
```

Report commit SHA, changed files, test counts, environmental failures, and the
explicit limitation that successful frozen tool execution does not yet resume the
original strategy continuation.
