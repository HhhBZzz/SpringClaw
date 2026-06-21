# Unified Runtime Canonical Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Phase 2A only: create or accept one normalized 32-character lowercase hexadecimal identity at the external ingress boundary and propagate that same value as `requestId == runId` through sync REST, SSE REST, async REST/Rabbit delivery, trace, tool execution, and proposal ownership.

**Architecture:** Each real ingress owns one normalized identity: `ChatController` creates IDs for REST, `WebhookRouterService` keeps and validates the request ID it already creates, `TaskExecutionService` keeps the scheduled execution ID it already creates, and `ChatMessageConsumer` reuses the Rabbit message ID. The existing `ChatService.chat(ChatRequest)` and `stream(ChatRequest)` compatibility APIs remain available and create once before delegating to `AcceptedChatCommand`; accepted-command and accepted-task overloads validate but never create. `ChatContextFactory` receives the accepted ID and never generates one. The context factory signature, service overloads, all production callers, direct constructors, and affected mocks/tests are migrated in one atomic task so every committed state compiles. Phase 2A deliberately does not suppress duplicate async execution or change lifecycle, trace authority, or routing.

**Tech Stack:** Java 17 records, Spring Boot 3.5 constructor injection, JUnit 5, AssertJ, Mockito, Maven Surefire.

---

## Scope and ownership decisions

This plan implements only spec Phase 2A.

Identity ownership is one rule with two entry forms:

1. An adapter that explicitly accepts an external request creates or accepts the ID, then calls an accepted-command service overload.
2. A legacy caller using the old `ChatService` API is treated as an implicit acceptance adapter; the compatibility method creates once, then delegates to the same accepted-command overload.

The concrete ingress behavior is:

| Path | Identity owner | Required behavior |
|---|---|---|
| `POST /api/chat/send` | `ChatController.send` | Create once, call `chat(AcceptedChatCommand)` |
| `POST /api/chat/stream` | `ChatController.stream` | Create once, call `stream(AcceptedChatCommand)` |
| `POST /api/chat/async` | `ChatController.sendAsync` | Create once, put the same value in accepted response, queued result key, and `AsyncChatRequestMessage.requestId` |
| Rabbit first delivery | `ChatMessageConsumer` | Reuse `message.requestId`; do not create |
| Rabbit redelivery | `ChatMessageConsumer` | Reuse the same `message.requestId`; execution may run again |
| Webhook dispatch | `WebhookRouterService` | Keep the normalized ID it already creates, validate it with `RunIdentityFactory.accept`, and call `chat(AcceptedChatCommand)` |
| Scheduled agent execution | `TaskExecutionService` | Keep the execution ID created before `ScheduledTaskExecutionService.start`, pass it to `executeTaskMessage(..., runId)`, and do not change `TaskChatExecutionResult` |
| Legacy `ChatService.chat(ChatRequest)` | `ChatServiceImpl` compatibility method | Create once, delegate |
| Legacy `ChatService.stream(ChatRequest)` | `ChatServiceImpl` compatibility method | Create once, delegate |
| Legacy `ChatServiceImpl.executeTaskMessage(ChatRequest, boolean)` | `ChatServiceImpl` compatibility method | Create once, delegate to the accepted-ID overload |
| Confirmation/resume | Existing proposal services | Reuse proposal `requestId`/`runId`; do not create |

Create:

```text
src/main/java/com/springclaw/runtime/identity/RunIdentityFactory.java
src/main/java/com/springclaw/runtime/identity/DefaultRunIdentityFactory.java
src/main/java/com/springclaw/service/chat/AcceptedChatCommand.java
src/test/java/com/springclaw/runtime/identity/DefaultRunIdentityFactoryTest.java
src/test/java/com/springclaw/architecture/CanonicalTransportIdentityTest.java
src/test/java/com/springclaw/architecture/CanonicalToolOwnershipTest.java
src/test/java/com/springclaw/service/webhook/WebhookRouterServiceTest.java
```

Modify:

```text
src/main/java/com/springclaw/controller/ChatController.java
src/main/java/com/springclaw/service/chat/ChatService.java
src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java
src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java
src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java
src/main/java/com/springclaw/service/webhook/WebhookRouterService.java
src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java
src/main/java/com/springclaw/service/chat/impl/SimplifiedOparEngine.java
src/main/java/com/springclaw/service/chat/impl/OparLoopEngine.java
src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java
src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java
src/main/java/com/springclaw/service/agent/executor/WorkspaceCapabilityExecutor.java
src/main/java/com/springclaw/service/agent/executor/LocalFilesCapabilityExecutor.java
src/main/java/com/springclaw/service/agent/executor/WebCapabilityExecutor.java
src/main/java/com/springclaw/service/agent/executor/SystemHealthCapabilityExecutor.java
src/main/java/com/springclaw/service/agent/executor/SkillCapabilityExecutor.java
src/main/java/com/springclaw/service/agent/executor/RealtimeCapabilityExecutor.java
src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java
src/test/java/com/springclaw/service/chat/impl/ChatServiceImplModeTest.java
src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPersistenceTest.java
src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPendingApprovalTest.java
src/test/java/com/springclaw/controller/ChatControllerAuthTest.java
src/test/java/com/springclaw/service/task/TaskExecutionServiceTest.java
src/test/java/com/springclaw/architecture/ContextPropagationCharacterizationTest.java
src/test/java/com/springclaw/architecture/FinalAnswerOwnershipCharacterizationTest.java
src/test/java/com/springclaw/architecture/TransportParityCharacterizationTest.java
src/test/java/com/springclaw/service/proposal/ToolInvocationProposalServiceConfirmTest.java
src/test/java/com/springclaw/service/proposal/ToolProposalExecutionServiceTest.java
```

Do not modify:

```text
src/main/java/com/springclaw/dto/chat/ChatRequest.java
src/main/java/com/springclaw/dto/chat/ChatResponse.java
src/main/java/com/springclaw/dto/chat/AsyncChatAcceptedResponse.java
src/main/java/com/springclaw/service/chat/async/AsyncChatRequestMessage.java
src/main/java/com/springclaw/service/chat/async/AsyncChatResultPayload.java
src/main/java/com/springclaw/tool/runtime/ToolExecutionContext.java
src/main/java/com/springclaw/tool/runtime/ToolRuntimeAspect.java
src/main/java/com/springclaw/service/proposal/ToolProposalExecutionService.java
src/main/java/com/springclaw/service/workspace/WorkspaceGuard.java
src/main/java/com/springclaw/service/workspace/WorkspaceGitGuard.java
src/main/java/com/springclaw/service/agent/EngineSelector.java
src/main/java/com/springclaw/runtime/contract/**
src/main/resources/application.yml
.env.example
```

The nested `ChatServiceImpl.TaskChatExecutionResult` declaration must remain byte-for-byte unchanged even though other methods in `ChatServiceImpl.java` change.

No `RunCoordinator`, lifecycle repository, event store, `RunState`, `LegacyRuntimeStrategy`, feature flag, trace-authority change, routing freeze, engine `supports()` change, engine priority change, context migration, decision migration, SSE projector, database change, or duplicate-execution suppression belongs in Phase 2A.

### Task 1: Add the canonical identity factory and accepted command

**Files:**
- Create: `src/main/java/com/springclaw/runtime/identity/RunIdentityFactory.java`
- Create: `src/main/java/com/springclaw/runtime/identity/DefaultRunIdentityFactory.java`
- Create: `src/main/java/com/springclaw/service/chat/AcceptedChatCommand.java`
- Test: `src/test/java/com/springclaw/runtime/identity/DefaultRunIdentityFactoryTest.java`

- [ ] **Step 1: Write the failing identity tests**

Create `DefaultRunIdentityFactoryTest.java`:

