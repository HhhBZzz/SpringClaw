# Unified Runtime Phase 3E MySQL Run Lifecycle Store Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an opt-in MySQL-backed `RunLifecycleStore` so canonical run state and lifecycle events survive process restart without changing the runtime state machine.

**Architecture:** Keep `RunCoordinator`, `RunState`, `RunEvent`, and transition policy as the authority. Add a MySQL persistence adapter that stores full `RunState`/`RunEvent` JSON plus query/locking columns, selected by `springclaw.runtime.lifecycle.store=mysql`; default remains in-memory. Schema initialization is opt-in with the MySQL store and idempotent.

**Tech Stack:** Spring Boot 3.5, Java 17 records, `JdbcTemplate`, Jackson `ObjectMapper`, MySQL/InnoDB, JUnit 5, AssertJ.

---

## Files

- Modify: `src/main/java/com/springclaw/runtime/lifecycle/InMemoryRunLifecycleStore.java`
  - Remove component scanning so it can be the fallback bean from configuration.
- Create: `src/main/java/com/springclaw/config/RuntimeLifecycleStoreConfig.java`
  - Wires in-memory default and MySQL opt-in store.
- Create: `src/main/java/com/springclaw/config/RuntimeLifecycleSchemaInitializer.java`
  - Runs the runtime lifecycle migration only when MySQL lifecycle store is enabled.
- Create: `src/main/java/com/springclaw/runtime/lifecycle/MySqlRunLifecycleStore.java`
  - Implements `RunLifecycleStore` with `JdbcTemplate`, JSON serialization, optimistic revision checks, transition validation, and append-only event sequence.
- Create: `src/main/resources/sql/migrations/2026-06-25-runtime-run-lifecycle.sql`
  - Creates `runtime_run_state` and `runtime_run_event`.
- Create: `src/test/java/com/springclaw/runtime/lifecycle/RuntimeLifecycleStoreConfigTest.java`
  - Verifies default store remains in-memory.
- Create: `src/test/java/com/springclaw/config/RuntimeLifecycleSchemaInitializerTest.java`
  - Verifies migration resource is split/executed and disabled mode does nothing.
- Create: `src/test/java/com/springclaw/runtime/lifecycle/MySqlRunLifecycleStoreIT.java`
  - Verifies MySQL round trip, process-reload style read, stale revision atomicity, idempotent creation, and observation append.
- Modify: `src/main/resources/application.yml`
  - Adds documented properties:
    - `springclaw.runtime.lifecycle.store=${SPRINGCLAW_RUNTIME_LIFECYCLE_STORE:memory}`
    - `springclaw.runtime.lifecycle.schema-auto-init=${SPRINGCLAW_RUNTIME_LIFECYCLE_SCHEMA_AUTO_INIT:true}`

---

### Task 1: Configuration and Schema Initializer Tests

**Files:**
- Test: `src/test/java/com/springclaw/runtime/lifecycle/RuntimeLifecycleStoreConfigTest.java`
- Test: `src/test/java/com/springclaw/config/RuntimeLifecycleSchemaInitializerTest.java`
- Later implementation: `RuntimeLifecycleStoreConfig`, `RuntimeLifecycleSchemaInitializer`, migration SQL, in-memory annotation removal.

- [ ] **Step 1: Write failing config test**

