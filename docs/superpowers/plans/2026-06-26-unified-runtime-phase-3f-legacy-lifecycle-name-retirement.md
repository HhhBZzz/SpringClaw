# Unified Runtime Phase 3F Legacy Lifecycle Name Retirement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `Legacy*` lifecycle bridge/observer dependencies from production wiring while preserving rollback-only legacy context and advisor components.

**Architecture:** This phase does not delete rollback components (`ContextAssembler`, `SemanticMemoryAdvisor`, `LegacyRunContextAdapter`, `LegacyContextViewAdapter`). It adds canonical lifecycle names (`RunLifecycleBridge`, `RunLifecycleObserver`, `DefaultRunLifecycleBridge`) and migrates production callers to them. Existing `LegacyRuntimeBridge`, `DefaultLegacyRuntimeBridge`, and `LegacyLifecycleObserver` remain as deprecated compatibility shims for tests and any older code paths.

**Tech Stack:** Java 17, Spring Boot 3.5, JUnit 5, AssertJ, TDD with focused architecture and lifecycle tests.

---

## Scope Boundaries

Do not remove these in Phase 3F:

- `ContextAssembler`: still required when `springclaw.context.snapshot.factory-enabled=false`.
- `SemanticMemoryAdvisor` and `MessageChatMemoryAdvisor`: still required in rollback mode.
- `LegacyRunContextAdapter`: still required by rollback lifecycle context observation and compatibility tests.
- `LegacyContextViewAdapter`: still required by canonical path until engines consume `ContextSnapshot`/`ContextInjection` directly.

Allowed changes:

- Add canonical lifecycle bridge/observer names.
- Migrate production imports and constructor parameters away from `LegacyRuntimeBridge` and `LegacyLifecycleObserver`.
- Keep old names as deprecated shims.
- Add architecture tests proving production wiring no longer imports the legacy lifecycle names.

---

## Files

- Create: `src/main/java/com/springclaw/runtime/bridge/RunLifecycleBridge.java`
  - Canonical lifecycle projection interface with the current `LegacyRuntimeBridge` method set.
- Create: `src/main/java/com/springclaw/runtime/bridge/DefaultRunLifecycleBridge.java`
  - Canonical implementation delegating to `RunCoordinator`.
- Modify: `src/main/java/com/springclaw/runtime/bridge/LegacyRuntimeBridge.java`
  - Mark deprecated and make it extend `RunLifecycleBridge`.
- Modify: `src/main/java/com/springclaw/runtime/bridge/DefaultLegacyRuntimeBridge.java`
  - Mark deprecated and extend `DefaultRunLifecycleBridge`.
- Create: `src/main/java/com/springclaw/runtime/bridge/RunLifecycleObserver.java`
  - Canonical observer implementation currently held by `LegacyLifecycleObserver`.
- Modify: `src/main/java/com/springclaw/runtime/bridge/LegacyLifecycleObserver.java`
  - Mark deprecated and make it extend `RunLifecycleObserver`.
- Modify production callers:
  - `src/main/java/com/springclaw/controller/ChatController.java`
  - `src/main/java/com/springclaw/service/webhook/WebhookRouterService.java`
  - `src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java`
  - `src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java`
  - `src/main/java/com/springclaw/service/proposal/ToolProposalLifecycleListener.java`
  - `src/main/java/com/springclaw/scheduled/ToolProposalCleanupTask.java`
- Create: `src/test/java/com/springclaw/architecture/LegacyLifecycleNameQuarantineTest.java`
  - Fails if production code outside `runtime/bridge` imports `LegacyRuntimeBridge` or `LegacyLifecycleObserver`.
- Create/modify focused tests:
  - `src/test/java/com/springclaw/runtime/bridge/RunLifecycleBridgeTest.java`
  - `src/test/java/com/springclaw/runtime/bridge/RunLifecycleObserverTest.java`
- Modify ledger:
  - `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`

---

### Task 1: Architecture Guard for Production Legacy Lifecycle Names

**Files:**
- Create: `src/test/java/com/springclaw/architecture/LegacyLifecycleNameQuarantineTest.java`

- [ ] **Step 1: Write failing architecture test**

Create a test that walks `src/main/java`, excludes `src/main/java/com/springclaw/runtime/bridge`, and fails if a Java file contains either:

```java
import com.springclaw.runtime.bridge.LegacyRuntimeBridge;
import com.springclaw.runtime.bridge.LegacyLifecycleObserver;
```

- [ ] **Step 2: Run RED**

```bash
mvn -q -Dtest=LegacyLifecycleNameQuarantineTest test
```

