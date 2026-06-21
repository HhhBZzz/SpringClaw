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