Create `RuntimeLifecycleStoreConfigTest` with an `ApplicationContextRunner` that imports `RuntimeLifecycleStoreConfig` and asserts the missing-property/default context exposes exactly one `RunLifecycleStore`, an `InMemoryRunLifecycleStore`.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=RuntimeLifecycleStoreConfigTest test
```

Expected: compile failure because `RuntimeLifecycleStoreConfig` does not exist.

- [ ] **Step 3: Write failing initializer test**

Create `RuntimeLifecycleSchemaInitializerTest` with a mock `JdbcTemplate` and in-memory `ByteArrayResource` containing two `CREATE TABLE` statements. Assert enabled mode executes both statements and disabled mode executes none.

- [ ] **Step 4: Verify RED**

Run:

```bash
mvn -q -Dtest=RuntimeLifecycleSchemaInitializerTest test
```

Expected: compile failure because `RuntimeLifecycleSchemaInitializer` does not exist.

---

### Task 2: MySQL Store Behavior Tests

**Files:**
- Test: `src/test/java/com/springclaw/runtime/lifecycle/MySqlRunLifecycleStoreIT.java`
- Later implementation: `src/main/java/com/springclaw/runtime/lifecycle/MySqlRunLifecycleStore.java`

- [ ] **Step 1: Write failing MySQL integration test**

Create `MySqlRunLifecycleStoreIT` with:

- `@SpringBootTest` properties enabling `springclaw.runtime.lifecycle.store=mysql`
- `OPENCLAW_PRIMARY_API_KEY=test-key`
- Spring AI chat/embedding disabled
- datasource URL with `allowPublicKeyRetrieval=true`
- cleanup of `runtime_run_event` and `runtime_run_state` rows for `run_id LIKE 'phase3e%'`

Test cases:

1. `persistsLifecycleAndCanBeReloadedByANewStoreInstance`
   - Accept a run through `RunCoordinator`.
   - Move through `CONTEXT_READY`, `DECIDED`, `RUNNING`.
   - Append `TOOL_STARTED`.
   - Construct a second `MySqlRunLifecycleStore` with the same `JdbcTemplate` and `ObjectMapper`.
   - Assert the second store reads `RUNNING`, revision `3`, the context snapshot hash, and event sequence `1..4`.
2. `staleCommitDoesNotMutateStateOrAppendEvent`
   - Create a run.
   - Commit one legal failure transition with expected revision `0`.
   - Try the same commit again with expected revision `0`.
   - Assert exception contains `stale`, state revision remains `1`, event count remains `2`.
3. `identicalCreateIsIdempotentButConflictingCreateFails`
   - Create a run.
   - Repeat same creation and assert event count remains `1`.
   - Attempt same `runId` with a different original message and assert `conflicting`.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=MySqlRunLifecycleStoreIT test
```

Expected: compile failure because `MySqlRunLifecycleStore` and lifecycle schema are missing.

---

### Task 3: Implement Schema and Wiring

**Files:**
- Create: `src/main/resources/sql/migrations/2026-06-25-runtime-run-lifecycle.sql`
- Create: `src/main/java/com/springclaw/config/RuntimeLifecycleSchemaInitializer.java`
- Create: `src/main/java/com/springclaw/config/RuntimeLifecycleStoreConfig.java`
- Modify: `src/main/java/com/springclaw/runtime/lifecycle/InMemoryRunLifecycleStore.java`
- Modify: `src/main/resources/application.yml`

- [ ] **Step 1: Add SQL migration**

Create two tables:

- `runtime_run_state`
  - primary key `run_id`
  - `request_id`, `session_key`, `channel`, `user_id`, `status`, `revision`
  - `accepted_at`, `updated_at`, `deadline_at`
  - `state_json`
  - `create_time`, `update_time`
  - indexes for user/session/status
- `runtime_run_event`
  - primary key `event_id`
  - `run_id`, `sequence_no`, `event_type`, `stage`, `status`, `occurred_at`, `correlation_id`
  - `event_json`
  - unique key `(run_id, sequence_no)`
  - indexes for run/type/time

- [ ] **Step 2: Add initializer**

Implement `RuntimeLifecycleSchemaInitializer` as an `ApplicationRunner` using the same SQL splitting helper as `RuntimeConsoleSchemaInitializer`. It must:

- return immediately when disabled
- throw `IllegalStateException` when the migration resource is missing
- execute each split SQL statement

- [ ] **Step 3: Add store config**

Implement `RuntimeLifecycleStoreConfig`:

- `@Bean @ConditionalOnProperty(prefix="springclaw.runtime.lifecycle", name="store", havingValue="mysql")` returns `MySqlRunLifecycleStore`
- `@Bean @ConditionalOnMissingBean(RunLifecycleStore.class)` returns `InMemoryRunLifecycleStore`

- [ ] **Step 4: Remove component annotation from in-memory store**

Remove `@Component` import and annotation from `InMemoryRunLifecycleStore`.

- [ ] **Step 5: Verify config tests GREEN**

Run:

```bash
mvn -q -Dtest=RuntimeLifecycleStoreConfigTest,RuntimeLifecycleSchemaInitializerTest test
```

Expected: both tests pass.

---

### Task 4: Implement MySQL Store