```java
package com.springclaw.runtime.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultRunIdentityFactoryTest {

    private final DefaultRunIdentityFactory factory = new DefaultRunIdentityFactory();

    @Test
    void createReturnsNormalizedLowercaseHex() {
        assertThat(factory.create()).matches("[0-9a-f]{32}");
    }

    @Test
    void acceptReturnsTheSuppliedNormalizedId() {
        String runId = "0123456789abcdef0123456789abcdef";

        assertThat(factory.accept(runId)).isEqualTo(runId);
    }

    @Test
    void acceptRejectsNullBlankHyphenatedUppercaseAndWrongLengthValues() {
        assertThatThrownBy(() -> factory.accept(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("runId must be exactly 32 lowercase hexadecimal characters");
        assertThatThrownBy(() -> factory.accept(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.accept("01234567-89ab-cdef-0123-456789abcdef"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.accept("ABCDEF0123456789ABCDEF0123456789"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.accept("0123456789abcdef"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -q -Dtest=DefaultRunIdentityFactoryTest test
```

Expected: test compilation fails because `DefaultRunIdentityFactory` does not exist.

- [ ] **Step 3: Implement the minimal identity API**

Create `RunIdentityFactory.java`:

```java
package com.springclaw.runtime.identity;

public interface RunIdentityFactory {

    String create();

    String accept(String suppliedRunId);
}
```

Create `DefaultRunIdentityFactory.java`:

```java
package com.springclaw.runtime.identity;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

@Component
public final class DefaultRunIdentityFactory implements RunIdentityFactory {

    private static final Pattern NORMALIZED_ID = Pattern.compile("[0-9a-f]{32}");

    @Override
    public String create() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public String accept(String suppliedRunId) {
        if (suppliedRunId == null || !NORMALIZED_ID.matcher(suppliedRunId).matches()) {
            throw new IllegalArgumentException(
                    "runId must be exactly 32 lowercase hexadecimal characters"
            );
        }
        return suppliedRunId;
    }
}
```

Create `AcceptedChatCommand.java`:

```java
package com.springclaw.service.chat;

import com.springclaw.dto.chat.ChatRequest;

import java.util.Objects;

public record AcceptedChatCommand(String runId, ChatRequest request) {

    public AcceptedChatCommand {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(request, "request");
    }
}
```

`AcceptedChatCommand` is an internal Java command, not an HTTP or Rabbit DTO. Do not add Jackson annotations or expose it from controller response types.

- [ ] **Step 4: Run the test and verify GREEN**

Run:

```bash
mvn -q -Dtest=DefaultRunIdentityFactoryTest test
mvn -q -DskipTests compile
mvn -q -DskipTests test-compile
```

Expected: 3 tests, 0 failures, 0 errors, 0 skips; both compile commands exit `0`. The first implementation commit is independently buildable.

- [ ] **Step 5: Commit the identity primitive**

```bash
git add \
  src/main/java/com/springclaw/runtime/identity/RunIdentityFactory.java \
  src/main/java/com/springclaw/runtime/identity/DefaultRunIdentityFactory.java \
  src/main/java/com/springclaw/service/chat/AcceptedChatCommand.java \
  src/test/java/com/springclaw/runtime/identity/DefaultRunIdentityFactoryTest.java
git commit -m "feat: add canonical run identity"
```

### Task 2: Atomically migrate context construction and every chat ingress/caller

This is one commit. Do not split it. Removing `ChatContextFactory.build(ChatRequest, boolean)` before the service, production callers, tests, stubs, and direct constructors are migrated leaves a non-compiling intermediate commit.

**Files:**
- Modify: `src/main/java/com/springclaw/service/chat/ChatService.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java`
- Modify: `src/main/java/com/springclaw/controller/ChatController.java`
- Modify: `src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java`
- Modify: `src/main/java/com/springclaw/service/webhook/WebhookRouterService.java`
- Modify: `src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java`
- Create: `src/test/java/com/springclaw/architecture/CanonicalTransportIdentityTest.java`
- Create: `src/test/java/com/springclaw/service/webhook/WebhookRouterServiceTest.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatServiceImplModeTest.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPersistenceTest.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPendingApprovalTest.java`
- Modify: `src/test/java/com/springclaw/controller/ChatControllerAuthTest.java`
- Modify: `src/test/java/com/springclaw/service/task/TaskExecutionServiceTest.java`
- Modify: `src/test/java/com/springclaw/architecture/ContextPropagationCharacterizationTest.java`
- Modify: `src/test/java/com/springclaw/architecture/FinalAnswerOwnershipCharacterizationTest.java`
- Modify: `src/test/java/com/springclaw/architecture/TransportParityCharacterizationTest.java`

#### Verified migration inventory

Every current `ChatContextFactory.build` production call, direct test call, and Mockito stub is migrated in this task:

| File | Current site(s) | Exact migration |
|---|---:|---|
| `ChatServiceImpl.java` | 176, 518 | `build(request, true, acceptedRunId)` and `build(request, persistResult, acceptedRunId)` |
| `ChatContextFactoryTest.java` | direct `factory.build(...)` | Add the fixed accepted ID as argument three and assert `context.requestId()` |
| `ContextPropagationCharacterizationTest.java` | direct `factory.build(...)` | Add the fixed accepted ID as argument three and assert it |
| `ChatServiceImplModeTest.java` | stubs at 46, 62, 78, 94, 130, 258, 289 | Use `build(any(ChatRequest.class), anyBoolean(), anyString())` |
| `ChatServiceImplPersistenceTest.java` | stubs at 88, 187 | Use the typed matcher `build(any(ChatRequest.class), anyBoolean(), anyString())` |

Every current `ChatService.chat` or `stream` production call/stub is handled:

| File | Current site(s) | Exact migration |
|---|---:|---|
| `ChatController.java` | `send`, `stream` | Create one ID and call `chat/stream(AcceptedChatCommand)` |
| `ChatMessageConsumer.java` | `consume` | Call `chat(AcceptedChatCommand)` with `message.requestId()` |
| `WebhookRouterService.java` | `dispatch` | Keep its existing normalized UUID, validate it, call `chat(AcceptedChatCommand)` |
| `TransportParityCharacterizationTest.java` | success/failure stubs and verifications | Replace `ChatRequest` with the exact `AcceptedChatCommand` built from the message |
| `ChatControllerAuthTest.java` | existing broad `any()` stub | Use `any(AcceptedChatCommand.class)`; capture `AcceptedChatCommand` and inspect `request()` |
| `ChatServiceImplModeTest.java` | five direct legacy `chat/stream(ChatRequest)` calls | Keep unchanged to prove compatibility methods still work |
| `ChatServiceImplPersistenceTest.java` | one direct legacy `chat(ChatRequest)` call | Keep unchanged to prove compatibility; only its context-factory stubs and constructors change |

Every direct full `new ChatServiceImpl(...)` call is migrated in the same commit:

```text
src/test/java/com/springclaw/service/chat/impl/ChatServiceImplModeTest.java (2)
src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPersistenceTest.java (2)
src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPendingApprovalTest.java (1)
src/test/java/com/springclaw/architecture/FinalAnswerOwnershipCharacterizationTest.java (1)
```

Every scheduled-task helper call/stub is migrated in the same commit:

```text
src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java (1)
src/test/java/com/springclaw/service/task/TaskExecutionServiceTest.java (2 existing compatibility negative verifications, 2 new accepted-overload negative verifications, 1 accepted-overload stub, 1 accepted-overload verification)
src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPersistenceTest.java (1 direct compatibility call)
```

- [ ] **Step 1: Write the failing context and service identity tests**

Add to `ChatServiceImplModeTest`:

```java
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.service.chat.AcceptedChatCommand;
```

In `ChatContextFactoryTest`, use:

```java
String acceptedRunId = "0123456789abcdef0123456789abcdef";
ChatContext context = factory.build(
        new ChatRequest("s1", "u1", "北京呢", "api", "agent"),
        true,
        acceptedRunId
);

assertThat(context.requestId()).isEqualTo(acceptedRunId);
```

In `ContextPropagationCharacterizationTest.chatContextFactoryBuildsCurrentInjectionShape`, use:

