package com.springclaw.service.chat.impl;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OparLoopEngineDecisionTest {

    @Test
    void shouldRunDecisionBoundLocalCapabilityBeforeModelPlanning() {
        LocalSkillFallbackService localSkillFallbackService = mock(LocalSkillFallbackService.class);
        ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
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
        when(localSkillFallbackService.tryHandlePriorityStructured("分析 springclaw 项目架构"))
                .thenReturn(Optional.of(localResult));
        AiProviderService.ActiveChatClient activeClient = new AiProviderService.ActiveChatClient(
                "deepseek",
                "deepseek-v4-pro",
                "https://api.deepseek.com",
                mock(ChatClient.class),
                true,
                ""
        );

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
}
