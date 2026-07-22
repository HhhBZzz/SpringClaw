package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetaGuardExecutorTypedContextTest {

    @Test
    void finalAnswerUsesTypedChatContextReflectPrompt() throws Exception {
        AiProviderService aiProviderService = mock(AiProviderService.class);
        OparLoopEngine oparLoopEngine = mock(OparLoopEngine.class);
        ChatResponsePolicyService responsePolicy = mock(ChatResponsePolicyService.class);
        ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        AiProviderService.ActiveChatClient activeClient = activeClient();
        ChatContext context = context(activeClient);
        MetaGuardExecutor executor = new MetaGuardExecutor(
                aiProviderService,
                oparLoopEngine,
                responsePolicy,
                mock(ModelTransportGuardService.class),
                modelCallExecutor,
                mock(ConversationAdvisorSupport.class),
                true,
                1
        );

        when(aiProviderService.activeClient()).thenReturn(activeClient);
        when(oparLoopEngine.renderReflectPrompt(context, "plan", "action"))
                .thenReturn("typed reflect prompt");
        when(modelCallExecutor.executeChat(
                eq(activeClient),
                eq("final-answer"),
                any(ModelCallExecutor.ChatRequestContext.class),
                eq(true),
                any()
        )).thenReturn(new ModelCallExecutor.ModelCallResult<>(
                "final answer",
                activeClient,
                List.of("provider:model"),
                false
        ));

        String answer = executor.execute(context, "plan", "action");

        assertThat(answer).isEqualTo("final answer");
        verify(oparLoopEngine).renderReflectPrompt(context, "plan", "action");
        verify(oparLoopEngine, never())
                .renderReflectPrompt(any(AssembledContext.class), anyString(), anyString());
    }

    private static AiProviderService.ActiveChatClient activeClient() {
        return new AiProviderService.ActiveChatClient(
                "deepseek",
                "deepseek-v4-pro",
                "https://api.deepseek.com",
                mock(ChatClient.class),
                true,
                ""
        );
    }

    private static ChatContext context(AiProviderService.ActiveChatClient activeClient) {
        AssembledContext assembled = new AssembledContext(
                "session:test",
                "api",
                "u1",
                "LEGACY-QUESTION",
                "",
                "",
                "# 当前问题\nLEGACY-QUESTION"
        );
        AgentSession session = new AgentSession();
        session.setSessionKey("session:test");
        return new ChatContext(
                session,
                "api",
                "u1",
                "USER",
                "legacy user message",
                "SNAPSHOT-QUESTION",
                "req-1",
                "system",
                assembled,
                activeClient,
                "opar",
                "test",
                "agent",
                "general",
                com.springclaw.service.agent.AgentDecision.general("test"),
                ContextInjection.empty(),
                OparPromptSupportTypedContextTest.snapshotForTest(),
                null
        );
    }
}