```java
String acceptedRunId = "11111111111111111111111111111111";
ChatContext context = factory.build(
        new ChatRequest("session-A", "alice", "为什么登录失败", "api", "agent"),
        true,
        acceptedRunId
);

assertThat(context.requestId()).isEqualTo(acceptedRunId);
assertThat(context.contextInjection().observePrompt())
        .isEqualTo(assembled.observePrompt());
assertThat(context.contextInjection().policyPrompt()).isEmpty();
assertThat(context.contextInjection().pendingProposalPrompt()).isEmpty();
assertThat(context.contextInjection().renderForPrompt())
        .isEqualTo(assembled.observePrompt() + "\n\n");
assertThat(context.contextInjection().metadata())
        .containsEntry("contextSummary", assembled.sourceSummary());
```

In `ChatServiceImplModeTest.Fixture`, add:

```java
private final RunIdentityFactory runIdentityFactory = mock(RunIdentityFactory.class);
```

and initialize it with:

```java
when(runIdentityFactory.create())
        .thenReturn("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
when(runIdentityFactory.accept(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
```

Add:

```java
@Test
void acceptedChatAndStreamReuseTheSuppliedIdentity() {
    Fixture fixture = new Fixture();
    fixture.useEngine(fixture.simplifiedOparEngine);
    ChatRequest request = new ChatRequest("s1", "u1", "你好", "api");
    ChatContext context = fixture.buildChatContext("你好", "simplified", "默认");
    when(fixture.chatContextFactory.build(
            eq(request),
            eq(true),
            anyString()
    )).thenReturn(context);
    when(fixture.simplifiedOparEngine.execute(any(), any()))
            .thenReturn(new ChatExecutionResult(
                    "observe",
                    "SIMPLIFIED",
                    "ACTION",
                    "answer",
                    true
            ));

    fixture.build().chat(new AcceptedChatCommand(
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            request
    ));
    SseEmitter emitter = fixture.build().stream(new AcceptedChatCommand(
            "cccccccccccccccccccccccccccccccc",
            request
    ));

    verify(fixture.runIdentityFactory, never()).create();
    verify(fixture.runIdentityFactory).accept(
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    );
    verify(fixture.runIdentityFactory).accept(
            "cccccccccccccccccccccccccccccccc"
    );
    verify(fixture.chatContextFactory).build(
            request,
            true,
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    );
    verify(fixture.sseEventBridge, timeout(1000)).sendTrace(
            eq(emitter),
            eq("cccccccccccccccccccccccccccccccc"),
            eq("接收请求"),
            eq("request"),
            eq("started"),
            anyString(),
            eq(0L)
    );
    verify(fixture.chatContextFactory, timeout(1000)).build(
            request,
            true,
            "cccccccccccccccccccccccccccccccc"
    );
    emitter.complete();
}
```

Change all seven `ChatServiceImplModeTest` context stubs and both `ChatServiceImplPersistenceTest` context stubs to:

```java
when(chatContextFactory.build(
        any(ChatRequest.class),
        anyBoolean(),
        anyString()
)).thenReturn(context);
```

The first matcher must remain `any(ChatRequest.class)` in `ChatServiceImplPersistenceTest`; do not use raw `any()`.

- [ ] **Step 2: Write failing ingress, redelivery, webhook, scheduled-task, and DTO-shape tests**

Add to `ChatControllerAuthTest`:

```java
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.service.chat.AcceptedChatCommand;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
```

In `ChatControllerAuthTest`, replace the existing broad stub and capture with typed accepted-command forms:

```java
when(chatService.chat(any(AcceptedChatCommand.class)))
        .thenReturn(new ChatResponse("s1", "ok", "m1", 1L));

ArgumentCaptor<AcceptedChatCommand> captor =
        ArgumentCaptor.forClass(AcceptedChatCommand.class);
verify(chatService).chat(captor.capture());
Assertions.assertEquals("user_local", captor.getValue().request().userId());
Assertions.assertEquals("agent", captor.getValue().request().responseMode());
```

Do not use `when(chatService.chat(any()))`: once `ChatService` has two overloads it is ambiguous. Add:

```java
@Test
void syncAndStreamEachCreateOneAcceptedIdentityAtTheController() {
    ChatService chatService = mock(ChatService.class);
    RunIdentityFactory identityFactory = mock(RunIdentityFactory.class);
    when(identityFactory.create())
            .thenReturn("11111111111111111111111111111111")
            .thenReturn("22222222222222222222222222222222");
    when(chatService.chat(any(AcceptedChatCommand.class)))
            .thenReturn(new ChatResponse("s1", "ok", "m1", 1L));
    when(chatService.stream(any(AcceptedChatCommand.class)))
            .thenReturn(new SseEmitter());
    ChatController controller = new ChatController(
            chatService,
            mock(ChatMessageProducer.class),
            mock(AsyncChatResultStore.class),
            mock(MessageEventService.class),
            mock(AiProviderService.class),
            identityFactory
    );
    RequestUserContextHolder.set(new RequestUserContext(
            "user_local",
            "USER",
            System.currentTimeMillis() + 60_000
    ));

    controller.send(new ChatRequest("s1", null, "你好", "api", "agent"));
    controller.stream(new ChatRequest("s1", null, "继续", "api", "agent"));

    ArgumentCaptor<AcceptedChatCommand> commands =
            ArgumentCaptor.forClass(AcceptedChatCommand.class);
    verify(chatService).chat(commands.capture());
    verify(chatService).stream(commands.capture());
    assertThat(commands.getAllValues())
            .extracting(AcceptedChatCommand::runId)
            .containsExactly(
                    "11111111111111111111111111111111",
                    "22222222222222222222222222222222"
            );
}
```

Add the async acceptance assertion:

```java
@Test
void asyncAcceptanceUsesOneIdentityForQueueMessageAndResponse() {
    ChatService chatService = mock(ChatService.class);
    ChatMessageProducer producer = mock(ChatMessageProducer.class);
    AsyncChatResultStore resultStore = mock(AsyncChatResultStore.class);
    RunIdentityFactory identityFactory = mock(RunIdentityFactory.class);
    when(identityFactory.create())
            .thenReturn("33333333333333333333333333333333");
    ChatController controller = new ChatController(
            chatService,
            producer,
            resultStore,
            mock(MessageEventService.class),
            mock(AiProviderService.class),
            identityFactory
    );
    RequestUserContextHolder.set(new RequestUserContext(
            "user_local",
            "USER",
            System.currentTimeMillis() + 60_000
    ));

    ApiResponse<AsyncChatAcceptedResponse> response = controller.sendAsync(
            new ChatRequest("s1", null, "异步处理", "api", "agent")
    );

    ArgumentCaptor<AsyncChatRequestMessage> message =
            ArgumentCaptor.forClass(AsyncChatRequestMessage.class);
    verify(resultStore).markQueued(message.capture());
    verify(producer).sendRequest(message.getValue());
    assertThat(response.getData().requestId())
            .isEqualTo("33333333333333333333333333333333");
    assertThat(message.getValue().requestId())
            .isEqualTo(response.getData().requestId());
}
```

Add its imports:

```java
import com.springclaw.dto.chat.AsyncChatAcceptedResponse;
import com.springclaw.service.chat.async.AsyncChatRequestMessage;
```

In `TransportParityCharacterizationTest`, replace each success/failure `expectedRequest` with:

```java
AcceptedChatCommand expectedCommand = new AcceptedChatCommand(
        message.requestId(),
        new ChatRequest(
                message.sessionKey(),
                message.userId(),
                message.message(),
                message.channel(),
                message.responseMode()
        )
);
```

The success path uses:

```java
when(chatService.chat(expectedCommand)).thenReturn(response);
verify(chatService).chat(expectedCommand);
```

The failure path uses:

```java
when(chatService.chat(expectedCommand))
        .thenThrow(new IllegalStateException("chat unavailable"));
verify(chatService).chat(expectedCommand);
```

Create `WebhookRouterServiceTest.java`:

```java
package com.springclaw.service.webhook;

import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.service.chat.AcceptedChatCommand;
import com.springclaw.service.chat.ChatService;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.strategy.channel.ChannelAdapter;
import com.springclaw.strategy.channel.factory.ChannelAdapterFactory;
import com.springclaw.strategy.channel.model.UnifiedInboundMessage;
import com.springclaw.strategy.channel.outbound.ChannelOutboundDispatcher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookRouterServiceTest {

    @Test
    void dispatchValidatesItsExistingRequestIdAndPassesItToChat() {
        ChannelAdapterFactory adapterFactory = mock(ChannelAdapterFactory.class);
        ChatService chatService = mock(ChatService.class);
        MessageEventService eventService = mock(MessageEventService.class);
        ChannelOutboundDispatcher dispatcher = mock(ChannelOutboundDispatcher.class);
        RunIdentityFactory identityFactory = mock(RunIdentityFactory.class);
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        UnifiedInboundMessage inbound = new UnifiedInboundMessage(
                "feishu",
                "session-A",
                "user-A",
                "hello"
        );
        when(adapterFactory.getRequired("feishu")).thenReturn(adapter);
        when(adapter.adapt(Map.of("event", "message"))).thenReturn(inbound);
        when(identityFactory.accept(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(chatService.chat(org.mockito.ArgumentMatchers.any(
                AcceptedChatCommand.class
        ))).thenReturn(new ChatResponse("session-A", "answer", "model", 1L));
        WebhookRouterService service = new WebhookRouterService(
                adapterFactory,
                chatService,
                eventService,
                dispatcher,
                identityFactory
        );

        service.dispatch("feishu", Map.of("event", "message"));

        ArgumentCaptor<String> acceptedId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AcceptedChatCommand> command =
                ArgumentCaptor.forClass(AcceptedChatCommand.class);
        verify(identityFactory).accept(acceptedId.capture());
        verify(chatService).chat(command.capture());
        assertThat(acceptedId.getValue()).matches("[0-9a-f]{32}");
        assertThat(command.getValue().runId()).isEqualTo(acceptedId.getValue());
        assertThat(command.getValue().request().sessionKey()).isEqualTo("session-A");
    }
}
```

In `TaskExecutionServiceTest.shouldExecuteAgentTaskThroughChatService`, capture the ID passed to `ScheduledTaskExecutionService.start` and require the same value in the new overload:

```java
import com.springclaw.domain.entity.ScheduledTaskExecution;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.eq;
```

```java
when(executionService.start(anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> {
            var record = new ScheduledTaskExecution();
            record.setExecutionId("exec_2");
            return record;
        });
when(chatService.executeTaskMessage(
        any(ChatRequest.class),
        anyBoolean(),
        anyString()
)).thenAnswer(invocation -> new ChatServiceImpl.TaskChatExecutionResult(
        "task:shadow:task_2",
        "今日进展：xxx",
        invocation.getArgument(2),
        "simplified",
        "task"
));

TaskExecutionOutcome outcome = service.runTask(task, "MANUAL");

ArgumentCaptor<String> startedRunId = ArgumentCaptor.forClass(String.class);
verify(executionService).start(
        eq("task_2"),
        eq("MANUAL"),
        startedRunId.capture()
);
verify(chatService).executeTaskMessage(
        any(ChatRequest.class),
        eq(false),
        eq(startedRunId.getValue())
);
assertThat(outcome.requestId()).isEqualTo(startedRunId.getValue());
```

Keep the two skill-task compatibility negative verifications, but type their first matcher:

```java
verify(chatService, never()).executeTaskMessage(
        any(ChatRequest.class),
        anyBoolean()
);
```

Immediately add the accepted-ID negative verification in both skill-task tests:

```java
verify(chatService, never()).executeTaskMessage(
        any(ChatRequest.class),
        anyBoolean(),
        anyString()
);
```

Create `CanonicalTransportIdentityTest.java` with both DTO-shape and redelivery coverage:

```java
package com.springclaw.architecture;

import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.service.chat.AcceptedChatCommand;
import com.springclaw.service.chat.ChatService;
import com.springclaw.service.chat.async.AsyncChatRequestMessage;
import com.springclaw.service.chat.async.AsyncChatResultPayload;
import com.springclaw.service.chat.async.AsyncChatResultStore;
import com.springclaw.service.chat.async.ChatMessageConsumer;
import com.springclaw.service.chat.async.ChatMessageProducer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CanonicalTransportIdentityTest {

    @Test
    void publicTransportDtoShapesRemainUnchanged() {
        assertThat(recordShape(ChatRequest.class)).containsExactly(
                "sessionKey:String",
                "userId:String",
                "message:String",
                "channel:String",
                "responseMode:String"
        );
        assertThat(recordShape(ChatResponse.class)).containsExactly(
                "sessionKey:String",
                "answer:String",
                "model:String",
                "timestamp:long"
        );
        assertThat(recordShape(AsyncChatRequestMessage.class)).containsExactly(
                "requestId:String",
                "sessionKey:String",
                "userId:String",
                "message:String",
                "channel:String",
                "createdAt:long",
                "responseMode:String"
        );
    }

    @Test
    void rabbitRedeliveryReusesIdentityButExecutesAgain() {
        ChatService chatService = mock(ChatService.class);
        AsyncChatResultStore resultStore = mock(AsyncChatResultStore.class);
        ChatMessageConsumer consumer = new ChatMessageConsumer(
                chatService,
                resultStore,
                mock(ChatMessageProducer.class),
                mock(SimpMessagingTemplate.class)
        );
        AsyncChatRequestMessage message = new AsyncChatRequestMessage(
                "44444444444444444444444444444444",
                "session",
                "user",
                "hello",
                "api",
                100L,
                "agent"
        );
        ChatResponse response = new ChatResponse(
                "session",
                "answer",
                "model",
                123L
        );
        when(chatService.chat(any(AcceptedChatCommand.class)))
                .thenReturn(response);
        when(resultStore.markCompleted(message, "answer", "model"))
                .thenReturn(new AsyncChatResultPayload(
                        message.requestId(),
                        "COMPLETED",
                        message.sessionKey(),
                        message.channel(),
                        response.answer(),
                        response.model(),
                        message.createdAt(),
                        456L,
                        ""
                ));

        consumer.consume(message);
        consumer.consume(message);

        ArgumentCaptor<AcceptedChatCommand> commands =
                ArgumentCaptor.forClass(AcceptedChatCommand.class);
        verify(chatService, times(2)).chat(commands.capture());
        assertThat(commands.getAllValues())
                .extracting(AcceptedChatCommand::runId)
                .containsExactly(message.requestId(), message.requestId());
    }

    private static List<String> recordShape(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName()
                        + ":"
                        + component.getType().getSimpleName())
                .toList();
    }
}
```

- [ ] **Step 3: Run the atomic task tests and verify RED**

```bash
mvn -q \
  -Dtest=ChatContextFactoryTest,ChatServiceImplModeTest,ChatControllerAuthTest,TransportParityCharacterizationTest,CanonicalTransportIdentityTest,WebhookRouterServiceTest,TaskExecutionServiceTest,ContextPropagationCharacterizationTest \
  test
```

Expected: test compilation fails because the accepted-command service overloads, three-argument context build, accepted-ID task overload, and identity-aware constructors do not exist.

- [ ] **Step 4: Add service overloads and replace context-factory ownership**

Add these production imports:

```java
// ChatServiceImpl.java
import com.springclaw.runtime.identity.DefaultRunIdentityFactory;
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.service.chat.AcceptedChatCommand;

// ChatController.java
import com.springclaw.runtime.identity.DefaultRunIdentityFactory;
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.service.chat.AcceptedChatCommand;

// ChatMessageConsumer.java
import com.springclaw.service.chat.AcceptedChatCommand;

// WebhookRouterService.java
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.service.chat.AcceptedChatCommand;
```

Replace `ChatService` with:

```java
package com.springclaw.service.chat;

import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface ChatService {

    ChatResponse chat(ChatRequest request);

    ChatResponse chat(AcceptedChatCommand command);

    SseEmitter stream(ChatRequest request);

    SseEmitter stream(AcceptedChatCommand command);
}
```

In `ChatContextFactory`, remove `java.util.UUID`, replace the two-argument method with:

```java
public ChatContext build(ChatRequest request,
                         boolean persistSession,
                         String acceptedRunId) {
    if (!StringUtils.hasText(acceptedRunId)) {
        throw new IllegalArgumentException("acceptedRunId must not be blank");
    }
    String requestId = acceptedRunId;
```

