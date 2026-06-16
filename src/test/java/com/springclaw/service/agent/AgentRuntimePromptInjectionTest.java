package com.springclaw.service.agent;

import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatResponsePolicyService;
import com.springclaw.service.chat.impl.ConversationAdvisorSupport;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import com.springclaw.service.chat.impl.ModelTransportGuardService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P0 干路上下文注入：验证 AgentRuntimeEngine 的 reflection / summary prompt
 * 都把 ContextInjection.renderForPrompt() 注入到 prompt 头部。
 */
class AgentRuntimePromptInjectionTest {

    private static final String OBSERVE_MARKER = "🪐MARKER-OBSERVE🪐";
    private static final String QUESTION = "测试问题";

    private ContextInjection injection() {
        return new ContextInjection(
                "# 当前问题\n" + QUESTION + "\n\n# 短期会话上下文（事件流）\n" + OBSERVE_MARKER,
                "",
                "",
                Map.of()
        );
    }

    private AssembledContext assembled() {
        return new AssembledContext(
                "session:test",
                "api",
                "u1",
                QUESTION,
                "",
                "",
                "# 当前问题\n" + QUESTION
        );
    }

    private ChatContext context() {
        return new ChatContext(
                null,
                "api",
                "u1",
                "USER",
                QUESTION,
                QUESTION,
                "req-1",
                "system",
                assembled(),
                null,
                "simplified",
                "test",
                "agent",
                "workspace_analysis",
                AgentDecision.general("test"),
                injection()
        );
    }

    private AgentRuntimeEngine engine() {
        return new AgentRuntimeEngine(
                mock(CapabilityExecutorRegistry.class),
                mock(ModelCallExecutor.class),
                mock(ConversationAdvisorSupport.class),
                mock(ModelTransportGuardService.class),
                mock(ChatResponsePolicyService.class)
        );
    }

    @Test
    void renderReflectionPrompt_prependsInjection() {
        AgentDecision decision = new AgentDecision(
                "workspace_analysis",
                "agent_tools",
                List.of("workspace-search"),
                "read",
                false,
                "test"
        );
        CapabilityPlan plan = new CapabilityPlan(
                "workspace_analysis",
                "agent_tools",
                List.of("workspace-search"),
                List.of("workspace"),
                "read",
                false,
                "test plan"
        );
        String prompt = engine().renderReflectionPrompt(
                context(),
                decision,
                plan,
                List.of(),
                QUESTION,
                QUESTION,
                1
        );
        assertThat(prompt).contains(OBSERVE_MARKER);
        assertThat(prompt).contains(QUESTION);
    }

    @Test
    void renderSummaryPrompt_prependsInjection() {
        CapabilityPlan plan = new CapabilityPlan(
                "workspace_analysis",
                "agent_tools",
                List.of("workspace-search"),
                List.of("workspace"),
                "read",
                false,
                "test plan"
        );
        VerificationResult verification = new VerificationResult("success", true, "ok");
        String prompt = engine().renderSummaryPrompt(
                context(),
                plan,
                List.of(),
                verification
        );
        assertThat(prompt).contains(OBSERVE_MARKER);
        assertThat(prompt).contains(QUESTION);
    }
}
