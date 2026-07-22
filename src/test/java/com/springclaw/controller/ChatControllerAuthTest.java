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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