and leave the remainder of the existing method unchanged. Do not retain `build(ChatRequest, boolean)`.

In `ChatServiceImpl`, inject and assign:

```java
private final RunIdentityFactory runIdentityFactory;
```

Add `RunIdentityFactory runIdentityFactory` immediately before the final two `@Value` constructor parameters. The package-visible compatibility constructor delegates with:

```java
new DefaultRunIdentityFactory(),
false,
true
```

Use these entry methods:

```java
@Override
public ChatResponse chat(ChatRequest request) {
    return chat(new AcceptedChatCommand(runIdentityFactory.create(), request));
}

@Override
public ChatResponse chat(AcceptedChatCommand command) {
    String acceptedRunId = runIdentityFactory.accept(command.runId());
    TaskChatExecutionResult result = executeInternal(
            command.request(),
            true,
            true,
            acceptedRunId
    );
    return new ChatResponse(
            result.sessionKey(),
            result.answer(),
            aiProviderService.activeClient().displayName(),
            System.currentTimeMillis()
    );
}

@Override
public SseEmitter stream(ChatRequest request) {
    return stream(new AcceptedChatCommand(runIdentityFactory.create(), request));
}

@Override
public SseEmitter stream(AcceptedChatCommand command) {
    String acceptedRunId = runIdentityFactory.accept(command.runId());
    ChatRequest request = command.request();
    chatGuardService.checkRateLimit(request.sessionKey());
    String lockToken = chatGuardService.acquireSessionLock(request.sessionKey());
    AtomicBoolean lockReleased = new AtomicBoolean(false);
    SseEmitter emitter = new SseEmitter(1_800_000L);
    AtomicReference<Disposable> disposableRef = new AtomicReference<>();
    emitter.onCompletion(() -> {
        Disposable disposable = disposableRef.get();
        if (disposable != null) {
            disposable.dispose();
        }
        releaseSessionLockOnce(request.sessionKey(), lockToken, lockReleased);
    });
    emitter.onTimeout(() -> {
        Disposable disposable = disposableRef.get();
        if (disposable != null) {
            disposable.dispose();
        }
        releaseSessionLockOnce(request.sessionKey(), lockToken, lockReleased);
        emitter.complete();
    });
    CompletableFuture.runAsync(() -> executeStream(
            request,
            acceptedRunId,
            lockToken,
            lockReleased,
            emitter,
            disposableRef
    ));
    return emitter;
}
```

Change `executeStream` to accept `String acceptedRunId`, use that value in the initial `sendTrace`, and call:

```java
ChatContext context = chatContextFactory.build(
        request,
        true,
        acceptedRunId
);
```

Replace scheduled-task entry code with:

```java
public TaskChatExecutionResult executeTaskMessage(
        ChatRequest request,
        boolean persistResult
) {
    return executeTaskMessage(
            request,
            persistResult,
            runIdentityFactory.create()
    );
}

public TaskChatExecutionResult executeTaskMessage(
        ChatRequest request,
        boolean persistResult,
        String runId
) {
    return executeInternal(
            request,
            false,
            persistResult,
            runIdentityFactory.accept(runId)
    );
}
```

Change `executeInternal` to receive `String acceptedRunId` and call:

```java
ChatContext context = chatContextFactory.build(
        request,
        persistResult,
        acceptedRunId
);
```

Do not change any field, component, constructor, or accessor of `TaskChatExecutionResult`.

- [ ] **Step 5: Migrate every ingress without adding new identity owners**

In `ChatController`, add:

```java
private final RunIdentityFactory runIdentityFactory;
```

Use these constructors:

```java
@Autowired
public ChatController(ChatService chatService,
                      ChatMessageProducer chatMessageProducer,
                      AsyncChatResultStore asyncChatResultStore,
                      MessageEventService messageEventService,
                      AiProviderService aiProviderService,
                      AgentActionProposalService actionProposalService,
                      AgentRunTraceService agentRunTraceService,
                      RunIdentityFactory runIdentityFactory) {
    this.chatService = chatService;
    this.chatMessageProducer = chatMessageProducer;
    this.asyncChatResultStore = asyncChatResultStore;
    this.messageEventService = messageEventService;
    this.aiProviderService = aiProviderService;
    this.actionProposalService = actionProposalService;
    this.agentRunTraceService = agentRunTraceService;
    this.runIdentityFactory = runIdentityFactory;
}

ChatController(ChatService chatService,
               ChatMessageProducer chatMessageProducer,
               AsyncChatResultStore asyncChatResultStore,
               MessageEventService messageEventService,
               AiProviderService aiProviderService) {
    this(
            chatService,
            chatMessageProducer,
            asyncChatResultStore,
            messageEventService,
            aiProviderService,
            null,
            null,
            new DefaultRunIdentityFactory()
    );
}

ChatController(ChatService chatService,
               ChatMessageProducer chatMessageProducer,
               AsyncChatResultStore asyncChatResultStore,
               MessageEventService messageEventService,
               AiProviderService aiProviderService,
               RunIdentityFactory runIdentityFactory) {
    this(
            chatService,
            chatMessageProducer,
            asyncChatResultStore,
            messageEventService,
            aiProviderService,
            null,
            null,
            runIdentityFactory
    );
}
```

The three endpoints use:

```java
ChatRequest normalizedRequest = normalizeRequest(request);
String acceptedRunId = runIdentityFactory.create();
return ApiResponse.success(chatService.chat(
        new AcceptedChatCommand(acceptedRunId, normalizedRequest)
));
```

```java
ChatRequest normalizedRequest = normalizeRequest(request);
String acceptedRunId = runIdentityFactory.create();
return chatService.stream(
        new AcceptedChatCommand(acceptedRunId, normalizedRequest)
);
```

```java
ChatRequest normalizedRequest = normalizeRequest(request);
String requestId = runIdentityFactory.create();
String channel = StringUtils.hasText(normalizedRequest.channel())
        ? normalizedRequest.channel()
        : "api";
AsyncChatRequestMessage message = new AsyncChatRequestMessage(
        requestId,
        normalizedRequest.sessionKey(),
        normalizedRequest.userId(),
        normalizedRequest.message(),
        channel,
        System.currentTimeMillis(),
        normalizedRequest.responseMode()
);
asyncChatResultStore.markQueued(message);
chatMessageProducer.sendRequest(message);
return ApiResponse.success(new AsyncChatAcceptedResponse(
        requestId,
        "QUEUED",
        channel,
        System.currentTimeMillis()
));
```

Register `RunIdentityFactory` in `shouldCreateChatControllerBeanWhenSpringResolvesConstructors`.

In `ChatMessageConsumer`, replace the service call with:

```java
ChatResponse response = chatService.chat(new AcceptedChatCommand(
        message.requestId(),
        new ChatRequest(
                message.sessionKey(),
                message.userId(),
                message.message(),
                message.channel(),
                message.responseMode()
        )
));
```

Do not inject a factory into the consumer and do not add a result-store pre-check. Redelivery reuses the ID and may execute again.

In `WebhookRouterService`, inject `RunIdentityFactory` as the final constructor dependency. Keep its existing ID creation, validate that exact value, and pass it onward:

```java
String requestId = runIdentityFactory.accept(
        UUID.randomUUID().toString().replace("-", "")
);
```

```java
response = chatService.chat(new AcceptedChatCommand(
        requestId,
        new ChatRequest(
                inboundMessage.sessionKey(),
                inboundMessage.userId(),
                inboundMessage.text(),
                inboundMessage.channel()
        )
));
```

Do not replace webhook ownership with `runIdentityFactory.create()`: the point of this migration is to preserve and validate the ID this ingress already owns.

In `TaskExecutionService`, pass the existing `requestId` into agent execution:

```java
case "agent" -> executeAgentTask(task, requestId);
```

Replace the helper with:

```java
private TaskExecutionOutcome executeAgentTask(
        ScheduledTask task,
        String runId
) {
    String prompt = resolveAgentPrompt(task.getInputPayload());
    ChatServiceImpl.TaskChatExecutionResult result =
            chatService.executeTaskMessage(
                    new ChatRequest(
                            resolveTaskSessionKey(task),
                            task.getOwnerUserId(),
                            prompt,
                            task.getChannel()
                    ),
                    shouldPersistToSession(task),
                    runId
            );
    return new TaskExecutionOutcome(
            buildSummary(task, result.answer()),
            result.answer(),
            result.requestId(),
            result.sessionKey()
    );
}
```

