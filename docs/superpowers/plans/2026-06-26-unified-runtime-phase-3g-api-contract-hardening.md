# Phase 3G API Contract Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Make public chat API responses expose the canonical request/run identity needed to correlate send, async result, trace, and proposal flows.

**Architecture:** Keep the internal canonical runtime unchanged. Add the missing public correlation field to `ChatResponse`, populate it from `AcceptedChatCommand.runId()`, and lock the external contract with focused tests. Do not change rollback components, lifecycle storage, or memory retrieval.

**Tech Stack:** Java 17, Spring Boot, JUnit 5, Mockito, AssertJ, Maven.

---

## Scope

Phase 3G is not a general API redesign. It only hardens the currently missing correlation contract:

- `/api/chat/send` creates a canonical run and must return that same id as `data.requestId`.
- `ChatServiceImpl.chat(AcceptedChatCommand)` must propagate `AcceptedChatCommand.runId()` into `ChatResponse`.
- Async result and proposal code must keep using the same request/run identity that already exists.
- Existing response shape remains additive: old fields stay; `requestId` is added.

Out of scope:

- changing SSE event format;
- changing database schema;
- deleting legacy rollback components;
- changing auth behavior;
- renaming `requestId` to `runId` in public APIs.

---

## Files

- Modify: `src/main/java/com/springclaw/dto/chat/ChatResponse.java`
  - Add `String requestId` as the first record component.
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java`
  - Populate `ChatResponse.requestId` with `acceptedRunId`.
- Modify tests that instantiate `ChatResponse` directly.
  - Add the existing run/request id where the test has one.
  - Use a stable placeholder like `"req-1"` where the id is not material.
- Add or modify: `src/test/java/com/springclaw/controller/ChatControllerAuthTest.java`
  - Lock that sync `/send` returns the accepted request id.
- Add or modify: `src/test/java/com/springclaw/service/chat/impl/ChatServiceImplLifecycleProjectionTest.java`
  - Lock that service-level response identity equals `AcceptedChatCommand.runId()`.
- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`
  - Append Phase 3G ledger entry after verification.

---

### Task 1: RED — Lock sync chat response requestId

**Files:**
- Modify: `src/test/java/com/springclaw/controller/ChatControllerAuthTest.java`

- [x] **Step 1: Add failing assertion to sync controller test**

Find the test `syncAndStreamCreateCanonicalRunsBeforeLegacyExecution`. Change the sync stub and capture the response:

```java
when(chatService.chat(any(AcceptedChatCommand.class)))
        .thenAnswer(invocation -> {
            AcceptedChatCommand command = invocation.getArgument(0);
            return new ChatResponse(
                    command.runId(),
                    "s1",
                    "ok",
                    "m1",
                    1L
            );
        });
```

Then replace:

```java
controller.send(new ChatRequest("s1", null, "你好", "api", "agent"));
```

with:

```java
ApiResponse<ChatResponse> syncResponse =
        controller.send(new ChatRequest("s1", null, "你好", "api", "agent"));
```

Add:

```java
assertThat(syncResponse.getData().requestId())
        .isEqualTo("11111111111111111111111111111111");
```

- [x] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=ChatControllerAuthTest#syncAndStreamCreateCanonicalRunsBeforeLegacyExecution test
```

Expected before implementation: compile failure because `ChatResponse` has no `requestId()` accessor and no five-argument constructor.

---

### Task 2: GREEN — Add requestId to ChatResponse and service response

**Files:**
- Modify: `src/main/java/com/springclaw/dto/chat/ChatResponse.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java`

- [x] **Step 1: Add public requestId field**

Change `ChatResponse` to:

```java
package com.springclaw.dto.chat;

/**
 * 对话响应。
 */
public record ChatResponse(
        String requestId,
        String sessionKey,
        String answer,
        String model,
        long timestamp
) {
}
```

- [x] **Step 2: Populate the id from accepted runtime identity**

In `ChatServiceImpl.chat(AcceptedChatCommand command)`, change the return statement to:

```java
return new ChatResponse(
        acceptedRunId,
        result.sessionKey(),
        result.answer(),
        aiProviderService.activeClient().displayName(),
        System.currentTimeMillis()
);
```

- [x] **Step 3: Update direct test constructors**

For every direct construction of project DTO `com.springclaw.dto.chat.ChatResponse`, add the request id as the first argument.

Examples:

```java
new ChatResponse("req-1", "s1", "ok", "m1", 1L)
```

For async consumer tests, use the async message id:

```java
new ChatResponse(message.requestId(), "session", "answer", "model", 123L)
```

- [x] **Step 4: Verify GREEN**

Run:

```bash
mvn -q -Dtest=ChatControllerAuthTest#syncAndStreamCreateCanonicalRunsBeforeLegacyExecution test
```

Expected: pass.

---

### Task 3: Lock service-level identity propagation

**Files:**
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatServiceImplLifecycleProjectionTest.java`

- [x] **Step 1: Add response identity assertion**

In the existing sync lifecycle projection test that calls:

```java
com.springclaw.dto.chat.ChatResponse response =
        fixture.service().chat(new AcceptedChatCommand(RUN_ID, request));
```

add:

```java
assertThat(response.requestId()).isEqualTo(RUN_ID);
```

- [x] **Step 2: Run focused service test**

Run:

```bash
mvn -q -Dtest=ChatServiceImplLifecycleProjectionTest test
```

Expected: pass.

---

### Task 4: Lock public HTTP contract

**Files:**
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatControllerCanonicalHttpSmokeTest.java`

- [x] **Step 1: Assert HTTP JSON includes requestId**

In the HTTP smoke test after posting `/api/chat/send`, assert:

```java
mockMvc.perform(post("/api/chat/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.requestId").value(RUN_ID));
```

Keep existing assertions for `sessionKey`, `answer`, and runtime lifecycle.

- [x] **Step 2: Run HTTP smoke**

Run:

```bash
set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a;
mvn -q -Dtest=ChatControllerCanonicalHttpSmokeTest test
```

Expected: pass.

---

### Task 5: Regression gate and ledger

**Files:**
- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`

- [x] **Step 1: Run API contract gate**

Run:

```bash
mvn -q -Dtest=CanonicalTransportIdentityTest,TransportParityCharacterizationTest,ChatControllerAuthTest,ChatServiceImplLifecycleProjectionTest,ChatMessageConsumerTest,WebhookControllerTest,ToolProposalControllerTest,RuntimeConsoleControllerTest test
```

Expected: pass.

- [x] **Step 2: Run canonical HTTP/MySQL smoke**

Run:

```bash
set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a;
mvn -q -Dtest=ChatControllerCanonicalHttpSmokeTest,ChatControllerSpringBootCanonicalSmokeIT,MySqlRunLifecycleStoreIT test
```

Expected: pass.

- [x] **Step 3: Run compile gate**

Run:

```bash
mvn -q -DskipTests test
```

Expected: pass.

- [x] **Step 4: Update collaboration ledger**

Append:

```text
## Update: Phase 3G API contract hardening

Task: Phase 3G API contract hardening
Branch:
  - codex/unified-runtime-phase-3g-api-contract
Runtime path tightened:
  - synchronous chat responses now expose data.requestId equal to the accepted canonical run id
  - ChatServiceImpl propagates AcceptedChatCommand.runId into ChatResponse
  - async/result/trace/proposal APIs remain on the same requestId correlation model
Verification:
  - <commands run>
Rollback order:
  - revert the Phase 3G commit to remove the additive ChatResponse.requestId field
Next dependency:
  - review/merge Phase 3G, then decide whether to harden SSE event contract or start rollback-component deletion analysis
```

- [x] **Step 5: Commit**

Run:

```bash
git add src/main/java/com/springclaw/dto/chat/ChatResponse.java \
        src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java \
        src/test/java/com/springclaw/controller/ChatControllerAuthTest.java \
        src/test/java/com/springclaw/service/chat/impl/ChatServiceImplLifecycleProjectionTest.java \
        src/test/java/com/springclaw/service/chat/impl/ChatControllerCanonicalHttpSmokeTest.java \
        src/test/java/com/springclaw/service/chat/async/ChatMessageConsumerTest.java \
        src/test/java/com/springclaw/service/webhook/WebhookRouterServiceTest.java \
        src/test/java/com/springclaw/architecture/CanonicalTransportIdentityTest.java \
        docs/superpowers/plans/2026-06-26-unified-runtime-phase-3g-api-contract-hardening.md \
        docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
git commit -m "feat: expose canonical request id in chat responses"
```

---

## Acceptance Criteria

- `/api/chat/send` response JSON includes `data.requestId`.
- `data.requestId` equals the accepted canonical run id.
- `ChatResponse` remains backward-additive: existing `sessionKey`, `answer`, `model`, and `timestamp` remain.
- Async completion still uses the queued `requestId`.
- Trace/proposal APIs continue using the same request id correlation.
- No rollback/memory/lifecycle storage components are removed.
