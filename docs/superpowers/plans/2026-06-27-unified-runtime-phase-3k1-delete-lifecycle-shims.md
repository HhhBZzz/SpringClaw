# Phase 3K1 Delete Deprecated Lifecycle Shims Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the three deprecated lifecycle compatibility shims identified by Phase 3K without changing runtime behavior.

**Architecture:** Production code already uses `RunLifecycleBridge`, `DefaultRunLifecycleBridge`, and `RunLifecycleObserver`. This phase deletes only the deprecated shell names and mechanically migrates tests/fixtures to the canonical names. Rollback context, memory, trace/replay fallback, and active `Legacy*` adapters remain untouched.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Mockito, AssertJ, Maven.

---

## Scope

- Delete:
  - `src/main/java/com/springclaw/runtime/bridge/LegacyRuntimeBridge.java`
  - `src/main/java/com/springclaw/runtime/bridge/DefaultLegacyRuntimeBridge.java`
  - `src/main/java/com/springclaw/runtime/bridge/LegacyLifecycleObserver.java`
- Migrate tests from deprecated shell names to canonical names.
- Keep:
  - `LegacyRunContextAdapter`
  - `LegacyExecutionDecisionAdapter`
  - `LegacyRunResultAdapter`
  - `LegacyContextViewAdapter`
  - `LegacyContextView`
  - `ContextAssembler`
  - `SemanticMemoryAdvisor`
  - all trace/replay fallback code

---

## Tasks

### Task 1: RED architecture guard

- [x] Add `deprecatedLegacyLifecycleShimSourcesAreRemoved()` to `LegacyLifecycleNameQuarantineTest`.
- [x] Run:

```bash
mvn -q -Dtest=LegacyLifecycleNameQuarantineTest#deprecatedLegacyLifecycleShimSourcesAreRemoved test
```

Expected before deletion: fail because the three deprecated source files still exist.

### Task 2: Delete shims and migrate tests

- [x] Delete the three deprecated source files.
- [x] Rename direct compatibility tests to canonical names:
  - `LegacyRuntimeBridgeTest` -> `RunLifecycleBridgeCompatibilityTest`
  - `LegacyLifecycleObserverTest` -> `RunLifecycleObserverIntegrationTest`
  - `LegacyLifecycleObserverCanonicalModeTest` -> `RunLifecycleObserverCanonicalModeTest`
  - `LegacyLifecycleObserverCanonicalModeTestContext` -> `RunLifecycleObserverTestContext`
- [x] Replace test fixtures:
  - `LegacyRuntimeBridge` -> `RunLifecycleBridge`
  - `DefaultLegacyRuntimeBridge` -> `DefaultRunLifecycleBridge`
  - `LegacyLifecycleObserver` -> `RunLifecycleObserver`
- [x] Run:

```bash
mvn -q -Dtest=LegacyLifecycleNameQuarantineTest,RunLifecycleBridgeTest,RunLifecycleBridgeCompatibilityTest,RunLifecycleObserverTest,RunLifecycleObserverIntegrationTest,RunLifecycleObserverCanonicalModeTest test
```

Expected: pass.

### Task 3: Regression gates

- [x] Run:

```bash
mvn -q -Dtest=ChatControllerAuthTest,WebhookRouterServiceTest,TaskExecutionServiceTest test
```

- [x] Run:

```bash
mvn -q -Dtest=ChatServiceImplLifecycleProjectionTest,ToolProposalLifecycleListenerTest,ToolProposalCleanupTaskTest,ToolProposalExecutionServiceTest,AsyncChatResultStoreProjectionTest,AcceptedRunCanonicalSmokeTest,ChatControllerCanonicalHttpSmokeTest test
```

- [x] Run:

```bash
rg -n "LegacyRuntimeBridge|DefaultLegacyRuntimeBridge|LegacyLifecycleObserver" src/main/java src/test/java
```

Expected: no output.

- [x] Run:

```bash
mvn -q -DskipTests test
git diff --check
```

- [x] Update collaboration ledger.
- [x] Commit.

---

## Acceptance Criteria

- Deprecated lifecycle shim source files are deleted.
- Production and test code no longer mention `LegacyRuntimeBridge`, `DefaultLegacyRuntimeBridge`, or `LegacyLifecycleObserver`.
- Canonical lifecycle bridge/observer tests still pass.
- Ingress, webhook, task, chat lifecycle, proposal, async result, and canonical smoke tests still pass.
- No rollback context/memory/trace/replay components are modified or deleted.
