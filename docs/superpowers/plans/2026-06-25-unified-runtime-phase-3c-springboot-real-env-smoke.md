# Unified Runtime Phase 3C SpringBoot Real-Env Smoke Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove `/api/chat/send` drives the canonical unified Runtime lifecycle under real Spring Boot wiring with real ContextSnapshot and real memory adapters.

**Architecture:** Keep the canonical path enabled and start the Spring container with `@SpringBootTest + MockMvc`. Mock only non-deterministic external seams: authentication, model provider, agent decision, and selected agent execution. Keep the Controller, interceptors, ChatServiceImpl, ChatContextFactory, ContextSnapshotFactory, CanonicalContextReadyProjector, MemoryCoordinator, MySQL memory store, Redis short-term memory store, and in-memory RunLifecycleStore real.

**Tech Stack:** Java 17, Spring Boot 3.5.7, JUnit 5, AssertJ, Mockito Spring test overrides, MockMvc, MySQL memory tables, Redis/Redisson short-term memory.

---

## Files

- Create: `src/test/java/com/springclaw/controller/ChatControllerSpringBootCanonicalSmokeIT.java`
- Create: `docs/superpowers/plans/2026-06-25-unified-runtime-phase-3c-springboot-real-env-smoke.md`

## Task 1: Add SpringBoot canonical HTTP smoke test

**Files:**
- Create: `src/test/java/com/springclaw/controller/ChatControllerSpringBootCanonicalSmokeIT.java`

- [x] **Step 1: Write the failing test**

Create a Spring Boot MockMvc integration test that:

```java
@SpringBootTest(properties = {
        "OPENCLAW_PRIMARY_API_KEY=test-key",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "springclaw.context.snapshot.factory-enabled=true",
        "springclaw.memory.frame.enabled=true",
        "springclaw.memory.core.enabled=true",
        "springclaw.memory.core.short-term-shadow-enabled=true",
        "springclaw.redisson.enabled=true",
        "springclaw.memory.vector-enabled=false"
})
@AutoConfigureMockMvc
class ChatControllerSpringBootCanonicalSmokeIT {
    @Test
    void httpSendUsesCanonicalRuntimeWithRealSpringBootContextAndMemoryAdapters() throws Exception {
        // seed Redis short-term memory and MySQL durable memory
        // POST /api/chat/send with Authorization: Bearer phase-3c-token
        // assert response answer/model
        // assert RunState is DEGRADED with non-null ContextSnapshot
        // assert snapshot contains short-term, semantic, procedural, and project memory layers
        // assert events are RUN_CREATED, CONTEXT_READY, DECISION_MADE,
        // STRATEGY_STARTED, VERIFICATION_COMPLETED, RUN_DEGRADED
    }
}
```

- [x] **Step 2: Run the test to verify RED**

Run:

```bash
mvn -Dtest=ChatControllerSpringBootCanonicalSmokeIT test
```

Expected before fixes: failure from missing test wiring, missing bean override, or a real environment contract mismatch. The failure must be read before changing code.

Actual RED findings:

- First run failed at test compile because the test used `ContextSnapshot.sourceSummary()` instead of the actual `contextSourceSummary()` accessor.
- Second run failed at Spring context startup because local MySQL requires `allowPublicKeyRetrieval=true`.
- Third run failed because the isolated worktree did not load the main project `.env.local`, so Maven used the default `MYSQL_PASSWORD=root`.

- [x] **Step 3: Keep production code unchanged unless the test exposes a real Runtime bug**

If failure is test wiring, adjust only the test. If failure is a Runtime contract violation, add the smallest production fix with a focused regression assertion.

- [x] **Step 4: Verify GREEN**

Run:

```bash
mvn -Dtest=ChatControllerSpringBootCanonicalSmokeIT test
```

Expected: one SpringBoot HTTP smoke test passes.

Actual GREEN command:

```bash
set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a; mvn -Dtest=ChatControllerSpringBootCanonicalSmokeIT test
```

Result: 1 test, 0 failures, 0 errors, 0 skipped.

## Task 2: Regression coverage

**Files:**
- Test: `src/test/java/com/springclaw/controller/ChatControllerSpringBootCanonicalSmokeIT.java`

- [x] **Step 1: Run Phase 3B and Phase 3C smoke tests together**

Run:

```bash
mvn -Dtest=ChatControllerCanonicalHttpSmokeTest,ChatControllerSpringBootCanonicalSmokeIT test
```

Expected: both standalone and SpringBoot HTTP smoke tests pass.

Actual result: 2 tests, 0 failures, 0 errors, 0 skipped.

- [x] **Step 2: Run relevant memory integration tests**

Run:

```bash
mvn -Dtest=MemoryManagementServiceIT,MySqlMemoryStoresIT,RedisShortTermMemoryStoreTest test
```

Expected: memory authority and short-term adapters still pass.

Actual result: 27 tests, 0 failures, 0 errors, 0 skipped.

- [x] **Step 3: Update the ledger**

Record the Phase 3C result in the active collaboration ledger with:

```text
Phase 3C added SpringBoot MockMvc canonical HTTP smoke coverage for /api/chat/send.
Verified real Spring wiring, real ContextSnapshotFactory, real MemoryCoordinator, MySQL memory_record, Redis short-term memory, project memory, and canonical event sequence.
```

Only update the ledger after tests pass.

## Final verification

```bash
set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a; MAVEN_OPTS='-Djdk.lang.Process.launchMechanism=FORK' mvn test
```

Result: 806 tests, 0 failures, 0 errors, 0 skipped.