**Files:**
- Create: `src/main/java/com/springclaw/runtime/lifecycle/MySqlRunLifecycleStore.java`

- [ ] **Step 1: Implement serialization helpers**

Use injected `ObjectMapper` and copy/register Java time support if needed. Provide:

- `String write(Object value)`
- `<T> T read(String json, Class<T> type)`
- `LocalDateTime toLocalDateTime(Instant instant)`
- `Instant toInstant(LocalDateTime value)`

- [ ] **Step 2: Implement reads**

Implement:

- `findByRunId(String runId)` reads `state_json`
- `findEventsByRunId(String runId)` orders by `sequence_no` and reads `event_json`

- [ ] **Step 3: Implement create**

Inside a transaction:

- validate initial state and event match
- if state exists and same acceptance, return existing
- if state exists but not same acceptance, throw `IllegalStateException("conflicting run creation: ...")`
- insert persisted event sequence `1`
- insert state row

- [ ] **Step 4: Implement commit**

Inside a transaction:

- read current state
- reject missing run
- reject stale revision
- validate transition with `RunTransitionPolicy.validate(current, nextState)`
- compute next sequence from `MAX(sequence_no)+1`
- insert event
- update state using `WHERE run_id=? AND revision=?`
- if update count is not `1`, throw stale revision

- [ ] **Step 5: Implement append**

Inside a transaction:

- read current state
- reject missing/stale revision
- reject event status mismatch
- insert persisted event with next sequence
- do not update state revision

- [ ] **Step 6: Verify MySQL IT GREEN**

Run:

```bash
mvn -q -Dtest=MySqlRunLifecycleStoreIT test
```

Expected: all Phase 3E MySQL store tests pass.

---

### Task 5: Regression Verification and Handoff

**Files:**
- All files above.

- [ ] **Step 1: Run lifecycle unit suite**

```bash
mvn -q -Dtest=InMemoryRunLifecycleStoreTest,RunCoordinatorTest,RuntimeLifecycleStoreConfigTest,RuntimeLifecycleSchemaInitializerTest test
```

Expected: pass.

- [ ] **Step 2: Run MySQL lifecycle integration suite**

```bash
mvn -q -Dtest=MySqlRunLifecycleStoreIT test
```

Expected: pass when local MySQL credentials match project defaults/environment.

- [ ] **Step 3: Run broader targeted runtime suite**

```bash
mvn -q -Dtest='com.springclaw.runtime.**.*Test,com.springclaw.config.*Runtime*Test' test
```

Expected: pass.

- [ ] **Step 4: Update ledger**

Append a Phase 3E entry to `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md` with:

- branch name
- files changed
- test commands and results
- rollback: set `SPRINGCLAW_RUNTIME_LIFECYCLE_STORE=memory`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/springclaw/runtime/lifecycle/InMemoryRunLifecycleStore.java \
        src/main/java/com/springclaw/runtime/lifecycle/MySqlRunLifecycleStore.java \
        src/main/java/com/springclaw/config/RuntimeLifecycleStoreConfig.java \
        src/main/java/com/springclaw/config/RuntimeLifecycleSchemaInitializer.java \
        src/main/resources/application.yml \
        src/main/resources/sql/migrations/2026-06-25-runtime-run-lifecycle.sql \
        src/test/java/com/springclaw/runtime/lifecycle/RuntimeLifecycleStoreConfigTest.java \
        src/test/java/com/springclaw/config/RuntimeLifecycleSchemaInitializerTest.java \
        src/test/java/com/springclaw/runtime/lifecycle/MySqlRunLifecycleStoreIT.java \
        docs/superpowers/plans/2026-06-25-unified-runtime-phase-3e-mysql-run-lifecycle-store.md \
        docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
git commit -m "feat: persist run lifecycle state in mysql"
```

---

## Acceptance Criteria

- Default application wiring still uses `InMemoryRunLifecycleStore`.
- `springclaw.runtime.lifecycle.store=mysql` selects `MySqlRunLifecycleStore`.
- MySQL store preserves current state and ordered events after a new store instance is constructed.
- Stale revision writes do not mutate state and do not append events.
- Identical create remains idempotent; conflicting create is rejected.
- Rollback is one property: `SPRINGCLAW_RUNTIME_LIFECYCLE_STORE=memory`.