Expected: fail, listing production files such as `ChatController`, `ChatServiceImpl`, `WebhookRouterService`, `TaskExecutionService`, `ToolProposalLifecycleListener`, and `ToolProposalCleanupTask`.

---

### Task 2: Add Canonical Lifecycle Bridge Names

**Files:**
- Create: `src/main/java/com/springclaw/runtime/bridge/RunLifecycleBridge.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/DefaultRunLifecycleBridge.java`
- Modify: `src/main/java/com/springclaw/runtime/bridge/LegacyRuntimeBridge.java`
- Modify: `src/main/java/com/springclaw/runtime/bridge/DefaultLegacyRuntimeBridge.java`
- Test: `src/test/java/com/springclaw/runtime/bridge/RunLifecycleBridgeTest.java`

- [ ] **Step 1: Write canonical bridge test**

Create `RunLifecycleBridgeTest` that constructs `DefaultRunLifecycleBridge` with a real `RunCoordinator` and `InMemoryRunLifecycleStore`, accepts a run, projects context/decision/running, and asserts event order.

- [ ] **Step 2: Run RED**

```bash
mvn -q -Dtest=RunLifecycleBridgeTest test
```

Expected: compile failure because `RunLifecycleBridge` and `DefaultRunLifecycleBridge` do not exist.

- [ ] **Step 3: Implement canonical bridge**

Move the method signatures from `LegacyRuntimeBridge` into `RunLifecycleBridge`. Implement `DefaultRunLifecycleBridge` by delegating to `RunCoordinator`.

- [ ] **Step 4: Preserve compatibility**

Change:

```java
@Deprecated
public interface LegacyRuntimeBridge extends RunLifecycleBridge {
}
```

Change:

```java
@Deprecated
public final class DefaultLegacyRuntimeBridge extends DefaultRunLifecycleBridge
        implements LegacyRuntimeBridge {
    public DefaultLegacyRuntimeBridge(RunCoordinator coordinator) {
        super(coordinator);
    }
}
```

`DefaultRunLifecycleBridge` must not be `final`, otherwise the compatibility shim cannot extend it.

- [ ] **Step 5: Run GREEN**

```bash
mvn -q -Dtest=RunLifecycleBridgeTest,LegacyRuntimeBridgeTest test
```

Expected: pass.

---

### Task 3: Add Canonical Lifecycle Observer Name

**Files:**
- Create: `src/main/java/com/springclaw/runtime/bridge/RunLifecycleObserver.java`
- Modify: `src/main/java/com/springclaw/runtime/bridge/LegacyLifecycleObserver.java`
- Test: `src/test/java/com/springclaw/runtime/bridge/RunLifecycleObserverTest.java`

- [ ] **Step 1: Write canonical observer test**

Create `RunLifecycleObserverTest` equivalent to the key `LegacyLifecycleObserverTest` behavior:

- in canonical mode, `contextAndDecisionObserved` must emit decision but not duplicate context observation;
- in rollback mode, it must emit both context and decision.

- [ ] **Step 2: Run RED**

```bash
mvn -q -Dtest=RunLifecycleObserverTest test
```

Expected: compile failure because `RunLifecycleObserver` does not exist.

- [ ] **Step 3: Implement `RunLifecycleObserver`**

Move the current constructor fields and methods from `LegacyLifecycleObserver` into `RunLifecycleObserver`, changing the bridge type to `RunLifecycleBridge`.

- [ ] **Step 4: Preserve compatibility shim**

Change `LegacyLifecycleObserver` into a deprecated subclass:

```java
@Deprecated
public class LegacyLifecycleObserver extends RunLifecycleObserver {
    public LegacyLifecycleObserver(
            LegacyRuntimeBridge bridge,
            LegacyRunContextAdapter contextAdapter,
            LegacyExecutionDecisionAdapter decisionAdapter,
            LegacyRunResultAdapter resultAdapter,
            boolean contextSnapshotFactoryEnabled
    ) {
        super(bridge, contextAdapter, decisionAdapter, resultAdapter,
                contextSnapshotFactoryEnabled);
    }

    public LegacyLifecycleObserver(
            LegacyRuntimeBridge bridge,
            LegacyRunContextAdapter contextAdapter,
            LegacyExecutionDecisionAdapter decisionAdapter,
            LegacyRunResultAdapter resultAdapter
    ) {
        super(bridge, contextAdapter, decisionAdapter, resultAdapter);
    }
}
```

`RunLifecycleObserver` must not be `final`.

- [ ] **Step 5: Run GREEN**

```bash
mvn -q -Dtest=RunLifecycleObserverTest,LegacyLifecycleObserverTest,LegacyLifecycleObserverCanonicalModeTest test
```

Expected: pass.

---

### Task 4: Migrate Production Callers to Canonical Names

