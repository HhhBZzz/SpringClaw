# Unified Runtime Phase 2B Task 8 Acceptance Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:verification-before-completion before claiming Phase 2B complete.

**Goal:** Verify the complete Phase 2B lifecycle bridge after Task 6 and Task 7 are committed, record exact evidence, and distinguish code failures from local infrastructure failures.

**Owner:** Codex

---

## Gate 1: Repository scope

The worktree must be clean. Review all Phase 2B integration changes from the core
handoff:

```bash
git status --short
git diff --check 56dc753..HEAD
git diff --name-status 56dc753..HEAD
```

The following files must remain unchanged after their owning Codex commits:

```bash
git diff --exit-code 1f51ba2..HEAD -- \
  src/main/java/com/springclaw/runtime \
  src/main/java/com/springclaw/tool/runtime \
  src/main/java/com/springclaw/service/workspace \
  src/main/java/com/springclaw/dto \
  src/main/resources \
  .env.example
```

Any required core change must have a separate Codex-reviewed commit and updated
baseline SHA.

## Gate 2: Contract and lifecycle core

```bash
mvn -q -Dtest=RunStatusTest,ContextAndDecisionContractTest,ToolCompletionAndResultContractTest,RunEventContractTest,RunStateContractTest,RuntimeStrategyContractTest,LegacyRuntimeContractFixturesTest,InMemoryRunLifecycleStoreTest,RunCoordinatorTest,LegacyRuntimeBridgeTest,LegacyRuntimeAdaptersTest,LegacyLifecycleObserverTest test
```

Required: zero failures and zero errors.

## Gate 3: Characterization and Phase 2A compatibility

```bash
mvn -q -Dtest=RuntimeRouteCharacterizationTest,ContextPropagationCharacterizationTest,ToolSafetyPathCharacterizationTest,FinalAnswerOwnershipCharacterizationTest,TransportParityCharacterizationTest,DefaultRunIdentityFactoryTest,CanonicalTransportIdentityTest,CanonicalToolOwnershipTest test
```

Required: zero failures and zero errors. Dynamic/nested tests are counted from
Surefire XML testcase elements rather than the outer-suite `tests` attribute.

## Gate 4: Phase 2B ingress and execution observations

```bash
mvn -q -Dtest=EngineSelectorTest,ChatControllerAuthTest,ChatMessageConsumerTest,WebhookRouterServiceTest,TaskExecutionServiceTest,ChatServiceImplLifecycleProjectionTest,ChatServiceImplModeTest,ChatServiceImplPersistenceTest,ChatServiceImplPendingApprovalTest,PromptInjectionTest test
```

Required:

- every accepted ingress creates or claims one canonical run;
- blocking and SSE execution reach `DEGRADED`, `FAILED`, or
  `WAITING_CONFIRMATION`;
- no legacy success is projected as canonical `COMPLETED`;
- existing answers and persistence assertions remain unchanged.

## Gate 5: Async, trace, proposal, and P0 safety

Load the local database environment without printing secrets:

```bash
set -a
source /Users/hanbingzheng/springclaw/.env.local
set +a
```

Run:

```bash
mvn -q -Dtest=AsyncChatResultStoreProjectionTest,AgentRunTraceServiceTest,ToolInvocationProposalServiceConfirmTest,ToolProposalLifecycleListenerTest,ToolProposalExecutionServiceTest,ToolProposalCleanupTaskTest,ToolInvocationProposalRepositoryTest,ToolRuntimeAspectInterceptionIT,WorkspaceGitGuardTest test
```

Required:

- async statuses are canonical projections;
- trace status is never inferred from trace payload;
- persisted proposal creation owns `WAITING_CONFIRMATION`;
- approval/rejection/expiry affect the same run;
- frozen tool execution still passes through `ToolRuntimeAspect`;
- write safety and workspace fencing remain unchanged.

If database integration fails, first verify connectivity:

```bash
MYSQL_PWD="$MYSQL_PASSWORD" /usr/local/mysql/bin/mysql \
  --protocol=TCP \
  -h "$MYSQL_HOST" \
  -P "$MYSQL_PORT" \
  -u "$MYSQL_USER" \
  -D "$MYSQL_DB" \
  -Nse 'SELECT DATABASE(), CURRENT_USER(), VERSION();'
```

Do not classify an authentication failure as a product assertion failure.

## Gate 6: Focused baseline

```bash
mvn -q -Dtest=EngineSelectorTest,ChatContextFactoryTest,PromptInjectionTest,AgentRuntimeEngineTest,ToolRuntimeAspectInterceptionIT,ToolInvocationProposalServiceConfirmTest test
```

Required: zero failures and zero errors.

## Gate 7: Full suite

With `.env.local` loaded:

```bash
mvn -q test
```

Record exact tests, failures, errors, and skips. Phase 2B is not reported fully
green if the command exits nonzero, even if focused suites pass.

## Gate 8: Final bounded review

Review only these P0/P1 invariants:

```text
one canonical RunState per accepted request
state/event atomicity and revision fencing
no synthetic canonical completion
trace and async stores cannot mutate canonical status
proposal create/approve/reject/expiry remain on one run
tool execution still passes ToolRuntimeAspect and workspace guards
legacy answer, persistence, routing, locks, and stream termination remain owners
no restart durability, exactly-once, or durable continuation claim
```

## Evidence commit

Update:

```text
docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
```

Include:

- all Task 4A–7 commit SHAs;
- files changed by responsibility;
- exact test counts and commands;
- database/environment findings;
- known process-local and continuation limitations;
- rollback order: Task 7 projections, Task 6 observations, Task 5 ingress, then
  lifecycle core, retaining Phase 2A identity.

Commit:

```text
docs: close phase 2b lifecycle bridge evidence
```
