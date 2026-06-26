# Phase 3H Canonical Trace Read Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `/api/chat/runs/{requestId}/trace` prefer canonical run events while preserving the legacy trace fallback.

**Architecture:** Keep the public `AgentRunTraceEvent` response shape unchanged. Inject `RunEventStore` into `AgentRunTraceService`, map canonical `RunEvent` rows into `AgentRunTraceEvent`, and only fall back to legacy `message_event` trace rows when no canonical events exist.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Mockito, AssertJ, Maven.

---

## Scope

This phase is a small read-path slice:

- Change only the chat trace read path (`AgentRunTraceService.listTrace`).
- Do not change replay, runtime-console runs, SSE, MySQL schema, or write-side lifecycle.
- Do not delete legacy `message_event` trace reads.

---

## Tasks

### Task 1: RED — canonical events win over legacy trace rows

**Files:**
- Modify: `src/test/java/com/springclaw/service/agent/AgentRunTraceServiceTest.java`

- [x] **Step 1: Add failing test**

Add a test that creates an `InMemoryRunLifecycleStore`, accepts a run, advances it through context/decision/running, and verifies `listTrace()` returns canonical events without calling legacy `MessageEventService.listRequestEvents`.

- [x] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=AgentRunTraceServiceTest#listTracePrefersCanonicalRunEventsOverLegacyTraceRows test
```

Expected before implementation: test fails because `listTrace()` still calls `MessageEventService.listRequestEvents`.

---

### Task 2: GREEN — map RunEvent to AgentRunTraceEvent

**Files:**
- Modify: `src/main/java/com/springclaw/service/agent/AgentRunTraceService.java`

- [x] **Step 1: Inject RunEventStore**

Add nullable `RunEventStore` to `AgentRunTraceService` constructors while keeping existing constructors source-compatible.

- [x] **Step 2: Prefer canonical events in `listTrace`**

If `runEventStore.findEventsByRunId(requestId)` returns a non-empty list, return those mapped to `AgentRunTraceEvent` and do not query legacy `message_event`.

Mapping:

```text
requestId  = RunEvent.runId
stepName   = RunEvent.eventType.wireName
type       = RunEvent.stage
status     = RunEvent.status.name, or eventType.wireName when status is null
detail     = RunEvent.payload
durationMs = RunEvent.durationMs
timestamp  = RunEvent.timestamp.toEpochMilli
category   = "runtime"
action     = RunEvent.eventType.wireName
target     = RunEvent.stage
source     = "canonical"
riskLevel  = ""
```

- [x] **Step 3: Verify GREEN**

Run:

```bash
mvn -q -Dtest=AgentRunTraceServiceTest#listTracePrefersCanonicalRunEventsOverLegacyTraceRows test
```

Expected: pass.

---

### Task 3: Regression gates and ledger

**Files:**
- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`

- [x] **Step 1: Run focused gates**

```bash
mvn -q -Dtest=AgentRunTraceServiceTest,ChatControllerAuthTest,ChatControllerCanonicalHttpSmokeTest test
```

- [x] **Step 2: Run compile gate**

```bash
mvn -q -DskipTests test
```

- [x] **Step 3: Update ledger**

Append Phase 3H result, verification, known limitation, and rollback.

- [x] **Step 4: Commit**

```bash
git add src/main/java/com/springclaw/service/agent/AgentRunTraceService.java \
        src/test/java/com/springclaw/service/agent/AgentRunTraceServiceTest.java \
        docs/superpowers/plans/2026-06-26-unified-runtime-phase-3h-canonical-trace-read.md \
        docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
git commit -m "feat: read run trace from canonical events"
```

---

## Acceptance Criteria

- `AgentRunTraceService.listTrace()` returns canonical events when available.
- Legacy trace reads remain as fallback when no canonical events exist.
- If canonical RunState exists for another user, legacy fallback is blocked.
- Public response type remains `AgentRunTraceEvent`.
- No write-side lifecycle, database schema, memory, rollback, replay, or runtime-console runs code is changed.
