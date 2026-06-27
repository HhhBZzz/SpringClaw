# Phase 3K Legacy / Rollback Retirement Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decide which legacy runtime, context, memory, trace, and replay components can be retired after PR #1–#13, and define a safe deletion order.

**Architecture:** This phase is documentation-only. It audits production references, rollback flags, and test coverage, then classifies each legacy component as delete-now, keep-for-rollback, migrate-first, or keep-as-product-data. No production Java, schema, or configuration behavior changes are made in Phase 3K.

**Tech Stack:** Java 17, Spring Boot, Maven, ripgrep, Git.

---

## Scope

- Audit only; do not modify production code.
- Use current base `codex/bootstrap-github @ 41ef582`.
- Treat the following default flags as current behavior:
  - `springclaw.context.snapshot.factory-enabled=true`
  - `springclaw.memory.frame.enabled=true`
  - `springclaw.runtime.lifecycle.store=memory` unless overridden to MySQL
  - `springclaw.chat.spring-ai-chat-memory-enabled=false`
- Keep rollback mode explicit:
  - `SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false`
  - `SPRINGCLAW_MEMORY_FRAME_ENABLED=false`

---

## Commands run

- [x] Baseline compile gate:

```bash
mvn -q -DskipTests test
```

- [x] Production/test reference scan:

```bash
rg -n "ContextAssembler|SemanticMemoryAdvisor|MessageChatMemoryAdvisor|LegacyRunContextAdapter|LegacyContextViewAdapter|LegacyRuntimeBridge|DefaultLegacyRuntimeBridge|LegacyLifecycleObserver|ContextInjection|AssembledContext|MessageEventService|agent_run_step|tool_invocation|fallback|rollback|factory-enabled" src/main/java src/test/java docs/superpowers/plans docs
```

- [x] Focused production scans:

```bash
rg -n "import com\\.springclaw\\.runtime\\.bridge\\.Legacy|LegacyRuntimeBridge|LegacyLifecycleObserver" src/main/java
rg -n "ContextAssembler|LegacyContextViewAdapter|AssembledContext|ContextInjection" src/main/java/com/springclaw/service/chat/impl src/main/java/com/springclaw/runtime src/main/java/com/springclaw/service/context
rg -n "SemanticMemoryAdvisor|MessageChatMemoryAdvisor|spring-ai-chat-memory-enabled|contextSnapshotFactoryEnabled" src/main/java src/test/java
rg -n "recordSingle\\(|listRequestEvents\\(|pageQuery\\(|listSessionEvents\\(|listRecent\\(|recordRunMetadata\\(|record\\(" src/main/java/com/springclaw
```

---

## Executive decision

Do **not** delete the big rollback/context/memory components yet.

The only low-risk deletion candidates are the deprecated lifecycle name shims:

- `LegacyRuntimeBridge`
- `DefaultLegacyRuntimeBridge`
- `LegacyLifecycleObserver`

Everything else either still backs an explicit rollback path, still feeds engine prompt shapes, or still stores product/user data outside the new canonical lifecycle.

---

## Component audit table

