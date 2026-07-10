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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentDecisionServiceTest {

    @Test
    void shouldKeepOrdinaryLongQuestionOnRuleDecisionWithoutModelRouter() {
        AgentDecision ruleDecision = AgentDecision.general("普通问答走最短路径。");
        AgentDecisionRouter ruleRouter = mock(AgentDecisionRouter.class);
        AiProviderService aiProviderService = mock(AiProviderService.class);
        ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        ModelTransportGuardService transportGuard = mock(ModelTransportGuardService.class);
        AgentDecisionRequest request = request("请解释一下 Java 线程池为什么可以复用线程");
        when(ruleRouter.routeByRules(request)).thenReturn(ruleDecision);

        AgentDecision decision = service(
                ruleRouter,
                aiProviderService,
                modelCallExecutor,
                transportGuard
        ).decide(request);

        assertThat(decision).isEqualTo(ruleDecision);
        verifyNoInteractions(aiProviderService, modelCallExecutor, transportGuard);
    }

    @Test
    void shouldStillUseModelRouterForAmbiguousActionWhenAvailable() {
        AgentDecision clarify = AgentDecision.clarify("动作化但缺少对象。");
        AgentDecisionRouter ruleRouter = mock(AgentDecisionRouter.class);
        AiProviderService aiProviderService = mock(AiProviderService.class);
        ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        ModelTransportGuardService transportGuard = mock(ModelTransportGuardService.class);
        AgentDecisionRequest request = request("帮我处理一下");
        AiProviderService.ActiveChatClient activeClient =
                new AiProviderService.ActiveChatClient("primary", "test-model", "", null, false, "disabled");
        when(ruleRouter.routeByRules(request)).thenReturn(clarify);
        when(aiProviderService.activeClient()).thenReturn(activeClient);
        when(transportGuard.isModelCallEnabled(activeClient)).thenReturn(false);

        AgentDecision decision = service(
                ruleRouter,
                aiProviderService,
                modelCallExecutor,
                transportGuard
        ).decide(request);

        assertThat(decision).isEqualTo(clarify);
        verify(aiProviderService).activeClient();
        verifyNoInteractions(modelCallExecutor);
    }

    private AgentDecisionService service(AgentDecisionRouter ruleRouter,
                                         AiProviderService aiProviderService,
                                         ModelCallExecutor modelCallExecutor,
                                         ModelTransportGuardService transportGuard) {
        return new AgentDecisionService(
                ruleRouter,
                aiProviderService,
                modelCallExecutor,
                transportGuard,
                new CapabilityRegistry(List.of()),
                new ObjectMapper(),
                true
        );
    }

    private AgentDecisionRequest request(String question) {
        return new AgentDecisionRequest(
                "session-1",
                "api",
                "alice",
                "USER",
                "run-1",
                question,
                "agent",
                Set.of("system", "workspace", "file", "web", "script")
        );
    }
}
