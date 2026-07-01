package com.springclaw.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import com.springclaw.service.chat.impl.ModelTransportGuardService;
import com.springclaw.tool.runtime.CapabilityRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDecisionServiceTest {

    @Test
    void deterministicModelControlDecisionDoesNotAskModelRouterAgain() throws Exception {
        AgentDecisionRouter ruleRouter = mock(AgentDecisionRouter.class);
        AiProviderService aiProviderService = mock(AiProviderService.class);
        ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        ModelTransportGuardService transportGuard = mock(ModelTransportGuardService.class);
        CapabilityRegistry capabilityRegistry = new CapabilityRegistry(List.of(
                CapabilityRegistry.entryForTest(
                        "system",
                        "system",
                        new String[]{"切换模型", "当前模型"},
                        true,
                        "read",
                        "系统模型控制"
                )
        ));
        AgentDecision ruleDecision = new AgentDecision(
                "model_control",
                "agent_tools",
                List.of("system", "skill-library"),
                "read",
                false,
                "检测到模型控制请求。"
        );
        when(ruleRouter.routeByRules(any(AgentDecisionRequest.class))).thenReturn(ruleDecision);

        AgentDecisionService service = new AgentDecisionService(
                ruleRouter,
                aiProviderService,
                modelCallExecutor,
                transportGuard,
                capabilityRegistry,
                new ObjectMapper(),
                true
        );

        AgentDecision decision = service.decide(new AgentDecisionRequest(
                "session-A",
                "api",
                "alice",
                "ADMIN",
                "request-A",
                "切换模型到 DeepSeek",
                "agent",
                Set.of("system")
        ));

        assertThat(decision).isEqualTo(ruleDecision);
        verify(aiProviderService, never()).activeClient();
        verify(modelCallExecutor, never()).executeChat(any(), any(), any(), any(Boolean.class), any());
    }
}
