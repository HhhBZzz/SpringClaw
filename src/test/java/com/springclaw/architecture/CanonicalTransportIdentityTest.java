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
