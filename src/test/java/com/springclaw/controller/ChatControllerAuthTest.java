package com.springclaw.controller;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.dto.chat.AsyncChatAcceptedResponse;
import com.springclaw.dto.chat.ChatHistoryResponse;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.service.agent.AgentActionProposalService;
import com.springclaw.service.agent.AgentRunTraceService;
import com.springclaw.service.ai.AiProviderService;
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
        ChatController controller = new ChatController(chatService, producer, resultStore, mock(MessageEventService.class), mock(AiProviderService.class));
        when(chatService.chat(any(AcceptedChatCommand.class)))
                .thenReturn(new ChatResponse("s1", "ok", "m1", 1L));
        RequestUserContextHolder.set(new RequestUserContext("user_local", "USER", System.currentTimeMillis() + 60_000));

        ApiResponse<ChatResponse> response = controller.send(new ChatRequest("s1", null, "你好", "api", "agent"));

        Assertions.assertEquals(0, response.getCode());
        ArgumentCaptor<AcceptedChatCommand> captor =
                ArgumentCaptor.forClass(AcceptedChatCommand.class);
        verify(chatService).chat(captor.capture());
        Assertions.assertEquals("user_local", captor.getValue().request().userId());
        Assertions.assertEquals("agent", captor.getValue().request().responseMode());
    }

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

    @Test
    void shouldRejectMismatchedUserIdFromRequestBody() {
        ChatController controller = new ChatController(mock(ChatService.class), mock(ChatMessageProducer.class), mock(AsyncChatResultStore.class), mock(MessageEventService.class), mock(AiProviderService.class));
        RequestUserContextHolder.set(new RequestUserContext("user_local", "USER", System.currentTimeMillis() + 60_000));

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> controller.send(new ChatRequest("s1", "other_user", "你好", "api")));

        Assertions.assertEquals(40313, ex.getCode());
    }

    @Test
    void shouldReturnChatHistoryForCurrentUserOnly() {
        MessageEventService messageEventService = mock(MessageEventService.class);
        ChatController controller = new ChatController(mock(ChatService.class), mock(ChatMessageProducer.class), mock(AsyncChatResultStore.class), messageEventService, mock(AiProviderService.class));
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
        ChatController controller = new ChatController(mock(ChatService.class), mock(ChatMessageProducer.class), mock(AsyncChatResultStore.class), messageEventService, mock(AiProviderService.class));
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
