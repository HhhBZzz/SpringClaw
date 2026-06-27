# Phase 3K2 Rename Active Runtime Projection Adapters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove misleading `Legacy*` names from active runtime lifecycle projection adapters without changing rollback behavior.

**Architecture:** Phase 3K1 deleted deprecated lifecycle shell names. Phase 3K2 handles the next safe cleanup: active adapters that still had `Legacy*` names but are not deletion targets. They project existing chat/runtime data into canonical lifecycle contracts. The rollback-only context projection remains supported through a rollback-specific name.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Maven.

---

## Scope

Rename:

- `LegacyExecutionDecisionAdapter` -> `RunExecutionDecisionProjector`
- `LegacyRunResultAdapter` -> `RunResultProjector`
- `LegacyRunContextAdapter` -> `RollbackRunContextAdapter`

Also rename the direct adapter test:

- `LegacyRuntimeAdaptersTest` -> `RuntimeProjectionAdaptersTest`

Keep untouched:

- `RunLifecycleObserver` behavior
- rollback `contextObserved` behavior when `springclaw.context.snapshot.factory-enabled=false`
- `ContextAssembler`
- `SemanticMemoryAdvisor`
- `MessageChatMemoryAdvisor`
- `LegacyContextViewAdapter`
- `LegacyContextView`
- trace/replay fallback and structured trace writes

---

## Tasks

### Task 1: RED quarantine test

- [x] Add `RuntimeProjectionAdapterNameQuarantineTest`.
- [x] Run:

```bash
mvn -q -Dtest=RuntimeProjectionAdapterNameQuarantineTest test
```

Expected before rename: fail because the three old adapter source files exist.

### Task 2: Rename active adapters

- [x] Move source files to canonical / rollback-specific names.
- [x] Update production injection points and tests.
- [x] Rename direct adapter tests to `RuntimeProjectionAdaptersTest`.

### Task 3: Verification gates

- [x] Run:

```bash
mvn -q -Dtest=RuntimeProjectionAdapterNameQuarantineTest,RuntimeProjectionAdaptersTest,RunLifecycleObserverTest,RunLifecycleObserverIntegrationTest,RunLifecycleObserverCanonicalModeTest,CanonicalContextReadyProjectorTest test
```

- [x] Run:

```bash
mvn -q -Dtest=ChatServiceImplLifecycleProjectionTest,ToolProposalLifecycleListenerTest,ToolProposalCleanupTaskTest,ToolProposalExecutionServiceTest,AsyncChatResultStoreProjectionTest,AcceptedRunCanonicalSmokeTest,ChatControllerCanonicalHttpSmokeTest test
```

- [x] Run:

```bash
rg -n "LegacyExecutionDecisionAdapter|LegacyRunResultAdapter|LegacyRunContextAdapter|LegacyRuntimeAdaptersTest" src/main/java src/test/java
```

Expected: no output.

- [x] Run:

```bash
mvn -q -DskipTests test
git diff --check
```

- [x] Update collaboration ledger.
- [ ] Commit.

---

## Acceptance Criteria

- Java production/test sources no longer reference the three old active adapter names.
- `RunLifecycleObserver` still has the same lifecycle behavior.
- Rollback context observation remains supported through `RollbackRunContextAdapter`.
- No rollback context/memory/trace/replay components are removed.
