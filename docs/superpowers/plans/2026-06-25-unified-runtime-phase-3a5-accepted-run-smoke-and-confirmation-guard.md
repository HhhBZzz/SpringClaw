# Phase 3A5 Accepted Run Smoke and Confirmation Guard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the merged canonical context path works on a real accepted run and close the stale confirmation approval state-machine gap.

**Architecture:** Keep Phase 3A5 narrow. Add an explicit `WAITING_CONFIRMATION` guard to `RunCoordinator.confirmationApproved`, then add a production-like smoke test that uses real lifecycle components, real canonical context projection, and mocked external dependencies only. Do not delete `ContextAssembler`, `SemanticMemoryAdvisor`, or rollback paths in this phase.

**Tech Stack:** Java 17, JUnit 5, AssertJ, Mockito, Maven Surefire, SpringClaw runtime lifecycle contracts.

---

### Task 1: Guard confirmation approval by state

**Files:**
- Modify: `src/main/java/com/springclaw/runtime/lifecycle/RunCoordinator.java`
- Modify: `src/test/java/com/springclaw/runtime/lifecycle/RunCoordinatorTest.java`

- [x] **Step 1: Write the failing test**

Add `confirmationApprovalCannotResumeRunThatIsNotWaiting` to `RunCoordinatorTest`. The test creates an accepted run, advances it only to `DECIDED`, calls `confirmationApproved`, expects an `IllegalStateException` mentioning `WAITING_CONFIRMATION`, and verifies the state remains `DECIDED`.

- [x] **Step 2: Run the focused test to verify RED**

Run:

```bash
MAVEN_OPTS='-Djdk.lang.Process.launchMechanism=FORK' mvn -q -DforkCount=0 -Dtest=RunCoordinatorTest#confirmationApprovalCannotResumeRunThatIsNotWaiting test
```

Expected: FAIL before implementation. The observed failure was a lower-level transition/schema error instead of the explicit `WAITING_CONFIRMATION` guard.

- [x] **Step 3: Implement the minimal guard**

In `RunCoordinator.confirmationApproved`, check `current.status() != RunStatus.WAITING_CONFIRMATION` immediately after `require(runId)` and throw:

```java
throw new IllegalStateException(
        "confirmation approval requires WAITING_CONFIRMATION but was "
                + current.status()
);
```

- [x] **Step 4: Verify GREEN**

Run:

```bash
MAVEN_OPTS='-Djdk.lang.Process.launchMechanism=FORK' mvn -q -DforkCount=0 -Dtest=RunCoordinatorTest test
```

Expected: PASS.

### Task 2: Add accepted-run canonical smoke

**Files:**
- Create: `src/test/java/com/springclaw/service/chat/impl/AcceptedRunCanonicalSmokeTest.java`

- [x] **Step 1: Write the smoke test**

Create a test that:

1. Creates a real `InMemoryRunLifecycleStore` and `RunCoordinator`.
2. Accepts a run with `RunCoordinator.accept`.
3. Builds a `ChatContextFactory` with real `ContextSnapshotFactory`, `RunStateContextSnapshotRequestFactory`, `CanonicalContextReadyProjector`, and `LegacyContextViewAdapter`.
4. Mocks only external dependencies: auth, sessions, model client, routing policy, skill registry, and memory retrieval.
5. Calls `ChatContextFactory.build(...)` with the accepted run id.
6. Uses a real `LegacyLifecycleObserver` in canonical mode to project decision, running, verification, and terminal degraded result.
7. Asserts the event sequence:

```text
RUN_CREATED
CONTEXT_READY
DECISION_MADE
STRATEGY_STARTED
VERIFICATION_COMPLETED
RUN_DEGRADED
```

- [x] **Step 2: Run the smoke test**

Run:

```bash
MAVEN_OPTS='-Djdk.lang.Process.launchMechanism=FORK' mvn -q -DforkCount=0 -Dtest=AcceptedRunCanonicalSmokeTest test
```

Expected: PASS.

- [x] **Step 3: Run focused Phase 3A5 gate**

Run:

```bash
MAVEN_OPTS='-Djdk.lang.Process.launchMechanism=FORK' mvn -q -DforkCount=0 -Dtest=RunCoordinatorTest,AcceptedRunCanonicalSmokeTest test
```

Expected: PASS.

### Task 3: Ledger and full verification

**Files:**
- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`

- [x] **Step 1: Update collaboration ledger**

Record:

- branch name;
- commits;
- guarded confirmation approval behavior;
- smoke event sequence;
- test commands and counts;
- rollback note that no legacy retrieval path was deleted.

- [x] **Step 2: Run full test suite**

Run with local MySQL URL override if required by this machine:

```bash
set -a
source /Users/hanbingzheng/springclaw/.env.local
set +a
DB_URL="jdbc:mysql://${MYSQL_HOST:-127.0.0.1}:${MYSQL_PORT:-3306}/${MYSQL_DB:-openclaw}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
MAVEN_OPTS='-Djdk.lang.Process.launchMechanism=FORK' mvn -q -DforkCount=0 -Dspring.datasource.url="$DB_URL" test
```

Expected: PASS.

- [x] **Step 3: Commit**

Commit in small logical commits:

```bash
git add src/main/java/com/springclaw/runtime/lifecycle/RunCoordinator.java src/test/java/com/springclaw/runtime/lifecycle/RunCoordinatorTest.java
git commit -m "fix: guard confirmation approval state"
git add src/test/java/com/springclaw/service/chat/impl/AcceptedRunCanonicalSmokeTest.java
git commit -m "test: smoke canonical accepted run lifecycle"
git add docs/superpowers/plans/2026-06-25-unified-runtime-phase-3a5-accepted-run-smoke-and-confirmation-guard.md docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
git commit -m "docs: record phase 3a5 runtime smoke evidence"
```
