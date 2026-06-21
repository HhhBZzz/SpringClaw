# Unified Runtime Canonical Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Phase 2A only: create or accept one normalized 32-character lowercase hexadecimal identity at the external ingress boundary and propagate that same value as `requestId == runId` through sync REST, SSE REST, async REST/Rabbit delivery, trace, tool execution, and proposal ownership.

**Architecture:** `ChatController` is the explicit owner for all three REST acceptance points and creates exactly one ID before calling `ChatService` or publishing an async message. The existing `ChatService.chat(ChatRequest)` and `stream(ChatRequest)` APIs remain available for non-controller callers; each creates one ID through `RunIdentityFactory` and delegates to an internal `AcceptedChatCommand` overload. `ChatContextFactory` receives the already-accepted ID and never generates one. Rabbit redelivery reconstructs the same command from `AsyncChatRequestMessage.requestId`; Phase 2A deliberately does not suppress duplicate execution or add durable lifecycle state.

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
| Legacy `ChatService.chat(ChatRequest)` | `ChatServiceImpl` compatibility method | Create once, delegate |
| Legacy `ChatService.stream(ChatRequest)` | `ChatServiceImpl` compatibility method | Create once, delegate |
| Scheduled task helper | `ChatServiceImpl.executeTaskMessage` | Create once without changing `TaskChatExecutionResult` |
| Confirmation/resume | Existing proposal services | Reuse proposal `requestId`/`runId`; do not create |

Create:

```text
src/main/java/com/springclaw/runtime/identity/RunIdentityFactory.java
src/main/java/com/springclaw/runtime/identity/DefaultRunIdentityFactory.java
src/main/java/com/springclaw/service/chat/AcceptedChatCommand.java
src/test/java/com/springclaw/runtime/identity/DefaultRunIdentityFactoryTest.java
src/test/java/com/springclaw/architecture/CanonicalTransportIdentityTest.java
src/test/java/com/springclaw/architecture/CanonicalToolOwnershipTest.java
```

Modify:

```text
src/main/java/com/springclaw/controller/ChatController.java
src/main/java/com/springclaw/service/chat/ChatService.java
src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java
src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java
src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java
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
src/test/java/com/springclaw/controller/ChatControllerAuthTest.java
src/test/java/com/springclaw/architecture/ContextPropagationCharacterizationTest.java
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
```

Expected: 3 tests, 0 failures, 0 errors, 0 skips.

- [ ] **Step 5: Commit the identity primitive**

```bash
git add \
  src/main/java/com/springclaw/runtime/identity/RunIdentityFactory.java \
  src/main/java/com/springclaw/runtime/identity/DefaultRunIdentityFactory.java \
  src/main/java/com/springclaw/service/chat/AcceptedChatCommand.java \
  src/test/java/com/springclaw/runtime/identity/DefaultRunIdentityFactoryTest.java
git commit -m "feat: add canonical run identity"
```

### Task 2: Make `ChatContextFactory` consume an accepted ID

**Files:**
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java`
- Modify: `src/test/java/com/springclaw/architecture/ContextPropagationCharacterizationTest.java`

- [ ] **Step 1: Change tests first to require an accepted ID**

In `ChatContextFactoryTest`, change the existing build call and add the identity assertion:

```java
String acceptedRunId = "0123456789abcdef0123456789abcdef";
ChatContext context = factory.build(
        new ChatRequest("s1", "u1", "北京呢", "api", "agent"),
        true,
        acceptedRunId
);

ArgumentCaptor<AgentDecisionRequest> decisionRequestCaptor =
        ArgumentCaptor.forClass(AgentDecisionRequest.class);
verify(agentDecisionService).decide(decisionRequestCaptor.capture());
assertThat(context.requestId()).isEqualTo(acceptedRunId);
assertThat(decisionRequestCaptor.getValue().question()).isEqualTo("北京呢");
verify(contextAssembler).assemble("s1", "api", "u1", "北京呢");
assertThat(context.userMessage()).isEqualTo("北京呢");
assertThat(context.effectiveUserMessage()).isEqualTo("北京呢");
assertThat(context.decision()).isEqualTo(decision);
```

In `ContextPropagationCharacterizationTest.chatContextFactoryBuildsCurrentInjectionShape`, replace the production call with:

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

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
mvn -q -Dtest=ChatContextFactoryTest,ContextPropagationCharacterizationTest test
```

