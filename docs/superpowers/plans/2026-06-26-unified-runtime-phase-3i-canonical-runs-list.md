# Phase 3I Canonical Runs List Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make runtime-console run lists prefer canonical `RunState` rows while preserving legacy fallback.

**Architecture:** Add a minimal read-only `RunStateRepository.findRecent(limit)` query. Implement it for in-memory and MySQL lifecycle stores, then let `AgentRunTraceService.recentRuns()` project canonical runs into the existing map response shape before falling back to legacy `message_event` rows.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Mockito, AssertJ, Maven.

---

## Scope

- Change `recentRuns()` read path only.
- This affects `/api/runtime-console/runs` and runtime-console overview because both call `AgentRunTraceService.recentRuns()`.
- Do not change admin replay, SSE, schemas, write-side lifecycle, memory, or rollback components.
- Do not delete legacy `message_event` fallback.

---

## Tasks

### Task 1: RED — recentRuns prefers canonical state rows

**Files:**
- Modify: `src/test/java/com/springclaw/service/agent/AgentRunTraceServiceTest.java`

- [x] Add a failing test where an `InMemoryRunLifecycleStore` contains one accepted canonical run and legacy `message_event` rows should not be read.
- [x] Run:

```bash
mvn -q -Dtest=AgentRunTraceServiceTest#recentRunsPrefersCanonicalRunStatesOverLegacyTraceRows test
```

Expected before implementation: failure because `recentRuns()` still reads legacy `message_event`.

---

### Task 2: GREEN — add canonical recent query

**Files:**
- Modify: `src/main/java/com/springclaw/runtime/lifecycle/RunStateRepository.java`
- Modify: `src/main/java/com/springclaw/runtime/lifecycle/InMemoryRunLifecycleStore.java`
- Modify: `src/main/java/com/springclaw/runtime/lifecycle/MySqlRunLifecycleStore.java`
- Modify: `src/main/java/com/springclaw/service/agent/AgentRunTraceService.java`

- [x] Add `findRecent(int limit)` default method returning `List.of()` to `RunStateRepository`.
- [x] Implement `findRecent` in `InMemoryRunLifecycleStore` sorted by `updatedAt DESC`.
- [x] Implement `findRecent` in `MySqlRunLifecycleStore` with:

```sql
SELECT state_json
FROM runtime_run_state
ORDER BY updated_at DESC, run_id DESC
LIMIT ?
```

- [x] Change `AgentRunTraceService.recentRuns()` to:
  - read canonical recent states first;
  - filter by `userId` when supplied;
  - map to existing row keys: `requestId`, `sessionKey`, `userId`, `lastStep`, `status`, `detail`, `timestamp`, `channel`, `responseMode`;
  - use latest canonical event as `lastStep/detail/timestamp` when available;
  - fallback legacy only when canonical projected rows are empty.

---

### Task 3: Regression gates and ledger

**Files:**
- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`

- [x] Run:

```bash
mvn -q -Dtest=AgentRunTraceServiceTest,RuntimeConsoleControllerTest,InMemoryRunLifecycleStoreTest,MySqlRunLifecycleStoreIT test
```

- [x] Run with MySQL env when needed:

```bash
set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a;
mvn -q -Dtest=MySqlRunLifecycleStoreIT test
```

- [x] Run:

```bash
mvn -q -DskipTests test
```

- [x] Update ledger.
- [x] Commit.

---

## Acceptance Criteria

- `AgentRunTraceService.recentRuns()` returns canonical rows when canonical states exist.
- Legacy rows remain fallback when canonical recent rows are absent.
- User filtering is applied to canonical rows.
- MySQL lifecycle store can list recent states.
- No replay/SSE/schema/memory/rollback behavior changes.
