package com.springclaw.controller;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.dto.chat.AsyncChatAcceptedResponse;
import com.springclaw.dto.chat.ChatHistoryResponse;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.runtime.bridge.RunLifecycleBridge;
import com.springclaw.runtime.identity.DefaultRunIdentityFactory;
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.service.agent.AgentActionProposalService;
import com.springclaw.service.agent.AgentRunTraceService;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.chat.AcceptedChatCommand;
import com.springclaw.service.chat.ChatService;
import com.springclaw.service.chat.async.AsyncChatRequestMessage;
import com.springclaw.service.chat.async.AsyncChatResultStore;
import com.springclaw.service.chat.async.ChatMessageProducer;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.web.auth.RequestUserContext;
import com.springclaw.web.auth.RequestUserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerAuthTest {

    @AfterEach
    void clearContext() {
        RequestUserContextHolder.clear();
    }

    @Test
    void shouldCreateChatControllerBeanWhenSpringResolvesConstructors() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ChatService.class, () -> mock(ChatService.class));
            context.registerBean(ChatMessageProducer.class, () -> mock(ChatMessageProducer.class));
            context.registerBean(AsyncChatResultStore.class, () -> mock(AsyncChatResultStore.class));
            context.registerBean(MessageEventService.class, () -> mock(MessageEventService.class));
            context.registerBean(AiProviderService.class, () -> mock(AiProviderService.class));
            context.registerBean(AgentActionProposalService.class, () -> mock(AgentActionProposalService.class));
            context.registerBean(AgentRunTraceService.class, () -> mock(AgentRunTraceService.class));
            context.registerBean(RunIdentityFactory.class, () -> mock(RunIdentityFactory.class));
            context.registerBean(AuthService.class, () -> mock(AuthService.class));
            context.registerBean(RunLifecycleBridge.class, () -> mock(RunLifecycleBridge.class));
            context.registerBean(com.springclaw.runtime.history.ConversationHistoryDeriver.class,
                    () -> mock(com.springclaw.runtime.history.ConversationHistoryDeriver.class));
            context.register(ChatController.class);

            context.refresh();

            Assertions.assertNotNull(context.getBean(ChatController.class));
        }
    }

    @Test
    void shouldUseAuthenticatedUsernameAsEffectiveUserId() {
        ChatService chatService = mock(ChatService.class);
        ChatMessageProducer producer = mock(ChatMessageProducer.class);
        AsyncChatResultStore resultStore = mock(AsyncChatResultStore.class);
        ChatController controller = new ChatController(
                chatService,
                producer,
                resultStore,
                mock(MessageEventService.class),
                mock(AiProviderService.class),
                new DefaultRunIdentityFactory(),
                mock(AuthService.class),
                mock(RunLifecycleBridge.class)
        );
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
        RequestUserContextHolder.set(new RequestUserContext("user_local", "USER", System.currentTimeMillis() + 60_000));

        ApiResponse<ChatResponse> response = controller.send(new ChatRequest("s1", null, "你好", "api", "agent", null));

        Assertions.assertEquals(0, response.getCode());
        ArgumentCaptor<AcceptedChatCommand> captor =
                ArgumentCaptor.forClass(AcceptedChatCommand.class);
        verify(chatService).chat(captor.capture());
        Assertions.assertEquals("user_local", captor.getValue().request().userId());
        Assertions.assertEquals("agent", captor.getValue().request().responseMode());
    }

    @Test
    void syncAndStreamCreateCanonicalRunsBeforeLegacyExecution() {
        ChatService chatService = mock(ChatService.class);
        RunIdentityFactory identityFactory = mock(RunIdentityFactory.class);
        AuthService authService = mock(AuthService.class);
        RunLifecycleBridge runtimeBridge = mock(RunLifecycleBridge.class);
        when(identityFactory.create())
                .thenReturn("11111111111111111111111111111111")
                .thenReturn("22222222222222222222222222222222");
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

        ApiResponse<ChatResponse> syncResponse =
                controller.send(new ChatRequest("s1", null, "你好", "api", "agent", null));
        controller.stream(new ChatRequest("s1", null, "继续", "api", "agent", null));

        assertThat(syncResponse.getData().requestId())
                .isEqualTo("11111111111111111111111111111111");

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
                    assertThat(acceptance.sessionAccessClaim().claimType())
                            .isEqualTo(SessionAccessClaim.ClaimType.PERSONAL);
                    assertThat(acceptance.sessionAccessClaim().acceptanceOrigin())
                            .isEqualTo(SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API);
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

    @Test
    void asyncAcceptanceUsesMessageCreatedAtForCanonicalRunAndQueueProjection() {
        ChatService chatService = mock(ChatService.class);
        ChatMessageProducer producer = mock(ChatMessageProducer.class);
        AsyncChatResultStore resultStore = mock(AsyncChatResultStore.class);
        RunIdentityFactory identityFactory = mock(RunIdentityFactory.class);
        AuthService authService = mock(AuthService.class);
        RunLifecycleBridge runtimeBridge = mock(RunLifecycleBridge.class);
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
                new ChatRequest("s1", null, "异步处理", "api", "agent", null)
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

    @Test
    void authenticatedApiCannotMintSharedClaimFromFeishuGroupStrings() {
        ChatService chatService = mock(ChatService.class);
        RunLifecycleBridge runtimeBridge = mock(RunLifecycleBridge.class);
        when(chatService.chat(any(AcceptedChatCommand.class)))
                .thenReturn(new ChatResponse(
                        "req-feishu-group",
                        "feishu:group:g1",
                        "ok",
                        "m1",
                        1L
                ));
        ChatController controller = new ChatController(
                chatService,
                mock(ChatMessageProducer.class),
                mock(AsyncChatResultStore.class),
                mock(MessageEventService.class),
                mock(AiProviderService.class),
                new DefaultRunIdentityFactory(),
                mock(AuthService.class),
                runtimeBridge
        );
        RequestUserContextHolder.set(new RequestUserContext(
                "alice",
                "USER",
                System.currentTimeMillis() + 60_000
        ));

        controller.send(new ChatRequest(
                "feishu:group:g1",
                null,
                "hello",
                "feishu",
                "agent",
                null
        ));

        ArgumentCaptor<RunAcceptance> acceptance =
                ArgumentCaptor.forClass(RunAcceptance.class);
        verify(runtimeBridge).accepted(acceptance.capture());
        assertThat(acceptance.getValue().sessionAccessClaim().claimType())
                .isEqualTo(SessionAccessClaim.ClaimType.PERSONAL);
        assertThat(acceptance.getValue().sessionAccessClaim().acceptanceOrigin())
                .isEqualTo(SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API);
    }

    @Test
    void apiUsesCanonicalTrimmedSessionKeyForAcceptanceSyncAndRabbit() {
        ChatService chatService = mock(ChatService.class);
        ChatMessageProducer producer = mock(ChatMessageProducer.class);
        AsyncChatResultStore resultStore = mock(AsyncChatResultStore.class);
        RunIdentityFactory identityFactory = mock(RunIdentityFactory.class);
        RunLifecycleBridge runtimeBridge = mock(RunLifecycleBridge.class);
        when(identityFactory.create())
                .thenReturn("44444444444444444444444444444444")
                .thenReturn("55555555555555555555555555555555");
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
        ChatController controller = new ChatController(
                chatService,
                producer,
                resultStore,
                mock(MessageEventService.class),
                mock(AiProviderService.class),
                identityFactory,
                mock(AuthService.class),
                runtimeBridge
        );
        RequestUserContextHolder.set(new RequestUserContext(
                "alice",
                "USER",
                System.currentTimeMillis() + 60_000
        ));

        controller.send(new ChatRequest(" s1 ", null, "sync", "api", "agent", null));
        controller.sendAsync(new ChatRequest(
                " s1 ",
                null,
                "async",
                "api",
                "agent",
                null
        ));

        ArgumentCaptor<RunAcceptance> acceptances =
                ArgumentCaptor.forClass(RunAcceptance.class);
        verify(runtimeBridge, org.mockito.Mockito.times(2))
                .accepted(acceptances.capture());
        assertThat(acceptances.getAllValues())
                .extracting(RunAcceptance::sessionKey)
                .containsExactly("s1", "s1");

        ArgumentCaptor<AcceptedChatCommand> command =
                ArgumentCaptor.forClass(AcceptedChatCommand.class);
        verify(chatService).chat(command.capture());
        assertThat(command.getValue().request().sessionKey()).isEqualTo("s1");

        ArgumentCaptor<AsyncChatRequestMessage> message =
                ArgumentCaptor.forClass(AsyncChatRequestMessage.class);
        verify(producer).sendRequest(message.capture());
        assertThat(message.getValue().sessionKey()).isEqualTo("s1");
    }

    @Test
    void lifecycleAcceptanceFailureStopsAsyncQueueing() {
        ChatMessageProducer producer = mock(ChatMessageProducer.class);
        AsyncChatResultStore resultStore = mock(AsyncChatResultStore.class);
        RunIdentityFactory identityFactory = mock(RunIdentityFactory.class);
        RunLifecycleBridge runtimeBridge = mock(RunLifecycleBridge.class);
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
                        new ChatRequest("s1", null, "异步处理", "api", "agent", null)
                )
        );

        verify(resultStore, org.mockito.Mockito.never())
                .markQueued(any(AsyncChatRequestMessage.class));
        verify(producer, org.mockito.Mockito.never())
                .sendRequest(any(AsyncChatRequestMessage.class));
    }

    @Test
    void shouldRejectMismatchedUserIdFromRequestBody() {
        ChatController controller = new ChatController(
                mock(ChatService.class),
                mock(ChatMessageProducer.class),
                mock(AsyncChatResultStore.class),
                mock(MessageEventService.class),
                mock(AiProviderService.class),
                new DefaultRunIdentityFactory(),
                mock(AuthService.class),
                mock(RunLifecycleBridge.class)
        );
        RequestUserContextHolder.set(new RequestUserContext("user_local", "USER", System.currentTimeMillis() + 60_000));

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> controller.send(new ChatRequest("s1", "other_user", "你好", "api")));

        Assertions.assertEquals(40313, ex.getCode());
    }

    @Test
    void shouldReturnChatHistoryForCurrentUserOnly() {
        MessageEventService messageEventService = mock(MessageEventService.class);
        ChatController controller = new ChatController(
                mock(ChatService.class),
                mock(ChatMessageProducer.class),
                mock(AsyncChatResultStore.class),
                messageEventService,
                mock(AiProviderService.class),
                new DefaultRunIdentityFactory(),
                mock(AuthService.class),
                mock(RunLifecycleBridge.class)
        );
        RequestUserContextHolder.set(new RequestUserContext("user_local", "USER", System.currentTimeMillis() + 60_000));
        when(messageEventService.countSessionEvents(eq("s1"), eq(null), eq(null), eq("CHAT")))
                .thenReturn(3L);
        when(messageEventService.countSessionEvents(eq("s1"), eq("user_local"), eq(null), eq("CHAT")))
                .thenReturn(2L);
        when(messageEventService.listSessionEvents(eq("s1"), eq("user_local"), eq(null), eq("CHAT"), eq(20), eq(true)))
                .thenReturn(List.of(
                        event("USER", "user_local", "你好"),
                        event("ASSISTANT", "user_local", "[REFLECT] 你好，我在。"),
                        event("SYSTEM", "user_local", "PLAN=internal")
                ));

        ApiResponse<ChatHistoryResponse> response = controller.history("s1", 20);

        Assertions.assertEquals(0, response.getCode());
        Assertions.assertEquals("s1", response.getData().sessionKey());
        Assertions.assertEquals(2, response.getData().messages().size());
        Assertions.assertEquals("user", response.getData().messages().get(0).role());
        Assertions.assertEquals("agent", response.getData().messages().get(1).role());
        Assertions.assertEquals("你好，我在。", response.getData().messages().get(1).content());
    }

    @Test
    void shouldRejectChatHistoryWhenSessionBelongsToAnotherUser() {
        MessageEventService messageEventService = mock(MessageEventService.class);
        ChatController controller = new ChatController(
                mock(ChatService.class),
                mock(ChatMessageProducer.class),
                mock(AsyncChatResultStore.class),
                messageEventService,
                mock(AiProviderService.class),
                new DefaultRunIdentityFactory(),
                mock(AuthService.class),
                mock(RunLifecycleBridge.class)
        );
        RequestUserContextHolder.set(new RequestUserContext("user_local", "USER", System.currentTimeMillis() + 60_000));
        when(messageEventService.countSessionEvents(eq("s1"), eq(null), eq(null), eq("CHAT")))
                .thenReturn(2L);
        when(messageEventService.countSessionEvents(eq("s1"), eq("user_local"), eq(null), eq("CHAT")))
                .thenReturn(0L);

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> controller.history("s1", 20));

        Assertions.assertEquals(40315, ex.getCode());
    }

    @Test
    void shouldReturnCanonicalChatHistoryWhenDeriverHasTurns() {
        // T5d(spec 2026-08-18 §3.4): canonical 源直读派生器,越权校验用 turn 归属,
        // 不读 message_event
        MessageEventService messageEventService = mock(MessageEventService.class);
        com.springclaw.runtime.history.ConversationHistoryDeriver deriver =
                mock(com.springclaw.runtime.history.ConversationHistoryDeriver.class);
        ChatController controller = new ChatController(
                mock(ChatService.class),
                mock(ChatMessageProducer.class),
                mock(AsyncChatResultStore.class),
                messageEventService,
                mock(AiProviderService.class),
                mock(AgentActionProposalService.class),
                mock(AgentRunTraceService.class),
                new DefaultRunIdentityFactory(),
                mock(AuthService.class),
                mock(RunLifecycleBridge.class),
                deriver,
                "canonical"
        );
        RequestUserContextHolder.set(new RequestUserContext("user_local", "USER", System.currentTimeMillis() + 60_000));
        when(deriver.deriveFull(eq("s1"), eq(20))).thenReturn(List.of(
                com.springclaw.runtime.history.ConversationTurn.canonicalUntruncated(
                        com.springclaw.runtime.history.ConversationTurn.Role.USER,
                        "你好", "r1", "api", "user_local",
                        java.time.Instant.parse("2026-08-18T00:00:01Z")),
                com.springclaw.runtime.history.ConversationTurn.canonicalUntruncated(
                        com.springclaw.runtime.history.ConversationTurn.Role.ASSISTANT,
                        "你好，我在。", "r1", "api", "user_local",
                        java.time.Instant.parse("2026-08-18T00:00:07Z"))
        ));

        ApiResponse<ChatHistoryResponse> response = controller.history("s1", 20);

        Assertions.assertEquals(0, response.getCode());
        Assertions.assertEquals(2, response.getData().messages().size());
        Assertions.assertEquals("user", response.getData().messages().get(0).role());
        Assertions.assertEquals("agent", response.getData().messages().get(1).role());
        Assertions.assertEquals("你好，我在。", response.getData().messages().get(1).content());
        Assertions.assertEquals("r1:agent", response.getData().messages().get(1).id());
        verify(messageEventService, never()).countSessionEvents(anyString(), any(), any(), anyString());
        verify(messageEventService, never()).listSessionEvents(
                anyString(), any(), any(), anyString(), anyInt(), eq(true));
    }

    @Test
    void shouldRejectCanonicalChatHistoryWhenNoTurnOwnedByCurrentUser() {
        // 归属过滤后为空 → 落 legacy 计数仲裁;legacy 确认会话存在且本人零记录 → 40315
        MessageEventService messageEventService = mock(MessageEventService.class);
        com.springclaw.runtime.history.ConversationHistoryDeriver deriver =
                mock(com.springclaw.runtime.history.ConversationHistoryDeriver.class);
        ChatController controller = new ChatController(
                mock(ChatService.class),
                mock(ChatMessageProducer.class),
                mock(AsyncChatResultStore.class),
                messageEventService,
                mock(AiProviderService.class),
                mock(AgentActionProposalService.class),
                mock(AgentRunTraceService.class),
                new DefaultRunIdentityFactory(),
                mock(AuthService.class),
                mock(RunLifecycleBridge.class),
                deriver,
                "canonical"
        );
        RequestUserContextHolder.set(new RequestUserContext("user_local", "USER", System.currentTimeMillis() + 60_000));
        when(deriver.deriveFull(eq("s1"), eq(20))).thenReturn(List.of(
                com.springclaw.runtime.history.ConversationTurn.canonicalUntruncated(
                        com.springclaw.runtime.history.ConversationTurn.Role.USER,
                        "别人的问题", "r9", "api", "someone_else",
                        java.time.Instant.parse("2026-08-18T00:00:01Z"))
        ));
        when(messageEventService.countSessionEvents(eq("s1"), eq(null), eq(null), eq("CHAT")))
                .thenReturn(2L);
        when(messageEventService.countSessionEvents(eq("s1"), eq("user_local"), eq(null), eq("CHAT")))
                .thenReturn(0L);

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> controller.history("s1", 20));

        Assertions.assertEquals(40315, ex.getCode());
    }

    @Test
    void shouldRenderOnlyOwnedTurnsInSharedCanonicalSession() {
        // 共享 sessionKey(如飞书群): canonical 渲染范围与 legacy 对齐——只回本人 turn,
        // 其他参与者的问答不出现在响应里(默认态零行为变化不变式)
        MessageEventService messageEventService = mock(MessageEventService.class);
        com.springclaw.runtime.history.ConversationHistoryDeriver deriver =
                mock(com.springclaw.runtime.history.ConversationHistoryDeriver.class);
        ChatController controller = new ChatController(
                mock(ChatService.class),
                mock(ChatMessageProducer.class),
                mock(AsyncChatResultStore.class),
                messageEventService,
                mock(AiProviderService.class),
                mock(AgentActionProposalService.class),
                mock(AgentRunTraceService.class),
                new DefaultRunIdentityFactory(),
                mock(AuthService.class),
                mock(RunLifecycleBridge.class),
                deriver,
                "canonical"
        );
        RequestUserContextHolder.set(new RequestUserContext("user_local", "USER", System.currentTimeMillis() + 60_000));
        when(deriver.deriveFull(eq("s1"), eq(20))).thenReturn(List.of(
                com.springclaw.runtime.history.ConversationTurn.canonicalUntruncated(
                        com.springclaw.runtime.history.ConversationTurn.Role.USER,
                        "群友的问题", "r8", "feishu", "groupmate",
                        java.time.Instant.parse("2026-08-18T00:00:01Z")),
                com.springclaw.runtime.history.ConversationTurn.canonicalUntruncated(
                        com.springclaw.runtime.history.ConversationTurn.Role.ASSISTANT,
                        "给群友的回答", "r8", "feishu", "groupmate",
                        java.time.Instant.parse("2026-08-18T00:00:02Z")),
                com.springclaw.runtime.history.ConversationTurn.canonicalUntruncated(
                        com.springclaw.runtime.history.ConversationTurn.Role.USER,
                        "我的问题", "r9", "feishu", "user_local",
                        java.time.Instant.parse("2026-08-18T00:01:01Z")),
                com.springclaw.runtime.history.ConversationTurn.canonicalUntruncated(
                        com.springclaw.runtime.history.ConversationTurn.Role.ASSISTANT,
                        "给我的回答", "r9", "feishu", "user_local",
                        java.time.Instant.parse("2026-08-18T00:01:07Z"))
        ));

        ApiResponse<ChatHistoryResponse> response = controller.history("s1", 20);

        Assertions.assertEquals(0, response.getCode());
        Assertions.assertEquals(2, response.getData().messages().size());
        Assertions.assertEquals("我的问题", response.getData().messages().get(0).content());
        Assertions.assertEquals("给我的回答", response.getData().messages().get(1).content());
    }

    @Test
    void shouldFallbackToLegacyWhenNoOwnedTurnInCanonicalWindow() {
        // 窗口化漏接防护: 请求者的 turn 全在 canonical 窗口外(窗口内只有他人 turn)时,
        // 不直接 403——落 legacy 计数校验+渲染(双写期 legacy 行仍新鲜)
        MessageEventService messageEventService = mock(MessageEventService.class);
        com.springclaw.runtime.history.ConversationHistoryDeriver deriver =
                mock(com.springclaw.runtime.history.ConversationHistoryDeriver.class);
        ChatController controller = new ChatController(
                mock(ChatService.class),
                mock(ChatMessageProducer.class),
                mock(AsyncChatResultStore.class),
                messageEventService,
                mock(AiProviderService.class),
                mock(AgentActionProposalService.class),
                mock(AgentRunTraceService.class),
                new DefaultRunIdentityFactory(),
                mock(AuthService.class),
                mock(RunLifecycleBridge.class),
                deriver,
                "canonical"
        );
        RequestUserContextHolder.set(new RequestUserContext("user_local", "USER", System.currentTimeMillis() + 60_000));
        when(deriver.deriveFull(eq("s1"), eq(20))).thenReturn(List.of(
                com.springclaw.runtime.history.ConversationTurn.canonicalUntruncated(
                        com.springclaw.runtime.history.ConversationTurn.Role.USER,
                        "窗口内别人的问题", "r9", "api", "someone_else",
                        java.time.Instant.parse("2026-08-18T00:00:01Z"))
        ));
        when(messageEventService.countSessionEvents(eq("s1"), eq(null), eq(null), eq("CHAT")))
                .thenReturn(3L);
        when(messageEventService.countSessionEvents(eq("s1"), eq("user_local"), eq(null), eq("CHAT")))
                .thenReturn(1L);
        when(messageEventService.listSessionEvents(eq("s1"), eq("user_local"), eq(null), eq("CHAT"), eq(20), eq(true)))
                .thenReturn(List.of(event("USER", "user_local", "我的旧消息")));

        ApiResponse<ChatHistoryResponse> response = controller.history("s1", 20);

        Assertions.assertEquals(0, response.getCode());
        Assertions.assertEquals(1, response.getData().messages().size());
        Assertions.assertEquals("我的旧消息", response.getData().messages().get(0).content());
    }

    @Test
    void shouldFallbackToLegacyChatHistoryWhenDeriverReturnsEmpty() {
        // 空回退腿(spec §5.3): 纯存量会话 canonical 无记录 → legacy 路径
        MessageEventService messageEventService = mock(MessageEventService.class);
        com.springclaw.runtime.history.ConversationHistoryDeriver deriver =
                mock(com.springclaw.runtime.history.ConversationHistoryDeriver.class);
        ChatController controller = new ChatController(
                mock(ChatService.class),
                mock(ChatMessageProducer.class),
                mock(AsyncChatResultStore.class),
                messageEventService,
                mock(AiProviderService.class),
                mock(AgentActionProposalService.class),
                mock(AgentRunTraceService.class),
                new DefaultRunIdentityFactory(),
                mock(AuthService.class),
                mock(RunLifecycleBridge.class),
                deriver,
                "canonical"
        );
        RequestUserContextHolder.set(new RequestUserContext("user_local", "USER", System.currentTimeMillis() + 60_000));
        when(deriver.deriveFull(anyString(), anyInt())).thenReturn(List.of());
        when(messageEventService.countSessionEvents(eq("s1"), eq(null), eq(null), eq("CHAT")))
                .thenReturn(1L);
        when(messageEventService.countSessionEvents(eq("s1"), eq("user_local"), eq(null), eq("CHAT")))
                .thenReturn(1L);
        when(messageEventService.listSessionEvents(eq("s1"), eq("user_local"), eq(null), eq("CHAT"), eq(20), eq(true)))
                .thenReturn(List.of(event("USER", "user_local", "存量消息")));

        ApiResponse<ChatHistoryResponse> response = controller.history("s1", 20);

        Assertions.assertEquals(0, response.getCode());
        Assertions.assertEquals(1, response.getData().messages().size());
        Assertions.assertEquals("存量消息", response.getData().messages().get(0).content());
    }

    @Test
    void shouldFallbackToLegacyChatHistoryWhenDeriverThrows() {
        MessageEventService messageEventService = mock(MessageEventService.class);
        com.springclaw.runtime.history.ConversationHistoryDeriver deriver =
                mock(com.springclaw.runtime.history.ConversationHistoryDeriver.class);
        ChatController controller = new ChatController(
                mock(ChatService.class),
                mock(ChatMessageProducer.class),
                mock(AsyncChatResultStore.class),
                messageEventService,
                mock(AiProviderService.class),
                mock(AgentActionProposalService.class),
                mock(AgentRunTraceService.class),
                new DefaultRunIdentityFactory(),
                mock(AuthService.class),
                mock(RunLifecycleBridge.class),
                deriver,
                "canonical"
        );
        RequestUserContextHolder.set(new RequestUserContext("user_local", "USER", System.currentTimeMillis() + 60_000));
        when(deriver.deriveFull(anyString(), anyInt())).thenThrow(new IllegalStateException("store down"));
        when(messageEventService.countSessionEvents(eq("s1"), eq(null), eq(null), eq("CHAT")))
                .thenReturn(2L);
        when(messageEventService.countSessionEvents(eq("s1"), eq("user_local"), eq(null), eq("CHAT")))
                .thenReturn(2L);
        when(messageEventService.listSessionEvents(eq("s1"), eq("user_local"), eq(null), eq("CHAT"), eq(20), eq(true)))
                .thenReturn(List.of(event("USER", "user_local", "legacy 问题")));

        ApiResponse<ChatHistoryResponse> response = controller.history("s1", 20);

        Assertions.assertEquals(0, response.getCode());
        Assertions.assertEquals(1, response.getData().messages().size());
        Assertions.assertEquals("legacy 问题", response.getData().messages().get(0).content());
    }

    private MessageEvent event(String role, String userId, String content) {
        MessageEvent event = new MessageEvent();
        event.setSessionKey("s1");
        event.setUserId(userId);
        event.setRole(role);
        event.setEventType("CHAT");
        event.setContent(content);
        return event;
    }
}