Expected: test compilation fails because `build(ChatRequest, boolean, String)` does not exist.

- [ ] **Step 3: Replace UUID ownership with the accepted parameter**

Remove:

```java
import java.util.UUID;
```

Replace the public method signature and the UUID assignment with:

```java
public ChatContext build(ChatRequest request,
                         boolean persistSession,
                         String acceptedRunId) {
    if (!StringUtils.hasText(acceptedRunId)) {
        throw new IllegalArgumentException("acceptedRunId must not be blank");
    }
    String requestId = acceptedRunId;
    String channel = StringUtils.hasText(request.channel()) ? request.channel() : "api";
    AgentSession session = persistSession
            ? agentSessionService.getOrCreate(request.sessionKey(), channel, request.userId())
            : buildEphemeralSession(request.sessionKey(), channel, request.userId());
    String roleCode = authService.resolveRoleByUserId(request.userId());
    var allowedToolPacks = skillService.resolveAllowedToolPacks(channel, request.userId());
    String routingQuestion = resolveRoutingQuestion(
            session.getSessionKey(),
            channel,
            request.userId(),
            requestId,
            request.message(),
            request.responseMode()
    );
    AgentDecision decision = agentDecisionService.decide(new AgentDecisionRequest(
            session.getSessionKey(),
            channel,
            request.userId(),
            roleCode,
            requestId,
            routingQuestion,
            request.responseMode(),
            allowedToolPacks
    ));
    String effectiveDefaultMode = chatRoutingStateService.resolveDefaultMode(configuredAgentMode);
    boolean effectiveAutoUpgrade = chatRoutingStateService.resolveAutoUpgrade(routingAutoUpgradeEnabled);
    ChatRoutingPolicyService.RoutingDecision routingDecision = chatRoutingPolicyService.decide(
            routingQuestion,
            roleCode,
            effectiveDefaultMode,
            effectiveAutoUpgrade,
            allowedToolPacks,
            request.responseMode()
    );
    if (routingDecision == null) {
        routingDecision = new ChatRoutingPolicyService.RoutingDecision(
                request.message(),
                effectiveDefaultMode,
                false,
                false,
                "路由策略未返回结果，回退到当前默认链路。"
        );
    }
    List<SkillDefinition> matchedSkills = skillRegistryService.matchAgentVisibleDefinitions(
            routingDecision.effectiveQuestion(),
            allowedToolPacks,
            2
    );
    String systemPrompt = soulPromptService.buildSystemPrompt(
            channel,
            request.userId(),
            matchedSkills
    );
    AssembledContext assembled = contextAssembler.assemble(
            session.getSessionKey(),
            channel,
            request.userId(),
            routingDecision.effectiveQuestion()
    );
    AiProviderService.ActiveChatClient activeClient = aiProviderService.activeClient();
    ContextInjection injection = new ContextInjection(
            assembled == null ? "" : assembled.observePrompt(),
            "",
            "",
            Map.of(
                    "contextSummary",
                    assembled == null
                            ? AssembledContext.ContextSourceSummary.empty()
                            : assembled.sourceSummary()
            )
    );
    return new ChatContext(
            session,
            channel,
            request.userId(),
            roleCode,
            request.message(),
            routingDecision.effectiveQuestion(),
            requestId,
            systemPrompt,
            assembled,
            activeClient,
            routingDecision.executionMode(),
            routingDecision.reason(),
            routingDecision.responseMode(),
            decision.intent(),
            decision,
            injection
    );
}
```

Do not retain a two-argument `build(ChatRequest, boolean)` overload. That would preserve a second hidden identity owner inside `ChatContextFactory`.

