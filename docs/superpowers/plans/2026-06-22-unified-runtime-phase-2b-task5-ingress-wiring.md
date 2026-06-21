# Unified Runtime Phase 2B Task 5 Ingress Wiring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create one canonical `CREATED` run at every real acceptance boundary and require Rabbit delivery to claim the already-created process-local run before legacy execution.

**Architecture:** HTTP sync/SSE/async, webhook, and scheduled-task ingress call the frozen `LegacyRuntimeBridge.accepted(RunAcceptance)` before invoking legacy work. Rabbit delivery does not reconstruct acceptance because its frozen DTO lacks `roleCodeAtAcceptance` and `deadlineAt`; it requires the controller-created run from `RunStateRepository` and verifies every acceptance field available on the message. Phase 2B remains process-local: a restart loses both the run and the ability to consume the queued message until a later durable lifecycle phase.

**Tech Stack:** Java 17 records, Spring Boot constructor injection, JUnit 5, AssertJ, Mockito, Maven Surefire.

---

## Q1–Q7 decisions

| Question | Decision |
|---|---|
| Q1 Rabbit role is absent | Choose A with validation: the consumer never calls `accept`. It calls `RunStateRepository.requireByRunId` and compares message identity, session, channel, user, message, response mode, and `createdAt` to the existing run. Missing or conflicting state fails delivery before legacy execution. |
| Q2 async `acceptedAt` | `AsyncChatRequestMessage.createdAt` is the sole timestamp. The controller constructs the message first, then uses `Instant.ofEpochMilli(message.createdAt())` for `RunAcceptance`. |
| Q3 `deadlineAt` | Fixed `Duration.ofMinutes(30)` from `acceptedAt`. No configuration key is added in Phase 2B. This matches the current 30-minute SSE boundary and avoids configuration/DTO scope expansion. |
| Q4 webhook role | Resolve with `AuthService.resolveRoleByUserId(inboundMessage.userId())`. Webhooks do not depend on HTTP thread-local authentication and do not use the event role `"SYSTEM"` as the user authorization role. |
| Q5 scheduled values | `originalMessage = renderTaskPrompt(task)`; `responseMode = "agent"` for agent targets and `"skill"` for skill targets; role is `AuthService.resolveRoleByUserId(task.getOwnerUserId())`; blank channel normalizes to `"api"`. Agent `ChatRequest` explicitly carries response mode `"agent"`. |
| Q6 acceptance failure | Fail closed. No ingress continues into legacy execution after lifecycle acceptance/claim failure. HTTP propagates; async submission does not queue; webhook wraps using its existing `BusinessException(50041)` path; scheduled task records task failure when possible; Rabbit throws before its broad legacy-execution catch so the listener can retry/dead-letter. |
| Q7 missing consumer test | Create `src/test/java/com/springclaw/service/chat/async/ChatMessageConsumerTest.java`. It proves missing/mismatched canonical state blocks chat and a matching run permits execution. Existing `CanonicalTransportIdentityTest` continues to prove redelivery reuses the ID and executes again. |

## Scope

Modify production:

```text
src/main/java/com/springclaw/controller/ChatController.java
src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java
src/main/java/com/springclaw/service/webhook/WebhookRouterService.java
src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java
```

Modify tests:

```text
src/test/java/com/springclaw/controller/ChatControllerAuthTest.java
src/test/java/com/springclaw/architecture/CanonicalTransportIdentityTest.java
src/test/java/com/springclaw/architecture/TransportParityCharacterizationTest.java
src/test/java/com/springclaw/service/webhook/WebhookRouterServiceTest.java
src/test/java/com/springclaw/service/task/TaskExecutionServiceTest.java
```

Create:

```text
src/test/java/com/springclaw/service/chat/async/ChatMessageConsumerTest.java
```

Do not modify:

```text
src/main/java/com/springclaw/runtime/lifecycle/**
src/main/java/com/springclaw/runtime/bridge/**
src/main/java/com/springclaw/runtime/contract/**
src/main/java/com/springclaw/dto/chat/**
src/main/java/com/springclaw/service/chat/async/AsyncChatRequestMessage.java
src/main/java/com/springclaw/service/chat/async/AsyncChatResultPayload.java
src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java
src/main/java/com/springclaw/tool/runtime/**
src/main/java/com/springclaw/service/workspace/**
src/main/resources/**
.env.example
```

No feature flag, new configuration property, DTO field, database table, durable
claim, restart recovery, duplicate-execution suppression, or exactly-once claim
belongs in Task 5.

### Task 5.1: Wire sync, SSE, and async HTTP acceptance

**Files:**
- Modify: `src/main/java/com/springclaw/controller/ChatController.java`
- Modify: `src/test/java/com/springclaw/controller/ChatControllerAuthTest.java`

- [ ] **Step 1: Add failing controller acceptance tests**

Add imports:

```java
import com.springclaw.runtime.bridge.LegacyRuntimeBridge;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.service.auth.AuthService;

import java.time.Duration;
```

In `shouldCreateChatControllerBeanWhenSpringResolvesConstructors`, register both
new constructor dependencies before `context.register(ChatController.class)`:

```java
context.registerBean(AuthService.class, () -> mock(AuthService.class));
context.registerBean(LegacyRuntimeBridge.class, () -> mock(LegacyRuntimeBridge.class));
```

Replace `syncAndStreamEachCreateOneAcceptedIdentityAtTheController` with:

```java
@Test
void syncAndStreamCreateCanonicalRunsBeforeLegacyExecution() {
    ChatService chatService = mock(ChatService.class);
    RunIdentityFactory identityFactory = mock(RunIdentityFactory.class);
    AuthService authService = mock(AuthService.class);
    LegacyRuntimeBridge runtimeBridge = mock(LegacyRuntimeBridge.class);
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
            identityFactory,
            authService,
            runtimeBridge
    );
    RequestUserContextHolder.set(new RequestUserContext(
            "user_local",
            "ADMIN",
            System.currentTimeMillis() + 60_000
    ));

    controller.send(new ChatRequest("s1", null, "你好", "api", "agent"));
    controller.stream(new ChatRequest("s1", null, "继续", "api", "agent"));

    ArgumentCaptor<RunAcceptance> acceptances =
            ArgumentCaptor.forClass(RunAcceptance.class);
    verify(runtimeBridge, org.mockito.Mockito.times(2))
            .accepted(acceptances.capture());
    assertThat(acceptances.getAllValues())
            .extracting(RunAcceptance::runId)
            .containsExactly(
                    "11111111111111111111111111111111",
                    "22222222222222222222222222222222"
            );
    assertThat(acceptances.getAllValues())
            .allSatisfy(acceptance -> {
                assertThat(acceptance.roleCodeAtAcceptance()).isEqualTo("ADMIN");
                assertThat(acceptance.channel()).isEqualTo("api");
                assertThat(acceptance.responseMode()).isEqualTo("agent");
                assertThat(Duration.between(
                        acceptance.acceptedAt(),
                        acceptance.deadlineAt()
                )).isEqualTo(Duration.ofMinutes(30));
            });

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

Replace `asyncAcceptanceUsesOneIdentityForQueueMessageAndResponse` with:

```java
@Test
void asyncAcceptanceUsesMessageCreatedAtForCanonicalRunAndQueueProjection() {
    ChatService chatService = mock(ChatService.class);
    ChatMessageProducer producer = mock(ChatMessageProducer.class);
    AsyncChatResultStore resultStore = mock(AsyncChatResultStore.class);
    RunIdentityFactory identityFactory = mock(RunIdentityFactory.class);
    AuthService authService = mock(AuthService.class);
    LegacyRuntimeBridge runtimeBridge = mock(LegacyRuntimeBridge.class);
    when(identityFactory.create())
            .thenReturn("33333333333333333333333333333333");
    ChatController controller = new ChatController(
            chatService,
            producer,
            resultStore,
            mock(MessageEventService.class),
            mock(AiProviderService.class),
            identityFactory,
            authService,
            runtimeBridge
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
    ArgumentCaptor<RunAcceptance> acceptance =
            ArgumentCaptor.forClass(RunAcceptance.class);
    verify(runtimeBridge).accepted(acceptance.capture());
    verify(resultStore).markQueued(message.capture());
    verify(producer).sendRequest(message.getValue());

    assertThat(response.getData().requestId())
            .isEqualTo("33333333333333333333333333333333");
    assertThat(message.getValue().requestId())
            .isEqualTo(response.getData().requestId());
    assertThat(acceptance.getValue().runId()).isEqualTo(message.getValue().requestId());
    assertThat(acceptance.getValue().acceptedAt().toEpochMilli())
            .isEqualTo(message.getValue().createdAt());
    assertThat(acceptance.getValue().deadlineAt())
            .isEqualTo(acceptance.getValue().acceptedAt().plus(Duration.ofMinutes(30)));
}
```

Add this fail-closed test:

```java
@Test
void lifecycleAcceptanceFailureStopsAsyncQueueing() {
    ChatMessageProducer producer = mock(ChatMessageProducer.class);
    AsyncChatResultStore resultStore = mock(AsyncChatResultStore.class);
    RunIdentityFactory identityFactory = mock(RunIdentityFactory.class);
    LegacyRuntimeBridge runtimeBridge = mock(LegacyRuntimeBridge.class);
    when(identityFactory.create()).thenReturn("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    when(runtimeBridge.accepted(any(RunAcceptance.class)))
            .thenThrow(new IllegalStateException("lifecycle unavailable"));
    ChatController controller = new ChatController(
            mock(ChatService.class),
            producer,
            resultStore,
            mock(MessageEventService.class),
            mock(AiProviderService.class),
            identityFactory,
            mock(AuthService.class),
            runtimeBridge
    );
    RequestUserContextHolder.set(new RequestUserContext(
            "user_local",
            "USER",
            System.currentTimeMillis() + 60_000
    ));

    Assertions.assertThrows(
            IllegalStateException.class,
            () -> controller.sendAsync(
                    new ChatRequest("s1", null, "异步处理", "api", "agent")
            )
    );

    verify(resultStore, org.mockito.Mockito.never())
            .markQueued(any(AsyncChatRequestMessage.class));
    verify(producer, org.mockito.Mockito.never())
            .sendRequest(any(AsyncChatRequestMessage.class));
}
```

- [ ] **Step 2: Update all remaining controller construction sites**

Replace the two package-private constructors in production with the one constructor
shown in Step 4. Then update these four tests:

`shouldUseAuthenticatedUsernameAsEffectiveUserId`:

```java
ChatController controller = new ChatController(
        chatService,
        producer,
        resultStore,
        mock(MessageEventService.class),
        mock(AiProviderService.class),
        new DefaultRunIdentityFactory(),
        mock(AuthService.class),
        mock(LegacyRuntimeBridge.class)
);
```

Add:

```java
import com.springclaw.runtime.identity.DefaultRunIdentityFactory;
```

`shouldRejectMismatchedUserIdFromRequestBody`:

```java
ChatController controller = new ChatController(
        mock(ChatService.class),
        mock(ChatMessageProducer.class),
        mock(AsyncChatResultStore.class),
        mock(MessageEventService.class),
        mock(AiProviderService.class),
        new DefaultRunIdentityFactory(),
        mock(AuthService.class),
        mock(LegacyRuntimeBridge.class)
);
```

`shouldReturnChatHistoryForCurrentUserOnly`:

```java
ChatController controller = new ChatController(
        mock(ChatService.class),
        mock(ChatMessageProducer.class),
        mock(AsyncChatResultStore.class),
        messageEventService,
        mock(AiProviderService.class),
        new DefaultRunIdentityFactory(),
        mock(AuthService.class),
        mock(LegacyRuntimeBridge.class)
);
```

`shouldRejectChatHistoryWhenSessionBelongsToAnotherUser`:

```java
ChatController controller = new ChatController(
        mock(ChatService.class),
        mock(ChatMessageProducer.class),
        mock(AsyncChatResultStore.class),
        messageEventService,
        mock(AiProviderService.class),
        new DefaultRunIdentityFactory(),
        mock(AuthService.class),
        mock(LegacyRuntimeBridge.class)
);
```

- [ ] **Step 3: Run controller tests and verify RED**

```bash
mvn -q -Dtest=ChatControllerAuthTest test
```

Expected: compilation fails because the new constructor and lifecycle calls do not
exist.

- [ ] **Step 4: Implement exact controller wiring**

Add imports:

```java
import com.springclaw.runtime.bridge.LegacyRuntimeBridge;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.service.auth.AuthService;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
```

Add fields/constants:

```java
private static final Duration RUN_DEADLINE = Duration.ofMinutes(30);

private final AuthService authService;
private final LegacyRuntimeBridge runtimeBridge;
```

Replace the `@Autowired` constructor with:

```java
@Autowired
public ChatController(ChatService chatService,
                      ChatMessageProducer chatMessageProducer,
                      AsyncChatResultStore asyncChatResultStore,
                      MessageEventService messageEventService,
                      AiProviderService aiProviderService,
                      AgentActionProposalService actionProposalService,
                      AgentRunTraceService agentRunTraceService,
                      RunIdentityFactory runIdentityFactory,
                      AuthService authService,
                      LegacyRuntimeBridge runtimeBridge) {
    this.chatService = chatService;
    this.chatMessageProducer = chatMessageProducer;
    this.asyncChatResultStore = asyncChatResultStore;
    this.messageEventService = messageEventService;
    this.aiProviderService = aiProviderService;
    this.actionProposalService = actionProposalService;
    this.agentRunTraceService = agentRunTraceService;
    this.runIdentityFactory = runIdentityFactory;
    this.authService = authService;
    this.runtimeBridge = runtimeBridge;
}
```

Delete the existing five-argument and six-argument package-private constructors.
Add this single package-private constructor:

```java
ChatController(ChatService chatService,
               ChatMessageProducer chatMessageProducer,
               AsyncChatResultStore asyncChatResultStore,
               MessageEventService messageEventService,
               AiProviderService aiProviderService,
               RunIdentityFactory runIdentityFactory,
               AuthService authService,
               LegacyRuntimeBridge runtimeBridge) {
    this(
            chatService,
            chatMessageProducer,
            asyncChatResultStore,
            messageEventService,
            aiProviderService,
            null,
            null,
            runIdentityFactory,
            authService,
            runtimeBridge
    );
}
```

Replace the three ingress methods with:

```java
@PostMapping("/send")
public ApiResponse<ChatResponse> send(@Valid @RequestBody ChatRequest request) {
    ChatRequest normalizedRequest = normalizeRequest(request);
    String acceptedRunId = runIdentityFactory.create();
    Instant acceptedAt = Instant.now();
    acceptRun(acceptedRunId, normalizedRequest, acceptedAt);
    return ApiResponse.success(chatService.chat(
            new AcceptedChatCommand(acceptedRunId, normalizedRequest)
    ));
}

@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
    ChatRequest normalizedRequest = normalizeRequest(request);
    String acceptedRunId = runIdentityFactory.create();
    Instant acceptedAt = Instant.now();
    acceptRun(acceptedRunId, normalizedRequest, acceptedAt);
    return chatService.stream(
            new AcceptedChatCommand(acceptedRunId, normalizedRequest)
    );
}

@PostMapping("/async")
public ApiResponse<AsyncChatAcceptedResponse> sendAsync(
        @Valid @RequestBody ChatRequest request
) {
    ChatRequest normalizedRequest = normalizeRequest(request);
    String requestId = runIdentityFactory.create();
    String channel = normalizedChannel(normalizedRequest.channel());
    AsyncChatRequestMessage message = new AsyncChatRequestMessage(
            requestId,
            normalizedRequest.sessionKey(),
            normalizedRequest.userId(),
            normalizedRequest.message(),
            channel,
            System.currentTimeMillis(),
            normalizedRequest.responseMode()
    );
    Instant acceptedAt = Instant.ofEpochMilli(message.createdAt());
    acceptRun(requestId, normalizedRequest, acceptedAt);
    asyncChatResultStore.markQueued(message);
    chatMessageProducer.sendRequest(message);
    return ApiResponse.success(new AsyncChatAcceptedResponse(
            requestId,
            "QUEUED",
            channel,
            System.currentTimeMillis()
    ));
}
```

Add helpers before `requireUserContext()`:

```java
private void acceptRun(
        String runId,
        ChatRequest request,
        Instant acceptedAt
) {
    runtimeBridge.accepted(new RunAcceptance(
            runId,
            request.sessionKey(),
            normalizedChannel(request.channel()),
            request.userId(),
            resolveRoleCode(request.userId()),
            request.message(),
            normalizedResponseMode(request.responseMode()),
            acceptedAt,
            acceptedAt.plus(RUN_DEADLINE)
    ));
}

private String resolveRoleCode(String userId) {
    RequestUserContext context = RequestUserContextHolder.get();
    String roleCode = context != null && StringUtils.hasText(context.roleCode())
            ? context.roleCode()
            : authService.resolveRoleByUserId(userId);
    if (!StringUtils.hasText(roleCode)) {
        throw new IllegalStateException("roleCode unavailable for accepted run");
    }
    return roleCode.trim().toUpperCase(Locale.ROOT);
}

private String normalizedChannel(String channel) {
    return StringUtils.hasText(channel) ? channel.trim() : "api";
}

private String normalizedResponseMode(String responseMode) {
    return StringUtils.hasText(responseMode) ? responseMode.trim() : "agent";
}
```

- [ ] **Step 5: Verify controller GREEN**

```bash
mvn -q -Dtest=ChatControllerAuthTest,CanonicalTransportIdentityTest test
```

Expected: all tests pass; lifecycle failure test proves no queue projection or Rabbit
send occurs.

- [ ] **Step 6: Commit controller wiring**

```bash
git add \
  src/main/java/com/springclaw/controller/ChatController.java \
  src/test/java/com/springclaw/controller/ChatControllerAuthTest.java
git commit -m "feat: create canonical runs at chat ingress"
```

### Task 5.2: Require Rabbit delivery to claim an existing run

**Files:**
- Modify: `src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java`
- Create: `src/test/java/com/springclaw/service/chat/async/ChatMessageConsumerTest.java`
- Modify: `src/test/java/com/springclaw/architecture/CanonicalTransportIdentityTest.java`
- Modify: `src/test/java/com/springclaw/architecture/TransportParityCharacterizationTest.java`

- [ ] **Step 1: Create the failing consumer test**

Create `ChatMessageConsumerTest.java`:

```java
package com.springclaw.service.chat.async;

import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.lifecycle.RunStateRepository;
import com.springclaw.service.chat.AcceptedChatCommand;
import com.springclaw.service.chat.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMessageConsumerTest {

    @Test
    void missingCanonicalRunStopsDeliveryBeforeLegacyExecution() {
        ChatService chatService = mock(ChatService.class);
        AsyncChatResultStore resultStore = mock(AsyncChatResultStore.class);
        RunStateRepository repository = mock(RunStateRepository.class);
        AsyncChatRequestMessage message = message();
        when(repository.requireByRunId(message.requestId()))
                .thenThrow(new IllegalStateException("run not found"));
        ChatMessageConsumer consumer = new ChatMessageConsumer(
                chatService,
                resultStore,
                mock(ChatMessageProducer.class),
                mock(SimpMessagingTemplate.class),
                repository
        );

        assertThatThrownBy(() -> consumer.consume(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("run not found");

        verify(chatService, never()).chat(any(AcceptedChatCommand.class));
        verify(resultStore, never()).markFailed(
                any(AsyncChatRequestMessage.class),
                org.mockito.ArgumentMatchers.anyString()
        );
    }

    @Test
    void matchingCanonicalRunAllowsLegacyExecution() {
        ChatService chatService = mock(ChatService.class);
        AsyncChatResultStore resultStore = mock(AsyncChatResultStore.class);
        ChatMessageProducer producer = mock(ChatMessageProducer.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        RunStateRepository repository = mock(RunStateRepository.class);
        AsyncChatRequestMessage message = message();
        when(repository.requireByRunId(message.requestId()))
                .thenReturn(createdRun(message));
        when(chatService.chat(any(AcceptedChatCommand.class)))
                .thenReturn(new ChatResponse("session", "answer", "model", 123L));
        AsyncChatResultPayload payload = new AsyncChatResultPayload(
                message.requestId(), "COMPLETED", message.sessionKey(),
                message.channel(), "answer", "model", message.createdAt(),
                456L, ""
        );
        when(resultStore.markCompleted(message, "answer", "model"))
                .thenReturn(payload);
        ChatMessageConsumer consumer = new ChatMessageConsumer(
                chatService,
                resultStore,
                producer,
                messaging,
                repository
        );

        consumer.consume(message);

        verify(chatService).chat(any(AcceptedChatCommand.class));
        verify(producer).sendResponse(payload);
        verify(messaging).convertAndSend("/topic/chat/" + message.requestId(), payload);
    }

    @Test
    void conflictingMessageStopsDeliveryBeforeLegacyExecution() {
        ChatService chatService = mock(ChatService.class);
        RunStateRepository repository = mock(RunStateRepository.class);
        AsyncChatRequestMessage message = message();
        RunState conflicting = createdRun(new AsyncChatRequestMessage(
                message.requestId(),
                "other-session",
                message.userId(),
                message.message(),
                message.channel(),
                message.createdAt(),
                message.responseMode()
        ));
        when(repository.requireByRunId(message.requestId())).thenReturn(conflicting);
        ChatMessageConsumer consumer = new ChatMessageConsumer(
                chatService,
                mock(AsyncChatResultStore.class),
                mock(ChatMessageProducer.class),
                mock(SimpMessagingTemplate.class),
                repository
        );

        assertThatThrownBy(() -> consumer.consume(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match canonical run");

        verify(chatService, never()).chat(any(AcceptedChatCommand.class));
    }

    private static AsyncChatRequestMessage message() {
        return new AsyncChatRequestMessage(
                "44444444444444444444444444444444",
                "session",
                "user",
                "hello",
                "api",
                100L,
                "agent"
        );
    }

    private static RunState createdRun(AsyncChatRequestMessage message) {
        Instant acceptedAt = Instant.ofEpochMilli(message.createdAt());
        return new RunState(
                message.requestId(),
                message.requestId(),
                0,
                RunStatus.CREATED,
                message.sessionKey(),
                message.channel(),
                message.userId(),
                "USER",
                message.message(),
                message.responseMode(),
                acceptedAt,
                null,
                acceptedAt,
                null,
                acceptedAt.plus(Duration.ofMinutes(30)),
                null,
                null,
                "",
                1,
                "",
                List.of(),
                null,
                null,
                Map.of(),
                null
        );
    }
}
```

- [ ] **Step 2: Verify consumer RED**

```bash
mvn -q -Dtest=ChatMessageConsumerTest test
```

Expected: compilation fails because the consumer constructor lacks
`RunStateRepository`.

- [ ] **Step 3: Implement exact consumer claim**

Add imports:

```java
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.lifecycle.RunStateRepository;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Objects;
```

Add the field and constructor parameter:

```java
private final RunStateRepository runStateRepository;

public ChatMessageConsumer(ChatService chatService,
                           AsyncChatResultStore asyncChatResultStore,
                           ChatMessageProducer chatMessageProducer,
                           SimpMessagingTemplate messagingTemplate,
                           RunStateRepository runStateRepository) {
    this.chatService = chatService;
    this.asyncChatResultStore = asyncChatResultStore;
    this.chatMessageProducer = chatMessageProducer;
    this.messagingTemplate = messagingTemplate;
    this.runStateRepository = runStateRepository;
}
```

At the first line of `consume`, before `try`, add:

```java
RunState canonicalRun = runStateRepository.requireByRunId(message.requestId());
requireMatchingAcceptance(canonicalRun, message);
```

Add:

```java
private void requireMatchingAcceptance(
        RunState run,
        AsyncChatRequestMessage message
) {
    boolean matches = Objects.equals(run.runId(), message.requestId())
            && Objects.equals(run.requestId(), message.requestId())
            && Objects.equals(run.sessionKey(), message.sessionKey())
            && Objects.equals(run.channel(), normalizedChannel(message.channel()))
            && Objects.equals(run.userId(), message.userId())
            && Objects.equals(run.originalMessage(), message.message())
            && Objects.equals(
                    run.responseMode(),
                    normalizedResponseMode(message.responseMode())
            )
            && Objects.equals(
                    run.acceptedAt(),
                    Instant.ofEpochMilli(message.createdAt())
            );
    if (!matches) {
        throw new IllegalStateException(
                "async message does not match canonical run: " + message.requestId()
        );
    }
}

private String normalizedChannel(String channel) {
    return StringUtils.hasText(channel) ? channel.trim() : "api";
}

private String normalizedResponseMode(String responseMode) {
    return StringUtils.hasText(responseMode) ? responseMode.trim() : "agent";
}
```

Do not move these checks inside the existing `try`. Missing/conflicting canonical
state must escape the listener instead of being converted into an async business
`FAILED` payload.

- [ ] **Step 4: Update all three existing consumer construction sites**

In `CanonicalTransportIdentityTest.rabbitRedeliveryReusesIdentityButExecutesAgain`,
add imports for `RunState`, `RunStatus`, `RunStateRepository`, `Duration`, `Instant`,
and `Map`. Create and stub the repository before the consumer:

```java
RunStateRepository repository = mock(RunStateRepository.class);
when(repository.requireByRunId(message.requestId()))
        .thenReturn(createdRun(message));
```

Move consumer construction after message construction and replace it with:

```java
ChatMessageConsumer consumer = new ChatMessageConsumer(
        chatService,
        resultStore,
        mock(ChatMessageProducer.class),
        mock(SimpMessagingTemplate.class),
        repository
);
```

Add this helper to `CanonicalTransportIdentityTest`:

```java
private static RunState createdRun(AsyncChatRequestMessage message) {
    Instant acceptedAt = Instant.ofEpochMilli(message.createdAt());
    return new RunState(
            message.requestId(), message.requestId(), 0, RunStatus.CREATED,
            message.sessionKey(), message.channel(), message.userId(), "USER",
            message.message(),
            message.responseMode() == null ? "agent" : message.responseMode(),
            acceptedAt, null, acceptedAt, null,
            acceptedAt.plus(Duration.ofMinutes(30)),
            null, null, "", 1, "", List.of(), null, null, Map.of(), null
    );
}
```

In both consumer tests in `TransportParityCharacterizationTest`, add:

```java
RunStateRepository repository = mock(RunStateRepository.class);
when(repository.requireByRunId(message.requestId()))
        .thenReturn(createdRun(message));
```

Construct each consumer after its message is created:

```java
ChatMessageConsumer consumer = new ChatMessageConsumer(
        chatService,
        resultStore,
        producer,
        messagingTemplate,
        repository
);
```

Add imports for `RunState`, `RunStatus`, `RunStateRepository`, `Duration`,
`Instant`, `List`, and `Map`, then add the same exact `createdRun` helper shown
above before the existing `message(String requestId)` helper.

- [ ] **Step 5: Verify consumer GREEN**

```bash
mvn -q -Dtest=ChatMessageConsumerTest,CanonicalTransportIdentityTest,TransportParityCharacterizationTest test
```

- [ ] **Step 6: Commit Rabbit claim**

```bash
git add \
  src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java \
  src/test/java/com/springclaw/service/chat/async/ChatMessageConsumerTest.java \
  src/test/java/com/springclaw/architecture/CanonicalTransportIdentityTest.java \
  src/test/java/com/springclaw/architecture/TransportParityCharacterizationTest.java
git commit -m "feat: require canonical run for async delivery"
```

### Task 5.3: Wire webhook acceptance with resolved user role

**Files:**
- Modify: `src/main/java/com/springclaw/service/webhook/WebhookRouterService.java`
- Modify: `src/test/java/com/springclaw/service/webhook/WebhookRouterServiceTest.java`

- [ ] **Step 1: Extend the webhook test and verify RED**

Add imports:

```java
import com.springclaw.runtime.bridge.LegacyRuntimeBridge;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.service.auth.AuthService;

import java.time.Duration;
```

Create mocks:

```java
AuthService authService = mock(AuthService.class);
LegacyRuntimeBridge runtimeBridge = mock(LegacyRuntimeBridge.class);
when(authService.resolveRoleByUserId("user-A")).thenReturn("USER");
```

Replace construction with:

```java
WebhookRouterService service = new WebhookRouterService(
        adapterFactory,
        chatService,
        eventService,
        dispatcher,
        identityFactory,
        authService,
        runtimeBridge
);
```

After dispatch, capture and assert:

```java
ArgumentCaptor<RunAcceptance> acceptance =
        ArgumentCaptor.forClass(RunAcceptance.class);
verify(runtimeBridge).accepted(acceptance.capture());
assertThat(acceptance.getValue().runId()).isEqualTo(acceptedId.getValue());
assertThat(acceptance.getValue().sessionKey()).isEqualTo("session-A");
assertThat(acceptance.getValue().channel()).isEqualTo("feishu");
assertThat(acceptance.getValue().userId()).isEqualTo("user-A");
assertThat(acceptance.getValue().roleCodeAtAcceptance()).isEqualTo("USER");
assertThat(acceptance.getValue().originalMessage()).isEqualTo("hello");
assertThat(acceptance.getValue().responseMode()).isEqualTo("agent");
assertThat(Duration.between(
        acceptance.getValue().acceptedAt(),
        acceptance.getValue().deadlineAt()
)).isEqualTo(Duration.ofMinutes(30));
```

Add a second test:

```java
@Test
void lifecycleAcceptanceFailureStopsWebhookBeforeChat() {
    ChannelAdapterFactory adapterFactory = mock(ChannelAdapterFactory.class);
    ChatService chatService = mock(ChatService.class);
    RunIdentityFactory identityFactory = mock(RunIdentityFactory.class);
    AuthService authService = mock(AuthService.class);
    LegacyRuntimeBridge runtimeBridge = mock(LegacyRuntimeBridge.class);
    ChannelAdapter adapter = mock(ChannelAdapter.class);
    when(adapterFactory.getRequired("feishu")).thenReturn(adapter);
    when(adapter.adapt(Map.of("event", "message"))).thenReturn(
            new UnifiedInboundMessage("feishu", "session-A", "user-A", "hello")
    );
    when(identityFactory.accept(anyString()))
            .thenAnswer(invocation -> invocation.getArgument(0));
    when(authService.resolveRoleByUserId("user-A")).thenReturn("USER");
    when(runtimeBridge.accepted(org.mockito.ArgumentMatchers.any(
            RunAcceptance.class
    ))).thenThrow(new IllegalStateException("lifecycle unavailable"));
    WebhookRouterService service = new WebhookRouterService(
            adapterFactory,
            chatService,
            mock(MessageEventService.class),
            mock(ChannelOutboundDispatcher.class),
            identityFactory,
            authService,
            runtimeBridge
    );

    org.junit.jupiter.api.Assertions.assertThrows(
            com.springclaw.common.exception.BusinessException.class,
            () -> service.dispatch("feishu", Map.of("event", "message"))
    );

    verify(chatService, org.mockito.Mockito.never())
            .chat(org.mockito.ArgumentMatchers.any(AcceptedChatCommand.class));
}
```

Run:

```bash
mvn -q -Dtest=WebhookRouterServiceTest test
```

Expected: compilation fails on the new constructor.

- [ ] **Step 2: Implement exact webhook wiring**

Add imports:

```java
import com.springclaw.runtime.bridge.LegacyRuntimeBridge;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.service.auth.AuthService;

import java.time.Duration;
import java.time.Instant;
```

Add:

```java
private static final Duration RUN_DEADLINE = Duration.ofMinutes(30);

private final AuthService authService;
private final LegacyRuntimeBridge runtimeBridge;
```

Replace the constructor with:

```java
public WebhookRouterService(ChannelAdapterFactory channelAdapterFactory,
                            ChatService chatService,
                            MessageEventService messageEventService,
                            ChannelOutboundDispatcher channelOutboundDispatcher,
                            RunIdentityFactory runIdentityFactory,
                            AuthService authService,
                            LegacyRuntimeBridge runtimeBridge) {
    this.channelAdapterFactory = channelAdapterFactory;
    this.chatService = chatService;
    this.messageEventService = messageEventService;
    this.channelOutboundDispatcher = channelOutboundDispatcher;
    this.runIdentityFactory = runIdentityFactory;
    this.authService = authService;
    this.runtimeBridge = runtimeBridge;
}
```

Inside `dispatch`, delete request-ID creation before the `try`. Replace the first
part of the `try` with:

```java
String requestId = "";
UnifiedInboundMessage inboundMessage = null;
ChatResponse response = null;
try {
    ChannelAdapter adapter = channelAdapterFactory.getRequired(channel);
    inboundMessage = adapter.adapt(payload);
    requestId = runIdentityFactory.accept(
            UUID.randomUUID().toString().replace("-", "")
    );
    Instant acceptedAt = Instant.now();
    String roleCode = authService.resolveRoleByUserId(inboundMessage.userId());
    runtimeBridge.accepted(new RunAcceptance(
            requestId,
            inboundMessage.sessionKey(),
            inboundMessage.channel(),
            inboundMessage.userId(),
            roleCode,
            inboundMessage.text(),
            "agent",
            acceptedAt,
            acceptedAt.plus(RUN_DEADLINE)
    ));
    response = chatService.chat(new AcceptedChatCommand(
            requestId,
            new ChatRequest(
                    inboundMessage.sessionKey(),
                    inboundMessage.userId(),
                    inboundMessage.text(),
                    inboundMessage.channel(),
                    "agent"
            )
    ));
} catch (Exception ex) {
    log.warn("Webhook 处理失败，channel={}, requestId={}", channel, requestId, ex);
    throw new BusinessException(50041, "Webhook 处理失败");
}
```

This keeps self-message filtering before run creation and keeps invalid adapter
payloads outside the canonical run inventory.

- [ ] **Step 3: Verify webhook GREEN**

```bash
mvn -q -Dtest=WebhookRouterServiceTest,CanonicalTransportIdentityTest test
```

- [ ] **Step 4: Commit webhook wiring**

```bash
git add \
  src/main/java/com/springclaw/service/webhook/WebhookRouterService.java \
  src/test/java/com/springclaw/service/webhook/WebhookRouterServiceTest.java
git commit -m "feat: create canonical runs at webhook ingress"
```

### Task 5.4: Wire scheduled skill and agent acceptance

**Files:**
- Modify: `src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java`
- Modify: `src/test/java/com/springclaw/service/task/TaskExecutionServiceTest.java`

- [ ] **Step 1: Update all three test constructors and add acceptance assertions**

Add imports:

```java
import com.springclaw.runtime.bridge.LegacyRuntimeBridge;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.service.auth.AuthService;

import java.time.Duration;
```

In each of the three tests, create:

```java
AuthService authService = mock(AuthService.class);
LegacyRuntimeBridge runtimeBridge = mock(LegacyRuntimeBridge.class);
when(authService.resolveRoleByUserId("tester")).thenReturn("USER");
```

Replace the constructor in `shouldExecuteScriptSkillTask` with:

```java
TaskExecutionService service = new TaskExecutionService(
        scheduledTaskService,
        executionService,
        scheduleSupport,
        skillRuntimeService,
        skillService,
        chatService,
        agentSessionService,
        memoryService,
        messageEventService,
        soulPromptService,
        dispatcher,
        authService,
        runtimeBridge,
        new ObjectMapper(),
        true
);
```

Replace the constructor in `shouldExecutePythonSkillTask` with:

```java
TaskExecutionService service = new TaskExecutionService(
        scheduledTaskService,
        executionService,
        scheduleSupport,
        skillRuntimeService,
        skillService,
        chatService,
        agentSessionService,
        memoryService,
        messageEventService,
        soulPromptService,
        dispatcher,
        authService,
        runtimeBridge,
        new ObjectMapper(),
        true
);
```

Replace the constructor in `shouldExecuteAgentTaskThroughChatService` with:

```java
TaskExecutionService service = new TaskExecutionService(
        scheduledTaskService,
        executionService,
        new TaskScheduleSupport(),
        skillRuntimeService,
        skillService,
        chatService,
        agentSessionService,
        memoryService,
        messageEventService,
        soulPromptService,
        dispatcher,
        authService,
        runtimeBridge,
        new ObjectMapper(),
        true
);
```

In `shouldExecuteScriptSkillTask`, after `runTask`, add:

```java
ArgumentCaptor<RunAcceptance> acceptance =
        ArgumentCaptor.forClass(RunAcceptance.class);
verify(runtimeBridge).accepted(acceptance.capture());
assertThat(acceptance.getValue().sessionKey()).isEqualTo("task:shadow:task_1");
assertThat(acceptance.getValue().channel()).isEqualTo("api");
assertThat(acceptance.getValue().userId()).isEqualTo("tester");
assertThat(acceptance.getValue().roleCodeAtAcceptance()).isEqualTo("USER");
assertThat(acceptance.getValue().originalMessage())
        .isEqualTo("读取这个网页 https://example.com");
assertThat(acceptance.getValue().responseMode()).isEqualTo("skill");
assertThat(Duration.between(
        acceptance.getValue().acceptedAt(),
        acceptance.getValue().deadlineAt()
)).isEqualTo(Duration.ofMinutes(30));
```

In `shouldExecuteAgentTaskThroughChatService`, capture acceptance and the
`ChatRequest`:

```java
ArgumentCaptor<RunAcceptance> acceptance =
        ArgumentCaptor.forClass(RunAcceptance.class);
ArgumentCaptor<ChatRequest> chatRequest =
        ArgumentCaptor.forClass(ChatRequest.class);
verify(runtimeBridge).accepted(acceptance.capture());
verify(chatService).executeTaskMessage(
        chatRequest.capture(),
        eq(false),
        eq(startedRunId.getValue())
);
assertThat(acceptance.getValue().runId()).isEqualTo(startedRunId.getValue());
assertThat(acceptance.getValue().originalMessage())
        .isEqualTo("总结今天的项目进展");
assertThat(acceptance.getValue().responseMode()).isEqualTo("agent");
assertThat(chatRequest.getValue().message())
        .isEqualTo(acceptance.getValue().originalMessage());
assertThat(chatRequest.getValue().responseMode()).isEqualTo("agent");
```

Remove the earlier uncaptured `verify(chatService).executeTaskMessage(...)` from
that test so the invocation is asserted exactly once.

Add this fail-closed test:

```java
@Test
void lifecycleAcceptanceFailureStopsScheduledExecutionBeforeExecutionRow() {
    ScheduledTaskService scheduledTaskService = mock(ScheduledTaskService.class);
    ScheduledTaskExecutionService executionService =
            mock(ScheduledTaskExecutionService.class);
    SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
    ChatServiceImpl chatService = mock(ChatServiceImpl.class);
    AuthService authService = mock(AuthService.class);
    LegacyRuntimeBridge runtimeBridge = mock(LegacyRuntimeBridge.class);
    when(authService.resolveRoleByUserId("tester")).thenReturn("USER");
    when(runtimeBridge.accepted(any(RunAcceptance.class)))
            .thenThrow(new IllegalStateException("lifecycle unavailable"));
    TaskExecutionService service = new TaskExecutionService(
            scheduledTaskService,
            executionService,
            new TaskScheduleSupport(),
            skillRuntimeService,
            mock(SkillService.class),
            chatService,
            mock(AgentSessionService.class),
            mock(MemoryService.class),
            mock(MessageEventService.class),
            mock(SoulPromptService.class),
            mock(ChannelOutboundDispatcher.class),
            authService,
            runtimeBridge,
            new ObjectMapper(),
            true
    );
    ScheduledTask task = new ScheduledTask();
    task.setTaskId("task_failure");
    task.setOwnerUserId("tester");
    task.setName("失败任务");
    task.setChannel("api");
    task.setTargetType("agent");
    task.setInputPayload("执行失败验证");
    task.setPersistToSession(0);

    org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> service.runTask(task, "MANUAL")
    );

    verify(executionService, never()).start(anyString(), anyString(), anyString());
    verify(chatService, never()).executeTaskMessage(
            any(ChatRequest.class),
            anyBoolean(),
            anyString()
    );
    verify(skillRuntimeService, never())
            .executeBySkillId(anyString(), anyString(), any());
    verify(scheduledTaskService).markFinished(
            eq(task),
            eq("FAILED"),
            any(LocalDateTime.class)
    );
}
```

- [ ] **Step 2: Run scheduled tests and verify RED**

```bash
mvn -q -Dtest=TaskExecutionServiceTest test
```

Expected: compilation fails because constructor and acceptance wiring do not exist.

- [ ] **Step 3: Implement exact scheduled-task wiring**

Add imports:

```java
import com.springclaw.runtime.bridge.LegacyRuntimeBridge;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.service.auth.AuthService;

import java.time.Duration;
import java.time.Instant;
```

Add:

```java
private static final Duration RUN_DEADLINE = Duration.ofMinutes(30);

private final AuthService authService;
private final LegacyRuntimeBridge runtimeBridge;
```

Add constructor parameters between `ChannelOutboundDispatcher` and `ObjectMapper`:

```java
AuthService authService,
LegacyRuntimeBridge runtimeBridge,
```

Assign:

```java
this.authService = authService;
this.runtimeBridge = runtimeBridge;
```

Replace `runTask` with:

```java
public TaskExecutionOutcome runTask(ScheduledTask task, String triggerSource) {
    LocalDateTime startedAt = LocalDateTime.now();
    String requestId = UUID.randomUUID().toString().replace("-", "");
    String sessionKey = resolveTaskSessionKey(task);
    String channel = TextUtils.safe(task.getChannel(), "api");
    ScheduledTaskExecution execution = null;
    try {
        if (!"CRON".equalsIgnoreCase(TextUtils.safe(triggerSource))) {
            scheduledTaskService.markRunning(task, startedAt, task.getNextRunAt());
        }
        String originalMessage = renderTaskPrompt(task);
        String responseMode = "agent".equalsIgnoreCase(task.getTargetType())
                ? "agent"
                : "skill";
        Instant acceptedAt = Instant.now();
        runtimeBridge.accepted(new RunAcceptance(
                requestId,
                sessionKey,
                channel,
                task.getOwnerUserId(),
                authService.resolveRoleByUserId(task.getOwnerUserId()),
                originalMessage,
                responseMode,
                acceptedAt,
                acceptedAt.plus(RUN_DEADLINE)
        ));
        execution = scheduledTaskExecutionService.start(
                task.getTaskId(),
                triggerSource,
                requestId
        );
        TaskExecutionOutcome outcome = switch (TextUtils.normalize(
                task.getTargetType()
        )) {
            case "skill" -> executeSkillTask(task, requestId);
            case "agent" -> executeAgentTask(task, requestId, originalMessage);
            default -> throw new BusinessException(
                    40079,
                    "不支持的任务目标类型: " + task.getTargetType()
            );
        };
        if (shouldPersistToSession(task)
                && !"agent".equalsIgnoreCase(task.getTargetType())) {
            persistTaskTurn(
                    task,
                    outcome.requestId(),
                    outcome.sessionKey(),
                    originalMessage,
                    outcome.resultPayload()
            );
        }
        deliverIfNeeded(task, outcome.resultPayload());
        scheduledTaskExecutionService.complete(
                execution.getExecutionId(),
                "SUCCESS",
                TextUtils.truncate(outcome.summary(), 300),
                TextUtils.truncate(outcome.resultPayload(), 5000),
                "",
                outcome.requestId(),
                outcome.sessionKey(),
                LocalDateTime.now()
        );
        scheduledTaskService.markFinished(task, "SUCCESS", LocalDateTime.now());
        return outcome;
    } catch (Exception ex) {
        String error = ex.getMessage() == null
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
        if (execution != null) {
            scheduledTaskExecutionService.complete(
                    execution.getExecutionId(),
                    "FAILED",
                    TextUtils.truncate(error, 300),
                    "",
                    TextUtils.truncate(error, 1000),
                    requestId,
                    sessionKey,
                    LocalDateTime.now()
            );
        }
        scheduledTaskService.markFinished(task, "FAILED", LocalDateTime.now());
        throw ex;
    }
}
```

Replace `executeAgentTask` with:

```java
private TaskExecutionOutcome executeAgentTask(
        ScheduledTask task,
        String runId,
        String prompt
) {
    ChatServiceImpl.TaskChatExecutionResult result =
            chatService.executeTaskMessage(
                    new ChatRequest(
                            resolveTaskSessionKey(task),
                            task.getOwnerUserId(),
                            prompt,
                            TextUtils.safe(task.getChannel(), "api"),
                            "agent"
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

- [ ] **Step 4: Verify scheduled GREEN**

```bash
mvn -q -Dtest=TaskExecutionServiceTest,CanonicalTransportIdentityTest test
```

- [ ] **Step 5: Commit scheduled wiring**

```bash
git add \
  src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java \
  src/test/java/com/springclaw/service/task/TaskExecutionServiceTest.java
git commit -m "feat: create canonical runs for scheduled tasks"
```

### Task 5.5: Task 5 acceptance and scope verification

**Files:**
- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`

- [ ] **Step 1: Run Task 5 focused tests**

```bash
mvn -q \
  -Dtest=ChatControllerAuthTest,ChatMessageConsumerTest,WebhookRouterServiceTest,TaskExecutionServiceTest,CanonicalTransportIdentityTest,TransportParityCharacterizationTest \
  test
```

Expected: all tests pass. `ChatMessageConsumerTest` is intentionally new and is
not a typo.

- [ ] **Step 2: Run contract, characterization, and baseline gates**

```bash
mvn -q \
  -Dtest=RunStatusTest,ContextAndDecisionContractTest,ToolCompletionAndResultContractTest,RunEventContractTest,RunStateContractTest,RuntimeStrategyContractTest,LegacyRuntimeContractFixturesTest \
  test

mvn -q \
  -Dtest=RuntimeRouteCharacterizationTest,ContextPropagationCharacterizationTest,ToolSafetyPathCharacterizationTest,FinalAnswerOwnershipCharacterizationTest,TransportParityCharacterizationTest,DefaultRunIdentityFactoryTest,CanonicalTransportIdentityTest,CanonicalToolOwnershipTest \
  test

mvn -q \
  -Dtest=EngineSelectorTest,ChatContextFactoryTest,PromptInjectionTest,AgentRuntimeEngineTest,ToolRuntimeAspectInterceptionIT,ToolInvocationProposalServiceConfirmTest \
  test
```

Expected:

```text
67 contract tests pass
53 characterization/Phase 2A tests pass
27 focused baseline tests pass
```

The known MySQL authentication warning may appear in application-context tests but
must not make these focused commands fail.

- [ ] **Step 3: Verify prohibited files and whitespace**

```bash
git diff --exit-code b7bb77f..HEAD -- \
  src/main/java/com/springclaw/runtime/lifecycle \
  src/main/java/com/springclaw/runtime/bridge \
  src/main/java/com/springclaw/runtime/contract \
  src/main/java/com/springclaw/dto/chat \
  src/main/java/com/springclaw/service/chat/async/AsyncChatRequestMessage.java \
  src/main/java/com/springclaw/service/chat/async/AsyncChatResultPayload.java \
  src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java \
  src/main/java/com/springclaw/tool/runtime \
  src/main/java/com/springclaw/service/workspace \
  src/main/resources \
  .env.example

git diff --check
```

Expected: both commands exit `0`.

- [ ] **Step 4: Record evidence**

Append to the collaboration ledger:

```text
Task: Phase 2B Task 5 ingress wiring
Owner: Claude
Base: b7bb77f
Decisions:
  - Rabbit requires a matching process-local run and never reconstructs role.
  - Async acceptedAt equals message.createdAt.
  - deadlineAt is acceptedAt + 30 minutes.
  - webhook and scheduled roles come from AuthService.
  - acceptance failures stop legacy execution.
  - ChatMessageConsumerTest is a new claim/fail-closed suite.
Limitations:
  - no restart durability
  - no exactly-once execution
  - no duplicate-execution suppression
Next dependency:
  Phase 2B Task 6 legacy observation wiring
```

Immediately above `Decisions`, add the four actual SHAs returned by:

```bash
git log --format='%h %s' --grep='create canonical runs at chat ingress' -1
git log --format='%h %s' --grep='require canonical run for async delivery' -1
git log --format='%h %s' --grep='create canonical runs at webhook ingress' -1
git log --format='%h %s' --grep='create canonical runs for scheduled tasks' -1
```

After `Limitations`, record the exact test counts from Surefire XML and copy any
environment warning emitted by the commands. If no warning is emitted, write
`Environmental warnings: none`.

- [ ] **Step 5: Commit evidence**

```bash
git add docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
git commit -m "docs: record phase 2b ingress evidence"
```

## Exact constructor migration inventory

The implementation must update all 13 existing construction sites:

```text
ChatControllerAuthTest.java
  1 Spring bean registration
  6 direct new ChatController(...) calls

CanonicalTransportIdentityTest.java
  1 new ChatMessageConsumer(...) call

TransportParityCharacterizationTest.java
  2 new ChatMessageConsumer(...) calls

WebhookRouterServiceTest.java
  1 new WebhookRouterService(...) call

TaskExecutionServiceTest.java
  3 new TaskExecutionService(...) calls
```

After edits, verify no old constructor remains:

```bash
rg -n \
  "new ChatController\\(|new ChatMessageConsumer\\(|new WebhookRouterService\\(|new TaskExecutionService\\(" \
  src/test/java
```

Review every result against the signatures defined in this plan before running the
acceptance suite.
