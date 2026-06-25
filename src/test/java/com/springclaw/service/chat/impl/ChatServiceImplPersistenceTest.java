package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.runtime.identity.DefaultRunIdentityFactory;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentRuntimeEngine;
import com.springclaw.service.agent.EngineSelector;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.service.usage.LlmUsageRecordService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceImplPersistenceTest {

    @Test
    void shouldPersistRawUserMessageInsteadOfObserveEnvelope() {
        AiProviderService aiProviderService = mock(AiProviderService.class);
        ChatGuardService chatGuardService = mock(ChatGuardService.class);
        OparLoopEngine oparLoopEngine = mock(OparLoopEngine.class);
        SimplifiedOparEngine simplifiedOparEngine = mock(SimplifiedOparEngine.class);
        ChatResponsePolicyService chatResponsePolicyService = mock(ChatResponsePolicyService.class);
        ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
        LlmUsageRecordService llmUsageRecordService = mock(LlmUsageRecordService.class);
        ConversationAdvisorSupport conversationAdvisorSupport = mock(ConversationAdvisorSupport.class);
        ChatContextFactory chatContextFactory = mock(ChatContextFactory.class);
        ChatResultPersister chatResultPersister = mock(ChatResultPersister.class);
        MetaGuardExecutor metaGuardExecutor = mock(MetaGuardExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        AgentRuntimeEngine agentRuntimeEngine = mock(AgentRuntimeEngine.class);
        EngineSelector engineSelector = mock(EngineSelector.class);
        SseEventBridge sseEventBridge = mock(SseEventBridge.class);

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

        ChatContext context = new ChatContext(
                session,
                "feishu",
                "u1",
                "USER",
                "在么",
                "在么",
                "req-1",
                "system",
                assembled,
                activeClient,
                "opar",
                "默认"
        );

        when(chatGuardService.acquireSessionLock("s1")).thenReturn("lock");
        when(aiProviderService.activeClient()).thenReturn(activeClient);
        when(chatContextFactory.build(any(ChatRequest.class), anyBoolean(), anyString())).thenReturn(context);
        when(engineSelector.select(any(ChatContext.class))).thenReturn(oparLoopEngine);
        when(oparLoopEngine.execute(any(), any()))
                .thenReturn(new ChatExecutionResult(
                        assembled.observePrompt(),
                        "PLAN=[STEP 1] READY",
                        "ACT=skip",
                        "在。",
                        true
                ));

        ChatServiceImpl service = new ChatServiceImpl(
                aiProviderService,
                chatGuardService,
                oparLoopEngine,
                simplifiedOparEngine,
                chatResponsePolicyService,
                modelTransportGuardService,
                llmUsageRecordService,
                conversationAdvisorSupport,
                chatContextFactory,
                chatResultPersister,
                metaGuardExecutor,
                toolOrchestrator,
                null,
                agentRuntimeEngine,
                engineSelector,
                null,
                sseEventBridge,
                null,
                null,
                new DefaultRunIdentityFactory(),
                false,
                true
        );

        service.chat(new ChatRequest("s1", "u1", "在么", "feishu"));

        verify(chatResultPersister).persist(eq(context), eq("在。"), any(ChatExecutionResult.class), eq(ChatPersistenceIntent.TERMINAL_RESULT));
    }

    @Test
    void executeTaskMessageShouldSkipPersistenceWhenDisabled() {
        AiProviderService aiProviderService = mock(AiProviderService.class);
        ChatGuardService chatGuardService = mock(ChatGuardService.class);
        OparLoopEngine oparLoopEngine = mock(OparLoopEngine.class);
        SimplifiedOparEngine simplifiedOparEngine = mock(SimplifiedOparEngine.class);
        ChatResponsePolicyService chatResponsePolicyService = mock(ChatResponsePolicyService.class);
        ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
        LlmUsageRecordService llmUsageRecordService = mock(LlmUsageRecordService.class);
        ConversationAdvisorSupport conversationAdvisorSupport = mock(ConversationAdvisorSupport.class);
        ChatContextFactory chatContextFactory = mock(ChatContextFactory.class);
        ChatResultPersister chatResultPersister = mock(ChatResultPersister.class);
        MetaGuardExecutor metaGuardExecutor = mock(MetaGuardExecutor.class);
        ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        AgentRuntimeEngine agentRuntimeEngine = mock(AgentRuntimeEngine.class);
        EngineSelector engineSelector = mock(EngineSelector.class);
        SseEventBridge sseEventBridge = mock(SseEventBridge.class);

        AssembledContext assembled = new AssembledContext(
                "task:shadow:t1",
                "api",
                "u1",
                "总结今天的项目进展",
                "",
                "",
                "# 当前问题\n总结今天的项目进展"
        );
        AiProviderService.ActiveChatClient activeClient = new AiProviderService.ActiveChatClient(
                "coding-plan",
                "qwen3.5-plus",
                "https://coding.dashscope.aliyuncs.com/v1",
                mock(ChatClient.class),
                true,
                ""
        );

        AgentSession ephemeralSession = new AgentSession();
        ephemeralSession.setId(0L);
        ephemeralSession.setSessionKey("task:shadow:t1");
        ephemeralSession.setChannel("api");
        ephemeralSession.setUserId("u1");
        ephemeralSession.setStatus("ACTIVE");

        ChatContext context = new ChatContext(
                ephemeralSession,
                "api",
                "u1",
                "USER",
                "总结今天的项目进展",
                "总结今天的项目进展",
                "req-2",
                "system",
                assembled,
                activeClient,
                "simplified",
                "默认"
        );

        when(chatGuardService.acquireSessionLock("task:shadow:t1")).thenReturn("lock");
        when(aiProviderService.activeClient()).thenReturn(activeClient);
        when(chatContextFactory.build(any(ChatRequest.class), anyBoolean(), anyString())).thenReturn(context);
        when(engineSelector.select(any(ChatContext.class))).thenReturn(simplifiedOparEngine);
        when(simplifiedOparEngine.execute(any(), any()))
                .thenReturn(new ChatExecutionResult(
                        assembled.observePrompt(),
                        "SIMPLIFIED",
                        "ACT=done",
                        "今天完成了 skill 重构",
                        true
                ));

        ChatServiceImpl service = new ChatServiceImpl(
                aiProviderService,
                chatGuardService,
                oparLoopEngine,
                simplifiedOparEngine,
                chatResponsePolicyService,
                modelTransportGuardService,
                llmUsageRecordService,
                conversationAdvisorSupport,
                chatContextFactory,
                chatResultPersister,
                metaGuardExecutor,
                toolOrchestrator,
                null,
                agentRuntimeEngine,
                engineSelector,
                null,
                sseEventBridge,
                null,
                null,
                new DefaultRunIdentityFactory(),
                false,
                true
        );

        ChatServiceImpl.TaskChatExecutionResult result = service.executeTaskMessage(
                new ChatRequest("task:shadow:t1", "u1", "总结今天的项目进展", "api"),
                false
        );

        assertThat(result.answer()).isEqualTo("今天完成了 skill 重构");
        verify(chatResultPersister, never()).persist(any(ChatContext.class), anyString(), any(ChatExecutionResult.class), any(ChatPersistenceIntent.class));
    }
}
