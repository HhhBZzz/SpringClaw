package com.openclaw.service.chat.impl;

import com.openclaw.domain.entity.AgentSession;
import com.openclaw.dto.chat.ChatRequest;
import com.openclaw.service.ai.AiProviderService;
import com.openclaw.service.auth.AuthService;
import com.openclaw.service.context.AssembledContext;
import com.openclaw.service.context.ContextAssembler;
import com.openclaw.service.event.MessageEventService;
import com.openclaw.service.guard.ChatGuardService;
import com.openclaw.service.memory.MemoryService;
import com.openclaw.service.prompt.SoulPromptService;
import com.openclaw.service.session.AgentSessionService;
import com.openclaw.service.usage.LlmUsageRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceImplPersistenceTest {

    @Test
    void shouldPersistRawUserMessageInsteadOfObserveEnvelope() {
        AiProviderService aiProviderService = mock(AiProviderService.class);
        SoulPromptService soulPromptService = mock(SoulPromptService.class);
        AgentSessionService agentSessionService = mock(AgentSessionService.class);
        MessageEventService messageEventService = mock(MessageEventService.class);
        ChatGuardService chatGuardService = mock(ChatGuardService.class);
        MemoryService memoryService = mock(MemoryService.class);
        ContextAssembler contextAssembler = mock(ContextAssembler.class);
        OparLoopEngine oparLoopEngine = mock(OparLoopEngine.class);
        SimplifiedOparEngine simplifiedOparEngine = mock(SimplifiedOparEngine.class);
        ChatResponsePolicyService chatResponsePolicyService = mock(ChatResponsePolicyService.class);
        AuthService authService = mock(AuthService.class);
        ChatRoutingStateService chatRoutingStateService = mock(ChatRoutingStateService.class);
        ChatRoutingPolicyService chatRoutingPolicyService = mock(ChatRoutingPolicyService.class);
        ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
        ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        ConversationAdvisorSupport conversationAdvisorSupport = mock(ConversationAdvisorSupport.class);
        LlmUsageRecordService llmUsageRecordService = mock(LlmUsageRecordService.class);

        AgentSession session = new AgentSession();
        session.setId(1L);
        session.setSessionKey("s1");
        AssembledContext assembled = new AssembledContext(
                "s1",
                "feishu",
                "u1",
                "在么",
                "- USER: 在么",
                "（暂无长期语义记忆）",
                """
                        # 当前问题
                        在么
                        """
        );
        AiProviderService.ActiveChatClient activeClient = new AiProviderService.ActiveChatClient(
                "deepseek",
                "deepseek-chat",
                "https://api.deepseek.com",
                mock(ChatClient.class),
                true,
                ""
        );

        when(chatGuardService.acquireSessionLock("s1")).thenReturn("lock");
        when(agentSessionService.getOrCreate("s1", "feishu", "u1")).thenReturn(session);
        when(authService.resolveRoleByUserId("u1")).thenReturn("USER");
        when(chatRoutingStateService.resolveDefaultMode("opar")).thenReturn("opar");
        when(chatRoutingStateService.resolveAutoUpgrade(false)).thenReturn(false);
        when(chatRoutingPolicyService.decide("在么", "USER", "opar", false))
                .thenReturn(new ChatRoutingPolicyService.RoutingDecision("在么", "opar", false, false, "默认"));
        when(soulPromptService.buildSystemPrompt("feishu", "u1")).thenReturn("system");
        when(soulPromptService.soulVersion()).thenReturn("v1");
        when(contextAssembler.assemble("s1", "feishu", "u1", "在么")).thenReturn(assembled);
        when(aiProviderService.activeClient()).thenReturn(activeClient);
        when(oparLoopEngine.runLoop(eq(activeClient), eq("system"), eq(assembled), anyString(), any()))
                .thenReturn(new ChatExecutionResult(
                        assembled.observePrompt(),
                        "PLAN=[STEP 1] READY",
                        "ACT=skip",
                        "在。",
                        true
                ));

        ChatServiceImpl service = new ChatServiceImpl(
                aiProviderService,
                soulPromptService,
                agentSessionService,
                messageEventService,
                chatGuardService,
                memoryService,
                contextAssembler,
                oparLoopEngine,
                simplifiedOparEngine,
                chatResponsePolicyService,
                authService,
                chatRoutingStateService,
                chatRoutingPolicyService,
                modelTransportGuardService,
                modelCallExecutor,
                conversationAdvisorSupport,
                llmUsageRecordService,
                false,
                0,
                "opar",
                false
        );

        service.chat(new ChatRequest("s1", "u1", "在么", "feishu"));

        verify(messageEventService).recordTurn(
                eq("s1"),
                eq("feishu"),
                eq("u1"),
                eq("在么"),
                eq("[REFLECT] 在。"),
                eq("CHAT"),
                anyString()
        );
    }
}
