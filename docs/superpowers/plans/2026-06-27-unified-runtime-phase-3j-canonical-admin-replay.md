# Phase 3J Canonical Admin Replay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make admin replay prefer canonical `RunState` + `RunEvent` data while preserving the legacy structured-table fallback.

**Architecture:** Keep `AgentRunTraceService.replayRun()` as the single service entrypoint used by `AdminManageController`. Add a canonical projection before the existing `agent_run / agent_run_step / tool_invocation` SQL path. The canonical projection returns the same broad map contract used by replay today, plus `source="canonical"`, and legacy SQL remains the fallback when canonical state is absent.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Mockito, AssertJ, Maven.

---

## Scope

- Change only admin replay read behavior in `AgentRunTraceService.replayRun()`.
- Preserve `/api/admin/manage/runs/{requestId}/replay` controller behavior: empty map still means controller returns 404.
- Do not change lifecycle writes, SSE, runtime-console run list, trace list, schema, memory, rollback flags, or legacy table retention.
- Do not delete the legacy SQL replay fallback.

## Files

- Modify: `src/main/java/com/springclaw/service/agent/AgentRunTraceService.java`
- Modify: `src/test/java/com/springclaw/service/agent/AgentRunTraceServiceTest.java`
- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`

---

## Tasks

### Task 1: RED — replay prefers canonical rows

**Files:**
- Modify: `src/test/java/com/springclaw/service/agent/AgentRunTraceServiceTest.java`

- [x] Add `replayRunPrefersCanonicalLifecycleOverLegacyStructuredTables()`.
- [x] Test setup:
  - create an `InMemoryRunLifecycleStore`;
  - accept run `req-canonical-replay`;
  - append a canonical `tool.started` event;
  - construct `AgentRunTraceService` with a mock `JdbcTemplate` and the store;
  - call `replayRun("req-canonical-replay")`.
- [x] Assertions:
  - result has `source=canonical`;
  - result has `request_id=req-canonical-replay`, `session_key=s1`, `user_id=u1`, `status=CREATED`;
  - `steps` contains `run.created` then `tool.started`;
  - `JdbcTemplate.queryForList(...)` is never called.
- [x] Run:

```bash
mvn -q -Dtest=AgentRunTraceServiceTest#replayRunPrefersCanonicalLifecycleOverLegacyStructuredTables test
```

Expected before implementation: fail because `replayRun()` still queries `agent_run` first.

### Task 2: GREEN — canonical replay projection

**Files:**
- Modify: `src/main/java/com/springclaw/service/agent/AgentRunTraceService.java`

- [x] Add a private `canonicalReplayRun(String requestId)` helper.
- [x] In `replayRun()`, call `canonicalReplayRun(requestId)` before the legacy SQL branch.
- [x] If canonical projection is non-empty, return it immediately.
- [x] Projection shape:

```java
Map<String, Object> result = new LinkedHashMap<>();
result.put("request_id", state.requestId());
result.put("session_key", state.sessionKey());
result.put("channel", state.channel());
result.put("user_id", state.userId());
result.put("response_mode", state.responseMode());
result.put("status", state.status().name());
result.put("started_at", canonicalStart(state));
result.put("finished_at", canonicalTime(state.finishedAt()));
result.put("duration_ms", canonicalDurationMs(state));
result.put("error_message", state.failure() == null ? null : state.failure().message());
result.put("source", "canonical");
result.put("steps", canonicalReplaySteps(events));
result.put("toolInvocations", canonicalReplayToolInvocations(state));
```

- [x] Canonical step shape:

```java
row.put("sequence_no", event.sequence());
row.put("step_name", event.eventType().wireName());
row.put("step_type", event.stage());
row.put("status", event.status() == null ? "" : event.status().name());
row.put("detail_json", event.payload());
row.put("started_at", canonicalTime(event.timestamp()));
row.put("finished_at", canonicalTime(event.timestamp()));
row.put("duration_ms", event.durationMs());
row.put("source", "canonical");
```

- [x] Canonical tool invocation shape maps `RunState.toolInvocations()` to replay-compatible rows:

```java
row.put("id", invocation.invocationId());
row.put("tool_name", invocation.toolName());
row.put("toolset", invocation.toolsetId());
row.put("risk_level", invocation.riskLevel().name());
row.put("status", invocation.status().name());
row.put("duration_ms", duration between startedAt and finishedAt when both exist);
row.put("input_summary", invocation.canonicalArgumentsJson());
row.put("output_summary", invocation.outcome() == null ? null : invocation.outcome().summary());
row.put("error_message", failed/denied outcome summary when present);
row.put("create_time", canonicalTime(startedAt or finishedAt or state.updatedAt()));
row.put("source", "canonical");
```

- [x] Run:

```bash
mvn -q -Dtest=AgentRunTraceServiceTest#replayRunPrefersCanonicalLifecycleOverLegacyStructuredTables test
```

Expected after implementation: pass.

### Task 3: RED/GREEN — legacy fallback remains

**Files:**
- Verify: `src/test/java/com/springclaw/contract/TurnContractTest.java`

- [x] Keep existing `replayRunReturnsEmptyWhenAgentRunMissing()` and `replayRunQueriesUseCorrectOrdering()` green.
- [x] If constructor setup must change to pass a lifecycle store explicitly, update helper methods without weakening assertions.
- [x] Run:

```bash
mvn -q -Dtest=TurnContractTest test
```

Expected: pass, proving legacy SQL fallback remains when canonical state is absent.

### Task 4: Regression gates and ledger

**Files:**
- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`

- [x] Run:

```bash
mvn -q -Dtest=AgentRunTraceServiceTest,TurnContractTest,RuntimeConsoleControllerTest test
```

- [x] Run:

```bash
mvn -q -DskipTests test
```

- [x] Update the collaboration ledger with Phase 3J summary, tests, rollback, and remaining limitations.
- [x] Run:

```bash
git diff --check
```

- [x] Commit:

```bash
git add docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md \
  docs/superpowers/plans/2026-06-27-unified-runtime-phase-3j-canonical-admin-replay.md \
  src/main/java/com/springclaw/service/agent/AgentRunTraceService.java \
  src/test/java/com/springclaw/service/agent/AgentRunTraceServiceTest.java \
  src/test/java/com/springclaw/contract/TurnContractTest.java
git commit -m "feat: read admin replay from canonical lifecycle"
```

---

## Acceptance Criteria

- `replayRun()` returns canonical replay data when canonical state exists.
- `replayRun()` does not query legacy structured tables when canonical data exists.
- Legacy structured-table replay remains the fallback when canonical state is absent.
- Empty canonical + empty legacy still returns an empty map.
- Controller 404 behavior remains unchanged.
- No SSE/runtime-console/memory/schema/rollback behavior changes.

## Known Limitation

- Canonical replay currently projects `toolInvocations` from `RunState.toolInvocations()`.
  The current `RunCoordinator` records tool lifecycle as `RunEvent` rows and does
  not yet populate `RunState.toolInvocations()`, so canonical replay exposes tool
  activity through `steps` while `toolInvocations` remains empty. Legacy SQL
  fallback still returns structured `tool_invocation` rows when canonical state is
  absent.
