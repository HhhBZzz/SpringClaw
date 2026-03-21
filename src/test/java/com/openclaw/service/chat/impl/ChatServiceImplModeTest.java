package com.openclaw.service.chat.impl;

import com.openclaw.domain.entity.AgentSession;
import com.openclaw.dto.chat.ChatRequest;
import com.openclaw.service.ai.AiProviderService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceImplModeTest {

    @Test
    void shouldDefaultToSimplifiedWhenModeIsUnknown() {
        Fixture fixture = new Fixture();
        when(fixture.simplifiedOparEngine.run(eq(fixture.activeClient), eq("system"), eq(fixture.assembled), anyString(), any()))
                .thenReturn(new ChatExecutionResult("observe", "SIMPLIFIED", "ACTION", "answer", true));

        ChatServiceImpl service = fixture.build("heavy-opar");
        service.chat(new ChatRequest("s1", "u1", "你好", "api"));

        verify(fixture.simplifiedOparEngine).run(eq(fixture.activeClient), eq("system"), eq(fixture.assembled), anyString(), any());
        verify(fixture.oparLoopEngine, never()).runLoop(any(), anyString(), any(), anyString(), any());
    }

    @Test
    void shouldUseOparWhenModeExplicitlyConfigured() {
        Fixture fixture = new Fixture();
        when(fixture.oparLoopEngine.runLoop(eq(fixture.activeClient), eq("system"), eq(fixture.assembled), anyString(), any()))
                .thenReturn(new ChatExecutionResult("observe", "PLAN", "ACTION", "answer", true));

        ChatServiceImpl service = fixture.build("opar");
        service.chat(new ChatRequest("s1", "u1", "你好", "api"));

        verify(fixture.oparLoopEngine).runLoop(eq(fixture.activeClient), eq("system"), eq(fixture.assembled), anyString(), any());
        verify(fixture.simplifiedOparEngine, never()).run(any(), anyString(), any(), anyString(), any());
    }

    private static final class Fixture {

        private final AiProviderService aiProviderService = mock(AiProviderService.class);
        private final SoulPromptService soulPromptService = mock(SoulPromptService.class);
        private final AgentSessionService agentSessionService = mock(AgentSessionService.class);
        private final MessageEventService messageEventService = mock(MessageEventService.class);
        private final ChatGuardService chatGuardService = mock(ChatGuardService.class);
        private final MemoryService memoryService = mock(MemoryService.class);
        private final ContextAssembler contextAssembler = mock(ContextAssembler.class);
        private final OparLoopEngine oparLoopEngine = mock(OparLoopEngine.class);
        private final SimplifiedOparEngine simplifiedOparEngine = mock(SimplifiedOparEngine.class);
        private final ChatResponsePolicyService chatResponsePolicyService = mock(ChatResponsePolicyService.class);
        private final ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
        private final ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        private final ConversationAdvisorSupport conversationAdvisorSupport = mock(ConversationAdvisorSupport.class);
        private final LlmUsageRecordService llmUsageRecordService = mock(LlmUsageRecordService.class);
        private final AgentSession session = new AgentSession();
        private final AssembledContext assembled = new AssembledContext(
                "s1",
                "api",
                "u1",
                "你好",
                "- USER: 你好",
                "（暂无长期语义记忆）",
                "# 当前问题\n你好"
        );
        private final AiProviderService.ActiveChatClient activeClient = new AiProviderService.ActiveChatClient(
                "deepseek",
                "deepseek-chat",
                "https://api.deepseek.com",
                mock(ChatClient.class),
                true,
                ""
        );

        private Fixture() {
            session.setId(1L);
            session.setSessionKey("s1");
            when(chatGuardService.acquireSessionLock("s1")).thenReturn("lock");
            when(agentSessionService.getOrCreate("s1", "api", "u1")).thenReturn(session);
            when(soulPromptService.buildSystemPrompt("api", "u1")).thenReturn("system");
            when(soulPromptService.soulVersion()).thenReturn("v1");
            when(contextAssembler.assemble("s1", "api", "u1", "你好")).thenReturn(assembled);
            when(aiProviderService.activeClient()).thenReturn(activeClient);
        }

        private ChatServiceImpl build(String agentMode) {
            return new ChatServiceImpl(
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
                    modelTransportGuardService,
                    modelCallExecutor,
                    conversationAdvisorSupport,
                    llmUsageRecordService,
                    false,
                    0,
                    agentMode
            );
        }
    }
}
