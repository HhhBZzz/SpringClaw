package com.springclaw.service.chat.impl;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OparLoopEngineDecisionTest {

    @Test
    void executeUsesTypedChatContextForPlanPromptRendering() throws Exception {
        ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
        OparContextAwareSupport contextAwareSupport = mock(OparContextAwareSupport.class);
        OparPromptSupport promptSupport = mock(OparPromptSupport.class);
        LocalExecutionSupport localExecutionSupport = mock(LocalExecutionSupport.class);
        OparLoopEngine engine = new OparLoopEngine(
                mock(AiProviderService.class),
                mock(ToolOrchestrator.class),
                mock(LocalSkillFallbackService.class),
                mock(LocalExecutionNarrator.class),
                modelTransportGuardService,
                modelCallExecutor,
                contextAwareSupport,
                promptSupport,
                mock(ConversationAdvisorSupport.class),
                localExecutionSupport,
                true,
                false,
                1
        );
        AiProviderService.ActiveChatClient activeClient = activeClient();
        ChatContext context = context(activeClient);
        PlanResult ready = new PlanResult();
        ready.setStatus("READY");
        ready.setSummary("done");

        when(modelTransportGuardService.isModelCallEnabled(activeClient)).thenReturn(true);
        when(promptSupport.renderPlanPrompt(eq(context), anyString(), eq(1), anyString()))
                .thenReturn("typed plan prompt");
        when(modelCallExecutor.executeChat(
                eq(activeClient),
                eq("plan"),
                any(ModelCallExecutor.ChatRequestContext.class),
                eq(true),
                any()
        )).thenReturn(new ModelCallExecutor.ModelCallResult<>(
                ready,
                activeClient,
                List.of("provider:model"),
                false
        ));

        engine.execute(context, (reason, assembled) -> "fallback");

        verify(promptSupport).renderPlanPrompt(eq(context), anyString(), eq(1), anyString());
        verify(promptSupport, org.mockito.Mockito.never())
                .renderPlanPrompt(any(AssembledContext.class), anyString(), eq(1), anyString());
        verifyNoMoreInteractions(promptSupport);
    }

    @Test
    void shouldRunDecisionBoundLocalCapabilityBeforeModelPlanning() {
        LocalSkillFallbackService localSkillFallbackService = mock(LocalSkillFallbackService.class);
        ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
        LocalExecutionSupport localExecutionSupport = mock(LocalExecutionSupport.class);
        OparLoopEngine engine = new OparLoopEngine(
                mock(AiProviderService.class),
                mock(ToolOrchestrator.class),
                localSkillFallbackService,
                mock(LocalExecutionNarrator.class),
                modelTransportGuardService,
                modelCallExecutor,
                mock(OparContextAwareSupport.class),
                new OparPromptSupport(),
                mock(ConversationAdvisorSupport.class),
                localExecutionSupport,
                true,
                false,
                3
        );
        AssembledContext assembled = new AssembledContext(
                "s1",
                "api",
                "u1",
                "分析 springclaw 项目架构",
                "observe",
                "",
                "# 当前问题\n分析 springclaw 项目架构"
        );
        AgentDecision decision = new AgentDecision(
                "workspace_analysis",
                "agent_tools",
                List.of("workspace-search", "workspace-review"),
                "read",
                false,
                "匹配到 workspace"
        );
        LocalSkillFallbackService.LocalSkillResult localResult = new LocalSkillFallbackService.LocalSkillResult(
                "BUILTIN_SKILL:CODE_ANALYSIS",
                "skill=code-analysis\n项目结构概览\n- src/main/java",
                "项目结构概览\n- src/main/java",
                true
        );
        when(localExecutionSupport.tryPriorityStructured("分析 springclaw 项目架构", true))
                .thenReturn(localResult);
        AiProviderService.ActiveChatClient activeClient = activeClient();

        ChatExecutionResult result = engine.runLoop(
                activeClient,
                "system",
                assembled,
                "req-1",
                (reason, context) -> "fallback",
                decision
        );

        assertThat(result.action()).contains("项目结构概览");
        assertThat(result.plan()).contains("命中已决策能力的本地执行路线");
        verifyNoInteractions(modelCallExecutor);
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
                "s1",
                "api",
                "u1",
                "LEGACY-QUESTION",
                "",
                "",
                "# 当前问题\nLEGACY-QUESTION"
        );
        return new ChatContext(
                null,
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
                AgentDecision.general("test"),
                ContextInjection.empty(),
                OparPromptSupportTypedContextTest.snapshotForTest(),
                null
        );
    }
}
