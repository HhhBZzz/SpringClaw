package com.springclaw.service.chat.async;

import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.RunStateRepository;
import com.springclaw.service.chat.AcceptedChatCommand;
import com.springclaw.service.chat.ChatService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
        RunState canonical = createdRun(message);
        when(repository.requireByRunId(message.requestId()))
                .thenReturn(canonical);
        when(chatService.chat(any(AcceptedChatCommand.class)))
                .thenReturn(new ChatResponse(
                        message.requestId(),
                        "session",
                        "answer",
                        "model",
                        123L
                ));
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

        ArgumentCaptor<AcceptedChatCommand> command =
                ArgumentCaptor.forClass(AcceptedChatCommand.class);
        verify(chatService).chat(command.capture());
        assertThat(command.getValue().runId()).isEqualTo(canonical.runId());
        assertThat(command.getValue().request().sessionKey()).isEqualTo(canonical.sessionKey());
        assertThat(command.getValue().request().channel()).isEqualTo(canonical.channel());
        assertThat(command.getValue().request().userId()).isEqualTo(canonical.userId());
        assertThat(command.getValue().request().message()).isEqualTo(canonical.originalMessage());
        assertThat(command.getValue().request().responseMode()).isEqualTo(canonical.responseMode());
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

    @Test
    void sharedCanonicalClaimStopsRabbitDeliveryBeforeLegacyExecution() {
        ChatService chatService = mock(ChatService.class);
        RunStateRepository repository = mock(RunStateRepository.class);
        AsyncChatRequestMessage message = message();
        when(repository.requireByRunId(message.requestId()))
                .thenReturn(createdRun(
                        message,
                        SessionAccessClaim.sharedVerified(
                                message.channel(),
                                message.sessionKey(),
                                message.userId()
                        )
                ));
        ChatMessageConsumer consumer = new ChatMessageConsumer(
                chatService,
                mock(AsyncChatResultStore.class),
                mock(ChatMessageProducer.class),
                mock(SimpMessagingTemplate.class),
                repository
        );

        assertThatThrownBy(() -> consumer.consume(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PERSONAL");

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
        return createdRun(
                message,
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        message.channel(),
                        message.sessionKey(),
                        message.userId()
                )
        );
    }

    private static RunState createdRun(
            AsyncChatRequestMessage message,
            SessionAccessClaim sessionAccessClaim
    ) {
        Instant acceptedAt = Instant.ofEpochMilli(message.createdAt());
        return new RunState(
                message.requestId(),
                message.requestId(),
                0,
                RunStatus.CREATED,
                message.sessionKey(),
                message.channel(),
                message.userId(),
                sessionAccessClaim,
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
