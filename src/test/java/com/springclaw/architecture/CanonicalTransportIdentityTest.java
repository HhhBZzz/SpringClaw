package com.springclaw.architecture;

import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.RunStateRepository;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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
                "requestId:String",
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
        AsyncChatRequestMessage message = new AsyncChatRequestMessage(
                "44444444444444444444444444444444",
                "session",
                "user",
                "hello",
                "api",
                100L,
                "agent"
        );
        RunStateRepository repository = mock(RunStateRepository.class);
        when(repository.requireByRunId(message.requestId()))
                .thenReturn(createdRun(message));
        ChatMessageConsumer consumer = new ChatMessageConsumer(
                chatService,
                resultStore,
                mock(ChatMessageProducer.class),
                mock(SimpMessagingTemplate.class),
                repository
        );
        ChatResponse response = new ChatResponse(
                message.requestId(),
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

    private static RunState createdRun(AsyncChatRequestMessage message) {
        Instant acceptedAt = Instant.ofEpochMilli(message.createdAt());
        return new RunState(
                message.requestId(), message.requestId(), 0, RunStatus.CREATED,
                message.sessionKey(), message.channel(), message.userId(),
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        message.channel(),
                        message.sessionKey(),
                        message.userId()
                ),
                "USER",
                message.message(),
                message.responseMode() == null ? "agent" : message.responseMode(),
                acceptedAt, null, acceptedAt, null,
                acceptedAt.plus(Duration.ofMinutes(30)),
                null, null, "", 1, "", List.of(), null, null, Map.of(), null
        );
    }
}
