# Phase 3B HTTP Accepted Run Smoke Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove a real HTTP `/api/chat/send` request enters the accepted-run canonical runtime path and reaches a terminal lifecycle state.

**Architecture:** Use `MockMvc` with a real `ChatController`, real `ChatServiceImpl`, real `ChatContextFactory`, and real runtime lifecycle components. Mock only external model/session/auth/skill/routing/memory-source dependencies. Do not delete or modify legacy retrieval paths in Phase 3B.

**Tech Stack:** Java 17, JUnit 5, AssertJ, Mockito, Spring MockMvc, Maven Surefire.

---

### Task 1: HTTP accepted-run smoke

**Files:**
- Create: `src/test/java/com/springclaw/service/chat/impl/ChatControllerCanonicalHttpSmokeTest.java`

- [x] **Step 1: Write failing smoke test**

Create a test that:

1. Sets `RequestUserContextHolder` with authenticated user `user-1`.
2. Uses `MockMvc` to POST JSON to `/api/chat/send`.
3. Uses a real `ChatController`.
4. Uses a real `ChatServiceImpl`.
5. Uses real lifecycle components:
   - `InMemoryRunLifecycleStore`
   - `RunCoordinator`
   - `DefaultLegacyRuntimeBridge`
   - `LegacyLifecycleObserver`
   - `ContextSnapshotFactory`
   - `RunStateContextSnapshotRequestFactory`
   - `CanonicalContextReadyProjector`
   - `LegacyContextViewAdapter`
6. Mocks only external dependencies:
   - session/auth/skill/routing/model providers
   - memory retrieval source
   - chat lock/persistence
   - selected agent engine
7. Asserts HTTP response body contains `code=0` and answer `http smoke answer`.
8. Asserts run state is terminal `DEGRADED`.
9. Asserts lifecycle events are:

```text
RUN_CREATED
CONTEXT_READY
DECISION_MADE
STRATEGY_STARTED
VERIFICATION_COMPLETED
RUN_DEGRADED
```

10. Asserts `ContextAssembler` has no interactions.

- [x] **Step 2: Run smoke test**

Run:

```bash
MAVEN_OPTS='-Djdk.lang.Process.launchMechanism=FORK' mvn -q -DforkCount=0 -Dtest=ChatControllerCanonicalHttpSmokeTest test
```

Expected: PASS after implementation.

### Task 2: Verification and ledger

**Files:**
- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`
- Modify: `docs/superpowers/plans/2026-06-25-unified-runtime-phase-3b-http-accepted-run-smoke.md`

- [x] **Step 1: Run focused Phase 3B gate**

Run:

```bash
MAVEN_OPTS='-Djdk.lang.Process.launchMechanism=FORK' mvn -q -DforkCount=0 -Dtest=ChatControllerCanonicalHttpSmokeTest,AcceptedRunCanonicalSmokeTest,RunCoordinatorTest test
```

Expected: PASS.

- [x] **Step 2: Run full suite**

Run with local MySQL URL override if required by this machine:

```bash
set -a
source /Users/hanbingzheng/springclaw/.env.local
set +a
DB_URL="jdbc:mysql://${MYSQL_HOST:-127.0.0.1}:${MYSQL_PORT:-3306}/${MYSQL_DB:-openclaw}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
MAVEN_OPTS='-Djdk.lang.Process.launchMechanism=FORK' mvn -q -DforkCount=0 -Dspring.datasource.url="$DB_URL" test
```

Expected: PASS.

- [x] **Step 3: Update collaboration ledger**

Record:

- branch name;
- modified files;
- HTTP smoke event sequence;
- focused and full test counts;
- explicit note that no legacy retrieval path was deleted.

- [x] **Step 4: Commit**

Commit:

```bash
git add src/test/java/com/springclaw/service/chat/impl/ChatControllerCanonicalHttpSmokeTest.java
git commit -m "test: smoke http canonical accepted run"
git add docs/superpowers/plans/2026-06-25-unified-runtime-phase-3b-http-accepted-run-smoke.md docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
git commit -m "docs: record phase 3b http smoke evidence"
```
