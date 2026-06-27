package com.springclaw.service.webhook;

import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.runtime.bridge.RunLifecycleBridge;
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.chat.AcceptedChatCommand;
import com.springclaw.service.chat.ChatService;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.strategy.channel.ChannelAdapter;
import com.springclaw.strategy.channel.factory.ChannelAdapterFactory;
import com.springclaw.strategy.channel.model.UnifiedInboundMessage;
import com.springclaw.strategy.channel.outbound.ChannelOutboundDispatcher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
        AuthService authService = mock(AuthService.class);
        RunLifecycleBridge runtimeBridge = mock(RunLifecycleBridge.class);
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
        when(authService.resolveRoleByUserId("user-A")).thenReturn("USER");
        when(chatService.chat(any(
                AcceptedChatCommand.class
        ))).thenReturn(new ChatResponse(
                "req-webhook-1",
                "session-A",
                "answer",
                "model",
                1L
        ));
        WebhookRouterService service = new WebhookRouterService(
                adapterFactory,
                chatService,
                eventService,
                dispatcher,
                identityFactory,
                authService,
                runtimeBridge
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
        assertThat(acceptance.getValue().sessionAccessClaim().claimType())
                .isEqualTo(SessionAccessClaim.ClaimType.PERSONAL);
        assertThat(acceptance.getValue().sessionAccessClaim().acceptanceOrigin())
                .isEqualTo(SessionAccessClaim.AcceptanceOrigin.VERIFIED_WEBHOOK);
        assertThat(Duration.between(
                acceptance.getValue().acceptedAt(),
                acceptance.getValue().deadlineAt()
        )).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void untrustedFeishuGroupWebhookRemainsPersonal() {
        ChannelAdapterFactory adapterFactory = mock(ChannelAdapterFactory.class);
        ChatService chatService = mock(ChatService.class);
        RunIdentityFactory identityFactory = mock(RunIdentityFactory.class);
        AuthService authService = mock(AuthService.class);
        RunLifecycleBridge runtimeBridge = mock(RunLifecycleBridge.class);
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        when(adapterFactory.getRequired("feishu")).thenReturn(adapter);
        when(adapter.adapt(Map.of("event", "group-message"))).thenReturn(
                new UnifiedInboundMessage(
                        "feishu",
                        "feishu:group:g1",
                        "alice",
                        "hello group"
                )
        );
        when(identityFactory.accept(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(authService.resolveRoleByUserId("alice")).thenReturn("USER");
        when(chatService.chat(any(AcceptedChatCommand.class)))
                .thenReturn(new ChatResponse(
                        "req-webhook-2",
                        "feishu:group:g1",
                        "answer",
                        "model",
                        1L
                ));
        WebhookRouterService service = new WebhookRouterService(
                adapterFactory,
                chatService,
                mock(MessageEventService.class),
                mock(ChannelOutboundDispatcher.class),
                identityFactory,
                authService,
                runtimeBridge
        );

        service.dispatch("feishu", Map.of("event", "group-message"));

        ArgumentCaptor<RunAcceptance> acceptance =
                ArgumentCaptor.forClass(RunAcceptance.class);
        verify(runtimeBridge).accepted(acceptance.capture());
        assertThat(acceptance.getValue().sessionAccessClaim().claimType())
                .isEqualTo(SessionAccessClaim.ClaimType.PERSONAL);
    }

    @Test
    void trustedFeishuGroupWebhookMintsSharedClaim() {
        ChannelAdapterFactory adapterFactory = mock(ChannelAdapterFactory.class);
        ChatService chatService = mock(ChatService.class);
        RunIdentityFactory identityFactory = mock(RunIdentityFactory.class);
        AuthService authService = mock(AuthService.class);
        RunLifecycleBridge runtimeBridge = mock(RunLifecycleBridge.class);
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        when(adapterFactory.getRequired("feishu")).thenReturn(adapter);
        when(adapter.adapt(Map.of("event", "trusted-group-message"))).thenReturn(
                new UnifiedInboundMessage(
                        "feishu",
                        "feishu:group:g1",
                        "alice",
                        "hello group"
                )
        );
        when(identityFactory.accept(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(authService.resolveRoleByUserId("alice")).thenReturn("USER");
        when(chatService.chat(any(AcceptedChatCommand.class)))
                .thenReturn(new ChatResponse(
                        "req-webhook-3",
                        "feishu:group:g1",
                        "answer",
                        "model",
                        1L
                ));
        WebhookRouterService service = new WebhookRouterService(
                adapterFactory,
                chatService,
                mock(MessageEventService.class),
                mock(ChannelOutboundDispatcher.class),
                identityFactory,
                authService,
                runtimeBridge
        );

        service.dispatchTrusted(
                "feishu",
                Map.of("event", "trusted-group-message")
        );

        ArgumentCaptor<RunAcceptance> acceptance =
                ArgumentCaptor.forClass(RunAcceptance.class);
        verify(runtimeBridge).accepted(acceptance.capture());
        assertThat(acceptance.getValue().sessionAccessClaim().claimType())
                .isEqualTo(SessionAccessClaim.ClaimType.SHARED);
        assertThat(acceptance.getValue().sessionAccessClaim().acceptanceOrigin())
                .isEqualTo(SessionAccessClaim.AcceptanceOrigin.VERIFIED_WEBHOOK);
        assertThat(acceptance.getValue().sessionAccessClaim().ownerOrSharedPrincipal())
                .isEqualTo("shared:feishu:feishu:group:g1");
    }

    @Test
    void lifecycleAcceptanceFailureStopsWebhookBeforeChat() {
        ChannelAdapterFactory adapterFactory = mock(ChannelAdapterFactory.class);
        ChatService chatService = mock(ChatService.class);
        RunIdentityFactory identityFactory = mock(RunIdentityFactory.class);
        AuthService authService = mock(AuthService.class);
        RunLifecycleBridge runtimeBridge = mock(RunLifecycleBridge.class);
        ChannelAdapter adapter = mock(ChannelAdapter.class);
        when(adapterFactory.getRequired("feishu")).thenReturn(adapter);
        when(adapter.adapt(Map.of("event", "message"))).thenReturn(
                new UnifiedInboundMessage("feishu", "session-A", "user-A", "hello")
        );
        when(identityFactory.accept(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(authService.resolveRoleByUserId("user-A")).thenReturn("USER");
        when(runtimeBridge.accepted(any(
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
                .chat(any(AcceptedChatCommand.class));
    }
}