The same `requestId` must be used by `ScheduledTaskExecutionService.start`, agent chat execution, success completion, and failure completion.

- [ ] **Step 6: Update every test stub and direct constructor before compiling**

For the six full `ChatServiceImpl` constructor calls listed in the inventory, insert the fixture `runIdentityFactory` or:

```java
new DefaultRunIdentityFactory()
```

immediately before the final two boolean arguments.

In `ChatServiceImplModeTest.Fixture.build()` and `buildWithRuntime()`, the final arguments are:

```java
runIdentityFactory,
false,
true
```

Update all nine context-factory stubs exactly as listed in the inventory. Update `ChatControllerAuthTest` to use only `any(AcceptedChatCommand.class)` for accepted chat/stream overloads. In `TaskExecutionServiceTest`, type both existing two-argument negative verifications, add both three-argument negative verifications, and migrate the agent-task stub/verification to the three-argument overload. Keep the one direct two-argument `executeTaskMessage` call in `ChatServiceImplPersistenceTest` to characterize compatibility.

- [ ] **Step 7: Run focused tests, compile all tests, and verify GREEN**

```bash
mvn -q \
  -Dtest=ChatContextFactoryTest,ChatServiceImplModeTest,ChatServiceImplPersistenceTest,ChatServiceImplPendingApprovalTest,ChatControllerAuthTest,WebhookRouterServiceTest,TaskExecutionServiceTest,ContextPropagationCharacterizationTest,FinalAnswerOwnershipCharacterizationTest,TransportParityCharacterizationTest,CanonicalTransportIdentityTest \
  test
mvn -q -DskipTests compile
mvn -q -DskipTests test-compile
```

Expected: every command exits `0`. The committed tree has no two-argument `ChatContextFactory.build`, no ambiguous Mockito `chat(any())`, webhook and scheduled execution reuse their ingress-owned IDs, and Rabbit redelivery invokes chat twice with one repeated ID.

- [ ] **Step 8: Prove the migration inventory is complete**

```bash
rg -n --glob '*.java' 'chatContextFactory\.build\(' src/main src/test
rg -n --glob '*.java' '\b(chatService|service)\.(chat|stream)\(' src/main src/test
rg -n --glob '*.java' 'executeTaskMessage\(' src/main src/test
rg -n --glob '*.java' 'chatService\.chat\(any\(\)\)' src/test
```

Expected:

- Every `chatContextFactory.build` result has three arguments.
- Production accepted-ingress calls are controller sync/SSE, consumer, and webhook.
- Legacy direct service calls remain only in `ChatServiceImplModeTest` and `ChatServiceImplPersistenceTest`.
- Scheduled production execution uses the three-argument overload; the two-argument overload remains only as compatibility coverage.
- The final broad-matcher scan prints no matches.

- [ ] **Step 9: Commit the atomic migration**

```bash
git add \
  src/main/java/com/springclaw/service/chat/ChatService.java \
  src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java \
  src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java \
  src/main/java/com/springclaw/controller/ChatController.java \
  src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java \
  src/main/java/com/springclaw/service/webhook/WebhookRouterService.java \
  src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java \
  src/test/java/com/springclaw/architecture/CanonicalTransportIdentityTest.java \
  src/test/java/com/springclaw/service/webhook/WebhookRouterServiceTest.java \
  src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java \
  src/test/java/com/springclaw/service/chat/impl/ChatServiceImplModeTest.java \
  src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPersistenceTest.java \
  src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPendingApprovalTest.java \
  src/test/java/com/springclaw/controller/ChatControllerAuthTest.java \
  src/test/java/com/springclaw/service/task/TaskExecutionServiceTest.java \
  src/test/java/com/springclaw/architecture/ContextPropagationCharacterizationTest.java \
  src/test/java/com/springclaw/architecture/FinalAnswerOwnershipCharacterizationTest.java \
  src/test/java/com/springclaw/architecture/TransportParityCharacterizationTest.java
git commit -m "refactor: migrate canonical chat identity atomically"
```

### Task 3: Put the canonical ID into tool execution and proposal ownership

**Files:**
- Modify the 13 verified five-argument construction sites in:
  - `src/main/java/com/springclaw/service/chat/impl/SimplifiedOparEngine.java` (2)
  - `src/main/java/com/springclaw/service/chat/impl/OparLoopEngine.java` (2)
  - `src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java` (1)
  - `src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java` (2)
  - `src/main/java/com/springclaw/service/agent/executor/WorkspaceCapabilityExecutor.java` (1)
  - `src/main/java/com/springclaw/service/agent/executor/LocalFilesCapabilityExecutor.java` (1)
  - `src/main/java/com/springclaw/service/agent/executor/WebCapabilityExecutor.java` (1)
  - `src/main/java/com/springclaw/service/agent/executor/SystemHealthCapabilityExecutor.java` (1)
  - `src/main/java/com/springclaw/service/agent/executor/SkillCapabilityExecutor.java` (1)
  - `src/main/java/com/springclaw/service/agent/executor/RealtimeCapabilityExecutor.java` (1)
- Modify: `src/test/java/com/springclaw/service/proposal/ToolInvocationProposalServiceConfirmTest.java`
- Modify: `src/test/java/com/springclaw/service/proposal/ToolProposalExecutionServiceTest.java`
- Create: `src/test/java/com/springclaw/architecture/CanonicalToolOwnershipTest.java`

`ToolProposalExecutionService` is already correct: its production constructor call uses `proposal.requestId()`, `proposal.runId()`, and `proposal.roleCode()`. Do not modify it.

- [ ] **Step 1: Write proposal ownership assertions**

Add imports to `ToolInvocationProposalServiceConfirmTest`:

```java
import com.springclaw.tool.runtime.ToolExecutionContext;

import java.util.Set;
```

Add to `ToolInvocationProposalServiceConfirmTest`:

```java
@Test
void createPendingCopiesCanonicalIdentityIntoProposalRow() {
    String canonicalId = "55555555555555555555555555555555";
    ToolInvocationSnapshot snapshot = new ToolInvocationSnapshot(
            "WorkspaceEditToolPack.workspaceWriteFile",
            "workspace",
            "[\"docs/a.md\",\"content\"]",
            "hash",
            "write",
            List.of("docs/a.md"),
            "write docs/a.md",
            false,
            Set.of(),
            "head"
    );
    ToolExecutionContext context = new ToolExecutionContext(
            "session-A",
            "api",
            "u1",
            canonicalId,
            "ACT-1",
            canonicalId,
            "USER"
    );
    when(repository.insert(any(ToolInvocationProposal.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

    ToolInvocationProposal proposal = service.createPending(snapshot, context);

    assertThat(proposal.requestId()).isEqualTo(canonicalId);
    assertThat(proposal.runId()).isEqualTo(canonicalId);
    assertThat(proposal.sessionKey()).isEqualTo("session-A");
    assertThat(proposal.userId()).isEqualTo("u1");
}
```

In `ToolProposalExecutionServiceTest.onExecutionRequested_setsBothContextsBeforeInvokingTool`, change the fixture proposal to use one canonical value:

```java
String canonicalId = "66666666666666666666666666666666";
```

and assert:

```java
assertThat(ctxDuringInvoke.get().requestId()).isEqualTo(canonicalId);
assertThat(ctxDuringInvoke.get().runId()).isEqualTo(canonicalId);
assertThat(approvedDuringInvoke.get().requestId()).isEqualTo(canonicalId);
assertThat(approvedDuringInvoke.get().runId()).isEqualTo(canonicalId);
```

Change `sampleExecutingProposal` to accept `canonicalId` and pass it into both the `requestId` and `runId` record components.

Create `CanonicalToolOwnershipTest.java`:

```java
package com.springclaw.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalToolOwnershipTest {

    private static final Pattern CONSTRUCTION = Pattern.compile(
            "new\\s+ToolExecutionContext\\s*\\((.*?)\\)\\s*;",
            Pattern.DOTALL
    );

    private static final List<String> FILES = List.of(
            "src/main/java/com/springclaw/service/chat/impl/SimplifiedOparEngine.java",
            "src/main/java/com/springclaw/service/chat/impl/OparLoopEngine.java",
            "src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java",
            "src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java",
            "src/main/java/com/springclaw/service/agent/executor/WorkspaceCapabilityExecutor.java",
            "src/main/java/com/springclaw/service/agent/executor/LocalFilesCapabilityExecutor.java",
            "src/main/java/com/springclaw/service/agent/executor/WebCapabilityExecutor.java",
            "src/main/java/com/springclaw/service/agent/executor/SystemHealthCapabilityExecutor.java",
            "src/main/java/com/springclaw/service/agent/executor/SkillCapabilityExecutor.java",
            "src/main/java/com/springclaw/service/agent/executor/RealtimeCapabilityExecutor.java",
            "src/main/java/com/springclaw/service/proposal/ToolProposalExecutionService.java"
    );

    @Test
    void allProductionToolContextsCarryRequestAndRunOwnership() throws IOException {
        int constructionCount = 0;
        for (String file : FILES) {
            Matcher matcher = CONSTRUCTION.matcher(
                    Files.readString(Path.of(file))
            );
            while (matcher.find()) {
                constructionCount++;
                List<String> arguments = topLevelArguments(matcher.group(1));
                assertThat(arguments)
                        .as(file + " construction " + constructionCount)
                        .hasSize(7);
                if (file.endsWith("ToolProposalExecutionService.java")) {
                    assertThat(arguments.get(3)).isEqualTo("proposal.requestId()");
                    assertThat(arguments.get(5)).isEqualTo("proposal.runId()");
                } else {
                    assertThat(arguments.get(5)).isEqualTo(arguments.get(3));
                }
            }
        }
        assertThat(constructionCount).isEqualTo(14);
    }

    private List<String> topLevelArguments(String arguments) {
        List<String> values = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < arguments.length(); index++) {
            char value = arguments.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    inString = false;
                }
                continue;
            }
            if (value == '"') {
                inString = true;
            } else if (value == '(' || value == '[' || value == '{') {
                depth++;
            } else if (value == ')' || value == ']' || value == '}') {
                depth--;
            } else if (value == ',' && depth == 0) {
                values.add(arguments.substring(start, index).trim());
                start = index + 1;
            }
        }
        if (!arguments.isBlank()) {
            values.add(arguments.substring(start).trim());
        }
        return values;
    }
}
```

- [ ] **Step 2: Run ownership tests and verify RED**

Run:

```bash
mvn -q \
  -Dtest=CanonicalToolOwnershipTest,ToolInvocationProposalServiceConfirmTest,ToolProposalExecutionServiceTest \
  test
```

Expected: `CanonicalToolOwnershipTest` fails because 13 production constructions still have five arguments. The proposal tests compile and preserve their existing behavior.

- [ ] **Step 3: Replace every verified five-argument tool context construction**

For `SimplifiedOparEngine`, both contexts become:

```java
ToolExecutionContext localCtx = new ToolExecutionContext(
        assembled.sessionKey(),
        assembled.channel(),
        assembled.userId(),
        requestId,
        "LOCAL-SHORTCUT",
        requestId,
        null
);
```

```java
ToolExecutionContext toolContext = new ToolExecutionContext(
        assembled.sessionKey(),
        assembled.channel(),
        assembled.userId(),
        requestId,
        "ACT-SIMPLIFIED",
        requestId,
        null
);
```

For `OparLoopEngine`, both contexts become:

```java
ToolExecutionContext localShortcutCtx = new ToolExecutionContext(
        assembled.sessionKey(),
        assembled.channel(),
        assembled.userId(),
        requestId,
        "LOCAL-SHORTCUT",
        requestId,
        null
);
```

```java
ToolExecutionContext context = new ToolExecutionContext(
        assembled.sessionKey(),
        assembled.channel(),
        assembled.userId(),
        requestId,
        "ACT-" + stepNo,
        requestId,
        null
);
```

For `AutonomousLoopEngine`, use:

```java
ToolExecutionContext toolContext = new ToolExecutionContext(
        assembled.sessionKey(),
        assembled.channel(),
        assembled.userId(),
        requestId,
        "AUTONOMOUS",
        requestId,
        ctx.roleCode()
);
```

For the blocking `ModelLedStreamEngine` construction, use:

```java
ToolExecutionContext toolContext = new ToolExecutionContext(
        ctx.session().getSessionKey(),
        ctx.channel(),
        ctx.userId(),
        ctx.requestId(),
        "ACT-BLOCKING",
        ctx.requestId(),
        ctx.roleCode()
);
```

For the streaming construction, use:

```java
ToolExecutionContext toolContext = new ToolExecutionContext(
        context.session().getSessionKey(),
        context.channel(),
        context.userId(),
        context.requestId(),
        "ACT-STREAM",
        context.requestId(),
        context.roleCode()
);
```

For each of the six capability executors, replace the one-line constructor with:

```java
ToolExecutionContext context = new ToolExecutionContext(
        assembled.sessionKey(),
        assembled.channel(),
        assembled.userId(),
        requestId,
        "AGENT-RUNTIME",
        requestId,
        null
);
```

Do not change the capability `execute(AgentDecision, AssembledContext, String)` API merely to add `roleCode`; it is not required to establish `requestId == runId`.

- [ ] **Step 4: Prove no five-argument production construction remains**

Run:

```bash
rg -n -U \
  'new ToolExecutionContext\([^;]*\);' \
  src/main/java/com/springclaw/service/chat/impl \
  src/main/java/com/springclaw/service/agent/executor \
  src/main/java/com/springclaw/service/proposal
```

Expected: 14 total production constructions. Thirteen chat/engine/capability constructions show seven arguments with `runId` equal to the existing request ID. The existing proposal-resume construction shows `proposal.requestId()` and `proposal.runId()`.

- [ ] **Step 5: Run tool and proposal tests and verify GREEN**

Run:

```bash
mvn -q \
  -Dtest=CanonicalToolOwnershipTest,ToolRuntimeAspectInterceptionIT,ToolInvocationProposalServiceConfirmTest,ToolProposalExecutionServiceTest,ToolSafetyPathCharacterizationTest \
  test
mvn -q -DskipTests compile
mvn -q -DskipTests test-compile
```

Expected: all selected tests pass and both compile commands exit `0`; proposal rows and resumed execution preserve the same canonical request/run identity. No assertion changes proposal authorization rules. The second implementation commit is independently buildable.

- [ ] **Step 6: Commit tool ownership propagation**

```bash
git add \
  src/main/java/com/springclaw/service/chat/impl/SimplifiedOparEngine.java \
  src/main/java/com/springclaw/service/chat/impl/OparLoopEngine.java \
  src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java \
  src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java \
  src/main/java/com/springclaw/service/agent/executor/WorkspaceCapabilityExecutor.java \
  src/main/java/com/springclaw/service/agent/executor/LocalFilesCapabilityExecutor.java \
  src/main/java/com/springclaw/service/agent/executor/WebCapabilityExecutor.java \
  src/main/java/com/springclaw/service/agent/executor/SystemHealthCapabilityExecutor.java \
  src/main/java/com/springclaw/service/agent/executor/SkillCapabilityExecutor.java \
  src/main/java/com/springclaw/service/agent/executor/RealtimeCapabilityExecutor.java \
  src/test/java/com/springclaw/architecture/CanonicalToolOwnershipTest.java \
  src/test/java/com/springclaw/service/proposal/ToolInvocationProposalServiceConfirmTest.java \
  src/test/java/com/springclaw/service/proposal/ToolProposalExecutionServiceTest.java
git commit -m "fix: propagate canonical tool ownership"
```

### Task 4: Run Phase 2A acceptance and verify rollback boundaries

**Files:**
- Verification only; no production file is expected to change.

- [ ] **Step 1: Run all 67 Phase 1 contract tests**