**Files:**
- Modify:
  - `src/main/java/com/springclaw/controller/ChatController.java`
  - `src/main/java/com/springclaw/service/webhook/WebhookRouterService.java`
  - `src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java`
  - `src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java`
  - `src/main/java/com/springclaw/service/proposal/ToolProposalLifecycleListener.java`
  - `src/main/java/com/springclaw/scheduled/ToolProposalCleanupTask.java`

- [ ] **Step 1: Replace production imports**

Replace production imports:

```java
import com.springclaw.runtime.bridge.LegacyRuntimeBridge;
import com.springclaw.runtime.bridge.LegacyLifecycleObserver;
```

with:

```java
import com.springclaw.runtime.bridge.RunLifecycleBridge;
import com.springclaw.runtime.bridge.RunLifecycleObserver;
```

Update field and constructor parameter types accordingly. Do not change method calls.

- [ ] **Step 2: Run architecture test**

```bash
mvn -q -Dtest=LegacyLifecycleNameQuarantineTest test
```

Expected: pass.

- [ ] **Step 3: Run focused production wiring tests**

```bash
mvn -q -Dtest=ChatControllerAuthTest,WebhookRouterServiceTest,TaskExecutionServiceTest,ChatServiceImplLifecycleProjectionTest,ToolProposalLifecycleListenerTest,ToolProposalCleanupTaskTest test
```

Expected: pass.

---

### Task 5: Final Gate and Ledger

**Files:**
- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`

- [x] **Step 1: Run Phase 3F focused gate**

```bash
mvn -q -Dtest=LegacyLifecycleNameQuarantineTest,RunLifecycleBridgeTest,RunLifecycleObserverTest,LegacyRuntimeBridgeTest,LegacyLifecycleObserverTest,LegacyLifecycleObserverCanonicalModeTest,ChatControllerAuthTest,WebhookRouterServiceTest,TaskExecutionServiceTest,ChatServiceImplLifecycleProjectionTest,ToolProposalLifecycleListenerTest,ToolProposalCleanupTaskTest test
```

Expected: pass.

- [x] **Step 2: Run current canonical smoke gate**

```bash
set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a;
mvn -q -Dtest=CanonicalRetrievalBoundaryTest,ConversationAdvisorSupportCanonicalModeTest,ChatControllerCanonicalHttpSmokeTest,ChatControllerSpringBootCanonicalSmokeIT,MySqlRunLifecycleStoreIT test
```

Expected: pass.

- [x] **Step 3: Update ledger**

Append Phase 3F results to `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`, including:

- branch name;
- legacy lifecycle names retired from production imports;
- compatibility shims retained;
- tests run;
- rollback: revert Phase 3F commit.

- [x] **Step 4: Commit**

```bash
git add src/main/java/com/springclaw/runtime/bridge/RunLifecycleBridge.java \
        src/main/java/com/springclaw/runtime/bridge/DefaultRunLifecycleBridge.java \
        src/main/java/com/springclaw/runtime/bridge/LegacyRuntimeBridge.java \
        src/main/java/com/springclaw/runtime/bridge/DefaultLegacyRuntimeBridge.java \
        src/main/java/com/springclaw/runtime/bridge/RunLifecycleObserver.java \
        src/main/java/com/springclaw/runtime/bridge/LegacyLifecycleObserver.java \
        src/main/java/com/springclaw/controller/ChatController.java \
        src/main/java/com/springclaw/service/webhook/WebhookRouterService.java \
        src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java \
        src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java \
        src/main/java/com/springclaw/service/proposal/ToolProposalLifecycleListener.java \
        src/main/java/com/springclaw/scheduled/ToolProposalCleanupTask.java \
        src/test/java/com/springclaw/architecture/LegacyLifecycleNameQuarantineTest.java \
        src/test/java/com/springclaw/runtime/bridge/RunLifecycleBridgeTest.java \
        src/test/java/com/springclaw/runtime/bridge/RunLifecycleObserverTest.java \
        docs/superpowers/plans/2026-06-26-unified-runtime-phase-3f-legacy-lifecycle-name-retirement.md \
        docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
git commit -m "refactor: retire legacy lifecycle names from production wiring"
```

---

## Acceptance Criteria

- No production file outside `com.springclaw.runtime.bridge` imports `LegacyRuntimeBridge` or `LegacyLifecycleObserver`.
- `RunLifecycleBridge` and `RunLifecycleObserver` are the production-facing lifecycle names.
- Deprecated legacy bridge/observer shims remain available for compatibility.
- Rollback context and advisor behavior is unchanged.
- Canonical retrieval, HTTP smoke, and MySQL lifecycle gates still pass.
