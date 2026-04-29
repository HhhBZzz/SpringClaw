package com.springclaw.controller;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.service.chat.ChatService;
import com.springclaw.service.chat.async.AsyncChatResultStore;
import com.springclaw.service.chat.async.ChatMessageProducer;
import com.springclaw.web.auth.RequestUserContext;
import com.springclaw.web.auth.RequestUserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerAuthTest {

    @AfterEach
    void clearContext() {
        RequestUserContextHolder.clear();
    }

    @Test
    void shouldUseAuthenticatedUsernameAsEffectiveUserId() {
        ChatService chatService = mock(ChatService.class);
        ChatMessageProducer producer = mock(ChatMessageProducer.class);
        AsyncChatResultStore resultStore = mock(AsyncChatResultStore.class);
        ChatController controller = new ChatController(chatService, producer, resultStore);
        when(chatService.chat(any())).thenReturn(new ChatResponse("s1", "ok", "m1", 1L));
        RequestUserContextHolder.set(new RequestUserContext("user_local", "USER", System.currentTimeMillis() + 60_000));

        ApiResponse<ChatResponse> response = controller.send(new ChatRequest("s1", null, "你好", "api"));

        Assertions.assertEquals(0, response.getCode());
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(chatService).chat(captor.capture());
        Assertions.assertEquals("user_local", captor.getValue().userId());
    }

    @Test
    void shouldRejectMismatchedUserIdFromRequestBody() {
        ChatController controller = new ChatController(mock(ChatService.class), mock(ChatMessageProducer.class), mock(AsyncChatResultStore.class));
        RequestUserContextHolder.set(new RequestUserContext("user_local", "USER", System.currentTimeMillis() + 60_000));

        BusinessException ex = Assertions.assertThrows(BusinessException.class,
                () -> controller.send(new ChatRequest("s1", "other_user", "你好", "api")));

        Assertions.assertEquals(40313, ex.getCode());
    }
}