| Component / area | Production use after PR #13 | Test protection | Classification | Decision |
|---|---|---|---|---|
| `LegacyRuntimeBridge` | Only inside deprecated shim files under `runtime/bridge`; production callers use `RunLifecycleBridge` | `LegacyRuntimeBridgeTest`, compatibility tests | Delete-now candidate | Safe first deletion slice after removing tests that intentionally instantiate it |
| `DefaultLegacyRuntimeBridge` | Only deprecated adapter extending `DefaultRunLifecycleBridge` | compatibility tests | Delete-now candidate | Safe with `LegacyRuntimeBridge` deletion |
| `LegacyLifecycleObserver` | Only deprecated adapter extending `RunLifecycleObserver` | `LegacyLifecycleObserver*` tests and older test fixtures | Delete-now candidate | Safe after updating tests/fixtures to use `RunLifecycleObserver` |
| `RunLifecycleBridge` / `DefaultRunLifecycleBridge` | Active canonical lifecycle interface/implementation | `RunLifecycleBridgeTest`, ingress tests | Keep | Not legacy after Phase 3F |
| `RunLifecycleObserver` | Active production observer; still takes `LegacyRunContextAdapter` for rollback `contextObserved` only | `RunLifecycleObserverTest`, canonical mode tests | Keep | Do not delete |
| `LegacyRunContextAdapter` | Active dependency of `RunLifecycleObserver`; used only when `contextSnapshotFactoryEnabled=false` | lifecycle observer tests | Keep-for-rollback | Remove only after deleting rollback context observation |
| `LegacyExecutionDecisionAdapter` | Active dependency of `RunLifecycleObserver`; maps current `ChatContext`/decision to canonical `ExecutionDecision` | lifecycle tests | Keep | Name is legacy-flavored but function is active projection |
| `LegacyRunResultAdapter` | Active dependency of `RunLifecycleObserver`; maps current execution result to canonical terminal values | lifecycle tests | Keep | Name is legacy-flavored but function is active projection |
| `LegacyContextViewAdapter` / `LegacyContextView` | Active canonical path adapter: `ContextSnapshot` -> `AssembledContext` + `ContextInjection` for existing engines | `LegacyContextViewAdapterTest`, `ChatContextFactory*` tests | Migrate-first | Cannot delete until engines consume `ContextSnapshot` directly |
| `ContextAssembler` | Active rollback path when `springclaw.context.snapshot.factory-enabled=false`; not called in canonical mode | `CanonicalRetrievalBoundaryTest`, `ChatContextFactoryCanonical*`, `ContextAssemblerTest` | Keep-for-rollback | Delete only after formally removing rollback flag |
| `AssembledContext` | Active engine-facing context type across OPAR/local/SSE/meta-guard paths | many engine tests | Migrate-first | Cannot delete until `ChatContext` and engines use canonical context types |
| `ContextInjection` | Active prompt injection contract used by simplified OPAR and canonical context projection | prompt/context tests | Migrate-first | Cannot delete until prompt renderers consume `ContextSnapshot`/typed sections |
| `SemanticMemoryAdvisor` | Still wired into `ConversationAdvisorSupport`; suppressed when canonical snapshot mode is enabled | `ConversationAdvisorSupportCanonicalModeTest`, characterization tests | Keep-for-rollback | Delete only after rollback advisor mode is removed |
| `MessageChatMemoryAdvisor` / `ChatMemoryConfig` | Bean still exists; used only when `springclaw.chat.spring-ai-chat-memory-enabled=true` and canonical snapshot mode is false | advisor tests | Keep-for-rollback / optional product flag | Do not delete without removing Spring AI chat memory feature flag |
| `MemoryCoordinator` / memory frame contracts | Active canonical context dependency through `ContextSnapshotFactory` | memory frame tests, Spring wiring tests | Keep | Canonical, not legacy |
| `message_event` chat history | Still product data source for chat history, audit pages, provider/routing audits, webhook/task records, recovery, and legacy trace fallback | controller/service tests | Keep-as-product-data | Do not delete table/service |
| `message_event` SYSTEM/TRACE fallback | Fallback for `listTrace()` and `recentRuns()` when canonical data is absent | `AgentRunTraceServiceTest` | Keep-for-fallback | Can be retired only after canonical lifecycle is durable by default and old rows are migrated or accepted as historical-only |
| `agent_run` / `agent_run_step` / `tool_invocation` structured tables | Still written by `AgentRunTraceService.record*`; replay fallback; quality/product metadata; runtime schema tests | `TurnContractTest`, schema tests, trace tests | Migrate-first | Do not delete until canonical replaces structured trace writes and ToolInvocation details are populated |
| `AgentRunTraceService.recordStructuredTrace` writes | Still writes legacy structured runtime rows | `AgentRunTraceServiceTest`, `TurnContractTest` | Migrate-first | Next major migration candidate, not a deletion-only task |
| Admin replay legacy SQL fallback | Still fallback when canonical state absent | `TurnContractTest`, Phase 3J tests | Keep-for-fallback | Can retire after canonical durability + historical compatibility decision |
| Runtime-console schema initializer | Owns legacy structured tables and quality/product columns | schema tests | Migrate-first | Keep until structured table writes are retired |

---

## Current production boundaries

### 1. Canonical default path

Default runtime now uses:

```text
Accepted run id
  -> RunLifecycleBridge / RunCoordinator
  -> ContextSnapshotFactory
  -> CanonicalContextReadyProjector
  -> RunEventStore / RunStateRepository
```

External read paths now prefer canonical:

- `/api/chat/runs/{requestId}/trace`
- `/api/runtime-console/runs`
- `/api/runtime-console/overview` run summary
- `/api/admin/manage/runs/{requestId}/replay`

### 2. Explicit rollback path

Rollback still exists by design:

```text
SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false
SPRINGCLAW_MEMORY_FRAME_ENABLED=false
```

That path still needs:

- `ContextAssembler`
- `SemanticMemoryAdvisor`
- optional `MessageChatMemoryAdvisor`
- `LegacyRunContextAdapter` inside `RunLifecycleObserver`

### 3. Engine shape compatibility

Even in canonical mode, existing engines still expect:

- `AssembledContext`
- `ContextInjection`
- `observePrompt`
- `ContextSourceSummary`

Therefore `LegacyContextViewAdapter` is not a rollback-only shim. It is an active compatibility adapter for canonical context.

---

## Recommended deletion / migration order

### Phase 3K1 — Delete deprecated lifecycle name shims

Scope:

- Remove `LegacyRuntimeBridge`
- Remove `DefaultLegacyRuntimeBridge`
- Remove `LegacyLifecycleObserver`
- Update tests/fixtures to use:
  - `RunLifecycleBridge`
  - `DefaultRunLifecycleBridge`
  - `RunLifecycleObserver`

Why first:

- Production code already stopped importing the deprecated lifecycle names.
- This is a small, mechanically verifiable deletion.
- It reduces confusion without touching rollback context behavior.

Required tests:

```bash
mvn -q -Dtest=LegacyLifecycleNameQuarantineTest,RunLifecycleBridgeTest,RunLifecycleObserverTest,ChatControllerAuthTest,WebhookRouterServiceTest,TaskExecutionServiceTest,ChatServiceImplLifecycleProjectionTest,ToolProposalLifecycleListenerTest,ToolProposalCleanupTaskTest,ToolProposalExecutionServiceTest test
mvn -q -DskipTests test
```

Acceptance:

- `rg -n "LegacyRuntimeBridge|DefaultLegacyRuntimeBridge|LegacyLifecycleObserver" src/main/java src/test/java` returns no production/test usage except historical docs if retained.

### Phase 3K2 — Rename active "Legacy" projection adapters, do not delete

Scope:

- Rename `LegacyExecutionDecisionAdapter` -> `RunExecutionDecisionProjector`
- Rename `LegacyRunResultAdapter` -> `RunResultProjector`
- Consider renaming `LegacyRunContextAdapter` -> `RollbackRunContextAdapter`

Why second:

- These classes are active behavior, not safe deletions.
- Renaming makes the architecture clearer before any deletion.

Hard boundary:

- Do not remove rollback `contextObserved` behavior in this phase.

### Phase 3L — Engine context migration plan

Scope:

- Design how engines consume typed canonical context instead of `AssembledContext`/`ContextInjection`.
- Replace `LegacyContextViewAdapter` only after engine contracts are ready.

Why separate:

- This touches prompt rendering, OPAR/local execution, SSE context summaries, and meta-guard fallback.
- It is larger than a deletion cleanup.

### Phase 3M — Structured trace write retirement

Scope:

- Stop writing duplicate structured rows to `agent_run`, `agent_run_step`, `tool_invocation` once canonical has equivalent data.
- First fill the known gap: `RunState.toolInvocations()` is currently not populated, so canonical replay cannot yet replace structured `tool_invocation` details.

Hard boundary:

- Do not remove schema/migrations until product owners accept historical data behavior.

### Phase 3N — Remove rollback context mode

Scope:

- Remove `springclaw.context.snapshot.factory-enabled=false` support.
- Delete `ContextAssembler`, rollback advisor wiring, and rollback lifecycle context observation.

Prerequisite:

- Explicit product decision that rollback mode is no longer required.
- At least one release with canonical mode stable in the target deployment.

---

## Do-not-delete list for the next coding slice

Do not delete these in Phase 3K1:

- `ContextAssembler`
- `AssembledContext`
- `ContextInjection`
- `LegacyContextViewAdapter`
- `LegacyContextView`
- `LegacyRunContextAdapter`
- `LegacyExecutionDecisionAdapter`
- `LegacyRunResultAdapter`
- `SemanticMemoryAdvisor`
- `MessageChatMemoryAdvisor`
- `MessageEventService`
- `message_event`
- `agent_run`
- `agent_run_step`
- `tool_invocation`
- `RuntimeConsoleSchemaInitializer`

---

## Immediate next implementation plan

The next safe coding task is:

```text
Phase 3K1: Delete deprecated lifecycle name shims
```

Expected change type:

- Delete 3 deprecated production files.
- Update tests that intentionally instantiate deprecated names.
- Keep canonical `RunLifecycleBridge` and `RunLifecycleObserver` behavior unchanged.

Expected risk:

- Low.

Reason:

- Production imports already show no non-shim usage of `LegacyRuntimeBridge` or `LegacyLifecycleObserver`.