```bash
mvn -q \
  -Dtest=RunStatusTest,ContextAndDecisionContractTest,ToolCompletionAndResultContractTest,RunEventContractTest,RunStateContractTest,RuntimeStrategyContractTest,LegacyRuntimeContractFixturesTest \
  test
```

Expected: 67 tests, 0 failures, 0 errors, 0 skips.

- [ ] **Step 2: Run all 47 production-backed characterization tests**

```bash
mvn -q \
  -Dtest=RuntimeRouteCharacterizationTest,ContextPropagationCharacterizationTest,ToolSafetyPathCharacterizationTest,FinalAnswerOwnershipCharacterizationTest,TransportParityCharacterizationTest \
  test
```

Expected:

```text
RuntimeRouteCharacterizationTest: 20
ContextPropagationCharacterizationTest: 5
ToolSafetyPathCharacterizationTest: 7
FinalAnswerOwnershipCharacterizationTest: 5
TransportParityCharacterizationTest: 10
Total: 47; failures/errors/skips: 0
```

- [ ] **Step 3: Run the 27 focused baseline tests**

```bash
mvn -q \
  -Dtest=EngineSelectorTest,ChatContextFactoryTest,PromptInjectionTest,AgentRuntimeEngineTest,ToolRuntimeAspectInterceptionIT,ToolInvocationProposalServiceConfirmTest \
  test
```

Expected: 27 tests, 0 failures, 0 errors, 0 skips. The known local MySQL `Public Key Retrieval is not allowed` warning may appear without failing Maven.

- [ ] **Step 4: Run all new and directly changed identity tests**

```bash
mvn -q \
  -Dtest=DefaultRunIdentityFactoryTest,ChatContextFactoryTest,ChatServiceImplModeTest,ChatServiceImplPersistenceTest,ChatControllerAuthTest,WebhookRouterServiceTest,TaskExecutionServiceTest,TransportParityCharacterizationTest,CanonicalTransportIdentityTest,CanonicalToolOwnershipTest,ToolInvocationProposalServiceConfirmTest,ToolProposalExecutionServiceTest \
  test
```

Expected: all selected tests pass, including webhook ingress ownership, scheduled execution ID reuse, typed Mockito overload selection, Rabbit redelivery reuse, and unchanged task result shape.

- [ ] **Step 5: Prove prohibited files and DTO shapes were not edited**

Run:

```bash
git diff --name-only 1ce67db..HEAD
```

Expected: no path under `src/main/java/com/springclaw/runtime/contract/`; no `ToolRuntimeAspect.java`, `ToolExecutionContext.java`, proposal authorization service, workspace guard, engine selector, resource configuration, migration, or DTO file.

Run:

```bash
git diff --exit-code 1ce67db..HEAD -- \
  src/main/java/com/springclaw/dto/chat/ChatRequest.java \
  src/main/java/com/springclaw/dto/chat/ChatResponse.java \
  src/main/java/com/springclaw/dto/chat/AsyncChatAcceptedResponse.java \
  src/main/java/com/springclaw/service/chat/async/AsyncChatRequestMessage.java \
  src/main/java/com/springclaw/service/chat/async/AsyncChatResultPayload.java \
  src/main/java/com/springclaw/tool/runtime/ToolExecutionContext.java \
  src/main/java/com/springclaw/tool/runtime/ToolRuntimeAspect.java \
  src/main/java/com/springclaw/service/proposal/ToolProposalExecutionService.java \
  src/main/java/com/springclaw/service/agent/EngineSelector.java \
  src/main/resources/application.yml \
  .env.example
```

Expected: exit code `0` with no diff.

- [ ] **Step 6: Run the complete Maven suite**

```bash
mvn -q test
```

Expected: exit code `0`; no test requires a real model call. The known local MySQL warning may appear without failing Maven.

- [ ] **Step 7: Record the rollback unit**

Rollback Phase 2A as one unit:

1. Revert the atomic context/service migration together: controller, consumer, webhook router, scheduled task execution, service overloads, context factory signature, constructors, and all affected tests/stubs.
2. Revert the 13 `ToolExecutionContext` run ownership changes together.
3. Remove `RunIdentityFactory`, `DefaultRunIdentityFactory`, and `AcceptedChatCommand`.
4. Restore `ChatContextFactory.build(ChatRequest, boolean)` and its previous UUID generation.
5. Restore `WebhookRouterService` and `TaskExecutionService` to their prior service calls only as part of item 1; never roll either ingress back independently.

Do not partially roll back only the controller, webhook router, scheduled task path, consumer, service, or `ChatContextFactory`; that would recreate multiple identity owners, introduce a second generated ID, or leave accepted commands without a consumer.

## Acceptance invariants

- Generated IDs match `[0-9a-f]{32}`.
- Supplied IDs are accepted only when already normalized to 32 lowercase hexadecimal characters.
- Sync REST creates one ID in `ChatController.send`.
- SSE REST creates one ID in `ChatController.stream`, including the initial trace emitted before context assembly.
- Async REST creates one ID in `ChatController.sendAsync`; accepted response, queued result, Rabbit message, polling key, completion/failure result, and STOMP topic use that ID.
- `ChatMessageConsumer` creates no ID. First delivery and redelivery both reuse `AsyncChatRequestMessage.requestId`.
- Redelivery may execute the legacy behavior again. Phase 2A does not claim duplicate execution suppression, durable lifecycle, or exactly-once processing.
- `WebhookRouterService` keeps the normalized request ID it already creates, validates it through `RunIdentityFactory.accept`, and calls `chat(AcceptedChatCommand)` without a second ID.
- `TaskExecutionService` keeps the ID created before `ScheduledTaskExecutionService.start` and passes that same value to `executeTaskMessage(ChatRequest, boolean, String)`.
- Legacy `ChatService.chat(ChatRequest)` and `stream(ChatRequest)` signatures remain available and each creates once before delegating.
- Legacy `ChatServiceImpl.executeTaskMessage(ChatRequest, boolean)` remains available and creates once before delegating; scheduled production execution uses the accepted-ID overload.
- `ChatContextFactory` contains no UUID generation and receives an accepted ID.
- `ChatContext.requestId`, trace calls, model-call request context, `AgentRun`, `ToolExecutionContext.requestId`, `ToolExecutionContext.runId`, and tool proposal rows use the same ID.
- Existing action confirmation uses `ChatContext.requestId`; existing tool proposal resume uses stored `requestId` and `runId`.
- `ChatRequest`, `ChatResponse`, `AsyncChatAcceptedResponse`, `AsyncChatRequestMessage`, `AsyncChatResultPayload`, and `TaskChatExecutionResult` shapes remain unchanged.
- Engine selection, `supports()` methods, priorities, answers, persistence, proposal authorization, workspace guards, and `ToolRuntimeAspect` behavior remain unchanged.
- All 67 contract, 47 characterization, 27 focused baseline, new identity tests, and the complete Maven suite pass.

## Explicitly out of scope

- Canonical lifecycle bridge or lifecycle events.
- `RunCoordinator`, `RunStateRepository`, `RunEventStore`, or any database/Redis run persistence.
- `RunState` production adoption.
- `LegacyRuntimeStrategy` or compatibility result adapters.
- Feature flags or runtime activation switches.
- Trace authority or terminal-status ownership changes.
- Routing freeze, `legacyRank`, engine priority, or `supports()` changes.
- Duplicate execution suppression, idempotent execution, or exactly-once Rabbit semantics.
- SSE lifecycle projector, cancellation redesign, completion migration, or stream-status authority.
- Context retrieval migration, decision migration, or answer ownership migration.
- Public DTO changes.

## Plan-document self-review commands

Before handing this plan to an implementation worker, run:

```bash
rg -n \
  'T[B]D|T[O]DO|F[I]XME|p[l]aceholder|fill[ ]in|implement[ ]later|待[定]|以后处[理]' \
  docs/superpowers/plans/2026-06-21-unified-runtime-canonical-identity.md
git diff --check
git status --short
```

Expected: the red-flag scan prints no matches, `git diff --check` exits `0`, and only `docs/superpowers/plans/2026-06-21-unified-runtime-canonical-identity.md` is modified before the documentation commit.