- [ ] **Step 4: Run the tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=ChatContextFactoryTest,ContextPropagationCharacterizationTest test
```

Expected: 6 tests, 0 failures, 0 errors, 0 skips.

- [ ] **Step 5: Commit the context ownership change**

```bash
git add \
  src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java \
  src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java \
  src/test/java/com/springclaw/architecture/ContextPropagationCharacterizationTest.java
git commit -m "refactor: accept canonical chat context identity"
```

### Task 3: Preserve the old `ChatService` API and delegate through one accepted identity

**Files:**
- Modify: `src/main/java/com/springclaw/service/chat/ChatService.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatServiceImplModeTest.java`
- Modify constructor calls in:
  - `src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPersistenceTest.java`
  - `src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPendingApprovalTest.java`
  - `src/test/java/com/springclaw/architecture/FinalAnswerOwnershipCharacterizationTest.java`

- [ ] **Step 1: Write sync and SSE delegation tests**

Add imports:

```java
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.service.chat.AcceptedChatCommand;
```

Add this mock to `ChatServiceImplModeTest.Fixture`:

```java
private final RunIdentityFactory runIdentityFactory = mock(RunIdentityFactory.class);
```

Add to the fixture constructor:

```java
when(runIdentityFactory.create())
        .thenReturn("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
when(runIdentityFactory.accept(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
```

Add these tests:

```java
@Test
void legacyChatCreatesOnceAndDelegatesTheSameIdentityToContextFactory() {
    Fixture fixture = new Fixture();
    fixture.useEngine(fixture.simplifiedOparEngine);
    ChatRequest request = new ChatRequest("s1", "u1", "你好", "api");
    ChatContext context = fixture.buildChatContext("你好", "simplified", "默认");
    when(fixture.chatContextFactory.build(
            request,
            true,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    )).thenReturn(context);
    when(fixture.simplifiedOparEngine.execute(any(), any()))
            .thenReturn(new ChatExecutionResult("observe", "SIMPLIFIED", "ACTION", "answer", true));

    fixture.build().chat(request);

    verify(fixture.runIdentityFactory, times(1)).create();
    verify(fixture.runIdentityFactory, times(1))
            .accept("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    verify(fixture.chatContextFactory).build(
            request,
            true,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    );
}

@Test
void acceptedChatNeverCreatesAnotherIdentity() {
    Fixture fixture = new Fixture();
    fixture.useEngine(fixture.simplifiedOparEngine);
    ChatRequest request = new ChatRequest("s1", "u1", "你好", "api");
    ChatContext context = fixture.buildChatContext("你好", "simplified", "默认");
    when(fixture.chatContextFactory.build(
            request,
            true,
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    )).thenReturn(context);
    when(fixture.simplifiedOparEngine.execute(any(), any()))
            .thenReturn(new ChatExecutionResult("observe", "SIMPLIFIED", "ACTION", "answer", true));

    fixture.build().chat(new AcceptedChatCommand(
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            request
    ));

    verify(fixture.runIdentityFactory, never()).create();
    verify(fixture.runIdentityFactory).accept(
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    );
    verify(fixture.chatContextFactory).build(
            request,
            true,
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    );
}

@Test
void acceptedStreamUsesTheIdentityForInitialTraceAndContextBuild() {
    Fixture fixture = new Fixture();
    fixture.useEngine(fixture.simplifiedOparEngine);
    ChatRequest request = new ChatRequest("s1", "u1", "你好", "api");
    ChatContext context = fixture.buildChatContext("你好", "simplified", "默认");
    when(fixture.chatContextFactory.build(
            request,
            true,
            "cccccccccccccccccccccccccccccccc"
    )).thenReturn(context);
    when(fixture.simplifiedOparEngine.execute(any(), any()))
            .thenReturn(new ChatExecutionResult("observe", "SIMPLIFIED", "ACTION", "answer", true));

    SseEmitter emitter = fixture.build().stream(new AcceptedChatCommand(
            "cccccccccccccccccccccccccccccccc",
            request
    ));

    verify(fixture.runIdentityFactory, never()).create();
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

Change existing stubs from:

```java
when(fixture.chatContextFactory.build(any(ChatRequest.class), anyBoolean()))
```

to:

```java
when(fixture.chatContextFactory.build(
        any(ChatRequest.class),
        anyBoolean(),
        anyString()
))
```

- [ ] **Step 2: Run the focused service test and verify RED**

Run:

```bash
mvn -q -Dtest=ChatServiceImplModeTest test
```

Expected: test compilation fails because the accepted-command overloads, factory constructor dependency, and three-argument context build call do not exist.

- [ ] **Step 3: Add accepted-command overloads without removing old signatures**

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

In `ChatServiceImpl`, add imports:

```java
import com.springclaw.runtime.identity.DefaultRunIdentityFactory;
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.service.chat.AcceptedChatCommand;
```

Add the field:

```java
private final RunIdentityFactory runIdentityFactory;
```

Add `RunIdentityFactory runIdentityFactory` immediately before the two `@Value` parameters in the `@Autowired` constructor and assign it:

```java
this.runIdentityFactory = runIdentityFactory;
```

Keep the package-visible eleven-argument test constructor and change its delegation tail to:

```java
metaGuardExecutor, null, null, null, null, null, null, null,
new DefaultRunIdentityFactory(), false, true);
```

Replace the sync methods with:

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
```

Replace the stream entry methods with:

```java
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

Change the stream worker signature to:

```java
private void executeStream(ChatRequest request,
                           String acceptedRunId,
                           String lockToken,
                           AtomicBoolean lockReleased,
                           SseEmitter emitter,
                           AtomicReference<Disposable> disposableRef)
```

Change its first trace and context build to:

```java
sseEventBridge.sendTrace(
        emitter,
        acceptedRunId,
        "接收请求",
        "request",
        "started",
        "已收到用户输入，准备组装上下文。",
        0L
);
sseEventBridge.sendStatus(emitter, "正在组织上下文");
ChatContext context = chatContextFactory.build(request, true, acceptedRunId);
```

Change task execution and the internal signature to:

```java
public TaskChatExecutionResult executeTaskMessage(ChatRequest request, boolean persistResult) {
    return executeInternal(
            request,
            false,
            persistResult,
            runIdentityFactory.create()
    );
}

private TaskChatExecutionResult executeInternal(ChatRequest request,
                                                boolean enforceRateLimit,
                                                boolean persistResult,
                                                String acceptedRunId) {
    if (enforceRateLimit) {
        chatGuardService.checkRateLimit(request.sessionKey());
    }
    String lockToken = chatGuardService.acquireSessionLock(request.sessionKey());
    try {
        ChatContext context = chatContextFactory.build(
                request,
                persistResult,
                acceptedRunId
        );
        ChatExecutionResult executionResult = runAgentExecution(context);
        String finalAnswer = resolveFinalAnswer(context, executionResult);
        if (persistResult) {
            chatResultPersister.persist(context, finalAnswer, executionResult);
        }
        return new TaskChatExecutionResult(
                context.session().getSessionKey(),
                finalAnswer,
                context.requestId(),
                context.executionMode(),
                context.routingReason()
        );
    } finally {
        chatGuardService.releaseSessionLock(request.sessionKey(), lockToken);
    }
}
```

Do not change the `TaskChatExecutionResult` record.

- [ ] **Step 4: Update exact constructor call sites**

For every direct call to the full `ChatServiceImpl` constructor in the four named test classes, insert:

```java
runIdentityFactory
```

immediately before the final two boolean arguments. Where the test has no fixture field, use:

```java
new DefaultRunIdentityFactory()
```

In `ChatServiceImplModeTest.Fixture.build()` and `buildWithRuntime()`, pass:

```java
runIdentityFactory,
false,
true
```

as the final three arguments.

- [ ] **Step 5: Run service and characterization tests and verify GREEN**

Run:

```bash
mvn -q \
  -Dtest=ChatServiceImplModeTest,ChatServiceImplPersistenceTest,ChatServiceImplPendingApprovalTest,FinalAnswerOwnershipCharacterizationTest \
  test
```

Expected: all selected tests pass; old service methods create once, accepted overloads never create, and the accepted ID reaches `ChatContextFactory`.

- [ ] **Step 6: Commit the service delegation**

```bash
git add \
  src/main/java/com/springclaw/service/chat/ChatService.java \
  src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java \
  src/test/java/com/springclaw/service/chat/impl/ChatServiceImplModeTest.java \
  src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPersistenceTest.java \
  src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPendingApprovalTest.java \
  src/test/java/com/springclaw/architecture/FinalAnswerOwnershipCharacterizationTest.java
git commit -m "refactor: delegate chat through accepted identity"
```

### Task 4: Make all REST endpoints explicit identity owners without changing DTO shapes

**Files:**
- Modify: `src/main/java/com/springclaw/controller/ChatController.java`
- Modify: `src/test/java/com/springclaw/controller/ChatControllerAuthTest.java`
- Create: `src/test/java/com/springclaw/architecture/CanonicalTransportIdentityTest.java`

- [ ] **Step 1: Write controller identity and DTO-shape tests**

Add these imports to `ChatControllerAuthTest`:

```java
import com.springclaw.dto.chat.AsyncChatAcceptedResponse;
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.service.chat.AcceptedChatCommand;
import com.springclaw.service.chat.async.AsyncChatRequestMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
```

In `ChatControllerAuthTest`, add a `RunIdentityFactory` mock and construct the controller with the package-visible constructor that accepts it. Add:

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
    verify(identityFactory, times(2)).create();
}

@Test
void asyncAcceptanceUsesOneIdentityForMessageQueueAndResponse() {
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
    verify(identityFactory, times(1)).create();
}
```

Create `CanonicalTransportIdentityTest.java`:

```java
package com.springclaw.architecture;

import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.service.chat.async.AsyncChatRequestMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalTransportIdentityTest {

    @Test
    @DisplayName("Public chat and async transport DTO record shapes remain unchanged")
    void publicChatDtoShapesRemainUnchanged() {
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

    private static List<String> recordShape(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName()
                        + ":"
                        + component.getType().getSimpleName())
                .toList();
    }
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
mvn -q -Dtest=ChatControllerAuthTest,CanonicalTransportIdentityTest test
```

Expected: controller tests fail to compile because the identity-aware constructor and accepted-command calls do not exist. The DTO shape test passes.

- [ ] **Step 3: Inject the factory and remove direct UUID generation**

In `ChatController`, remove:

```java
import java.util.UUID;
```

Add imports:

```java
import com.springclaw.runtime.identity.DefaultRunIdentityFactory;
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.service.chat.AcceptedChatCommand;
```

Add the field:

```java
private final RunIdentityFactory runIdentityFactory;
```

Add `RunIdentityFactory runIdentityFactory` as the final parameter of the `@Autowired` constructor and assign it.

Replace the package-visible five-argument constructor with:

```java
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

Replace the three acceptance methods with:

```java
@PostMapping("/send")
public ApiResponse<ChatResponse> send(@Valid @RequestBody ChatRequest request) {
    ChatRequest normalizedRequest = normalizeRequest(request);
    String acceptedRunId = runIdentityFactory.create();
    return ApiResponse.success(chatService.chat(
            new AcceptedChatCommand(acceptedRunId, normalizedRequest)
    ));
}

@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
    ChatRequest normalizedRequest = normalizeRequest(request);
    String acceptedRunId = runIdentityFactory.create();
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
}
```

Update `shouldCreateChatControllerBeanWhenSpringResolvesConstructors` to register:

```java
context.registerBean(
        RunIdentityFactory.class,
        () -> mock(RunIdentityFactory.class)
);
```

Update existing controller verifications from `ChatRequest` capture to `AcceptedChatCommand` capture and assert against `command.request()`.

Specifically, replace:

```java
when(chatService.chat(any()))
        .thenReturn(new ChatResponse("s1", "ok", "m1", 1L));
ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
verify(chatService).chat(captor.capture());
Assertions.assertEquals("user_local", captor.getValue().userId());
Assertions.assertEquals("agent", captor.getValue().responseMode());
```

with:

```java
when(chatService.chat(any(AcceptedChatCommand.class)))
        .thenReturn(new ChatResponse("s1", "ok", "m1", 1L));
ArgumentCaptor<AcceptedChatCommand> captor =
        ArgumentCaptor.forClass(AcceptedChatCommand.class);
verify(chatService).chat(captor.capture());
Assertions.assertEquals("user_local", captor.getValue().request().userId());
Assertions.assertEquals("agent", captor.getValue().request().responseMode());
```

- [ ] **Step 4: Run controller and transport tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=ChatControllerAuthTest,CanonicalTransportIdentityTest test
```

Expected: all selected tests pass; each REST acceptance creates exactly one ID, and record component lists are unchanged.

- [ ] **Step 5: Commit REST ingress ownership**

```bash
git add \
  src/main/java/com/springclaw/controller/ChatController.java \
  src/test/java/com/springclaw/controller/ChatControllerAuthTest.java \
  src/test/java/com/springclaw/architecture/CanonicalTransportIdentityTest.java
git commit -m "feat: own canonical identity at chat ingress"
```

### Task 5: Reuse the async message ID on first delivery and redelivery

**Files:**
- Modify: `src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java`
- Modify: `src/test/java/com/springclaw/architecture/TransportParityCharacterizationTest.java`
- Modify: `src/test/java/com/springclaw/architecture/CanonicalTransportIdentityTest.java`

- [ ] **Step 1: Change the consumer characterization test first**

Add to `TransportParityCharacterizationTest`:

```java
import com.springclaw.service.chat.AcceptedChatCommand;
```

In `asyncConsumerProjectsCompletedResultToPollingRabbitAndStomp`, replace `expectedRequest` with:

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

Stub and verify:

```java
when(chatService.chat(expectedCommand)).thenReturn(response);
verify(chatService).chat(expectedCommand);
```

Add these imports to `CanonicalTransportIdentityTest`:

```java
import com.springclaw.service.chat.AcceptedChatCommand;
import com.springclaw.service.chat.ChatService;
import com.springclaw.service.chat.async.AsyncChatResultPayload;
import com.springclaw.service.chat.async.AsyncChatResultStore;
import com.springclaw.service.chat.async.ChatMessageConsumer;
import com.springclaw.service.chat.async.ChatMessageProducer;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
```

Add this explicit redelivery test inside `CanonicalTransportIdentityTest`:

```java
@Test
@DisplayName("Rabbit redelivery reuses the message identity and does not promise execution suppression")
void asyncConsumerRedeliveryReusesIdentityButExecutesAgain() {
    ChatService chatService = mock(ChatService.class);
    AsyncChatResultStore resultStore = mock(AsyncChatResultStore.class);
    ChatMessageProducer producer = mock(ChatMessageProducer.class);
    SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    ChatMessageConsumer consumer = new ChatMessageConsumer(
            chatService,
            resultStore,
            producer,
            messagingTemplate
    );
    AsyncChatRequestMessage message = message(
            "44444444444444444444444444444444"
    );
    ChatResponse response = new ChatResponse(
            message.sessionKey(),
            "answer",
            "model",
            123L
    );
    AsyncChatResultPayload payload = new AsyncChatResultPayload(
            message.requestId(),
            "COMPLETED",
            message.sessionKey(),
            message.channel(),
            response.answer(),
            response.model(),
            message.createdAt(),
            456L,
            ""
    );
    when(chatService.chat(any(AcceptedChatCommand.class))).thenReturn(response);
    when(resultStore.markCompleted(
            message,
            response.answer(),
            response.model()
    )).thenReturn(payload);

    consumer.consume(message);
    consumer.consume(message);

    ArgumentCaptor<AcceptedChatCommand> commands =
            ArgumentCaptor.forClass(AcceptedChatCommand.class);
    verify(chatService, times(2)).chat(commands.capture());
    assertThat(commands.getAllValues())
            .extracting(AcceptedChatCommand::runId)
            .containsExactly(
                    message.requestId(),
                    message.requestId()
            );
}

private static AsyncChatRequestMessage message(String requestId) {
    return new AsyncChatRequestMessage(
            requestId,
            "session",
            "user",
            "hello",
            "api",
            100L,
            "agent"
    );
}
```

Change the failure-path stub to throw from `chat(AcceptedChatCommand)`.

- [ ] **Step 2: Run the transport test and verify RED**

Run:

```bash
mvn -q -Dtest=TransportParityCharacterizationTest,CanonicalTransportIdentityTest test
```

Expected: the completed consumer test fails because production still calls `chat(ChatRequest)`.

- [ ] **Step 3: Pass the existing message ID to the accepted service overload**

Replace the consumer call with:

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

Add:

```java
import com.springclaw.service.chat.AcceptedChatCommand;
```

Do not add a `RunIdentityFactory` dependency to `ChatMessageConsumer`. Do not inspect the result store to skip work. A redelivered Rabbit message is allowed to execute again in Phase 2A; the invariant is identity reuse, not duplicate suppression.

- [ ] **Step 4: Run the transport test and verify GREEN**

Run:

```bash
mvn -q -Dtest=TransportParityCharacterizationTest,CanonicalTransportIdentityTest test
```

Expected: all transport characterization tests pass; both deliveries carry the same message ID and invoke chat twice.

- [ ] **Step 5: Commit async identity reuse**

```bash
git add \
  src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java \
  src/test/java/com/springclaw/architecture/TransportParityCharacterizationTest.java \
  src/test/java/com/springclaw/architecture/CanonicalTransportIdentityTest.java
git commit -m "fix: reuse async chat request identity"
```

### Task 6: Put the canonical ID into tool execution and proposal ownership

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
```

Expected: all selected tests pass; proposal rows and resumed execution preserve the same canonical request/run identity. No assertion changes proposal authorization rules.

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

### Task 7: Run Phase 2A acceptance and verify rollback boundaries

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
  -Dtest=DefaultRunIdentityFactoryTest,ChatContextFactoryTest,ChatServiceImplModeTest,ChatControllerAuthTest,CanonicalTransportIdentityTest,CanonicalToolOwnershipTest,ToolInvocationProposalServiceConfirmTest,ToolProposalExecutionServiceTest \
  test
```

Expected: all selected tests pass.

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

1. Revert the controller/service/consumer accepted-identity changes together.
2. Revert the 13 `ToolExecutionContext` run ownership changes together.
3. Remove `RunIdentityFactory`, `DefaultRunIdentityFactory`, and `AcceptedChatCommand`.
4. Restore `ChatContextFactory.build(ChatRequest, boolean)` and its previous UUID generation.

Do not partially roll back only the controller or only `ChatContextFactory`; that would recreate multiple identity owners or leave accepted commands without a consumer.

## Acceptance invariants

- Generated IDs match `[0-9a-f]{32}`.
- Supplied IDs are accepted only when already normalized to 32 lowercase hexadecimal characters.
- Sync REST creates one ID in `ChatController.send`.
- SSE REST creates one ID in `ChatController.stream`, including the initial trace emitted before context assembly.
- Async REST creates one ID in `ChatController.sendAsync`; accepted response, queued result, Rabbit message, polling key, completion/failure result, and STOMP topic use that ID.
- `ChatMessageConsumer` creates no ID. First delivery and redelivery both reuse `AsyncChatRequestMessage.requestId`.
- Redelivery may execute the legacy behavior again. Phase 2A does not claim duplicate execution suppression, durable lifecycle, or exactly-once processing.
- Legacy `ChatService.chat(ChatRequest)` and `stream(ChatRequest)` signatures remain available and each creates once before delegating.
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

Expected: the red-flag scan prints no matches, `git diff --check` exits `0`, and only the superseded legacy plan plus this canonical identity plan are modified before the documentation commit.
