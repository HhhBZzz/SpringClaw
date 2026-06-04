package com.springclaw.service.agent;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatResponsePolicyService;
import com.springclaw.service.chat.impl.ConversationAdvisorSupport;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import com.springclaw.service.chat.impl.ModelTransportGuardService;
import com.springclaw.service.context.AssembledContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentRuntimeEngineTest {

    @Test
    void shouldReturnCapabilityResultsWhenModelIsDisabled() {
        CapabilityExecutorRegistry registry = mock(CapabilityExecutorRegistry.class);
        ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        ConversationAdvisorSupport conversationAdvisorSupport = mock(ConversationAdvisorSupport.class);
        ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
        ChatResponsePolicyService chatResponsePolicyService = new ChatResponsePolicyService("");
        AgentRuntimeEngine engine = new AgentRuntimeEngine(
                registry,
                modelCallExecutor,
                conversationAdvisorSupport,
                modelTransportGuardService,
                chatResponsePolicyService
        );
        AgentDecision decision = new AgentDecision("workspace_analysis", "agent_tools", List.of("workspace-review"), "read", false, "项目分析");
        CapabilityPlan plan = new CapabilityPlan("workspace_analysis", "agent_tools", List.of("workspace-review"), List.of("workspace"), "read", false, "项目分析");
        List<CapabilityResult> results = List.of(new CapabilityResult(
                "workspace-review",
                "workspace",
                "success",
                "审查当前工作区",
                "src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java",
                15L,
                "read"
        ));
        when(registry.plan(decision)).thenReturn(plan);
        when(registry.execute(decision, assembled(), "req-1")).thenReturn(results);
        AiProviderService.ActiveChatClient activeClient = activeClient(false);
        when(modelTransportGuardService.isModelCallEnabled(activeClient)).thenReturn(false);
        when(modelTransportGuardService.disabledModelReason(activeClient)).thenReturn("未配置可用模型提供方");

        AgentRun run = engine.run(context(decision, activeClient));

        assertThat(run.capabilityResults()).hasSize(1);
        assertThat(run.verification().sufficient()).isTrue();
        assertThat(run.executionResult().reflect()).contains("后端真实拿到的结果");
        assertThat(run.executionResult().reflect()).contains("ChatServiceImpl.java");
        verifyNoInteractions(modelCallExecutor);
    }

    @Test
    void shouldRenderCacheFriendlyStablePrefixBeforeDynamicSummaryContext() throws Exception {
        AgentRuntimeEngine engine = new AgentRuntimeEngine(
                mock(CapabilityExecutorRegistry.class),
                mock(ModelCallExecutor.class),
                mock(ConversationAdvisorSupport.class),
                mock(ModelTransportGuardService.class),
                new ChatResponsePolicyService("")
        );
        AgentDecision decision = new AgentDecision("workspace_analysis", "agent_tools", List.of("workspace-review"), "read", false, "项目分析");
        CapabilityPlan plan = new CapabilityPlan("workspace_analysis", "agent_tools", List.of("workspace-review"), List.of("workspace"), "read", false, "项目分析");
        List<CapabilityResult> results = List.of(new CapabilityResult(
                "workspace-review",
                "workspace",
                "success",
                "审查当前工作区",
                "src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java",
                15L,
                "read"
        ));

        Method method = AgentRuntimeEngine.class.getDeclaredMethod(
                "renderSummaryPrompt",
                ChatContext.class,
                CapabilityPlan.class,
                List.class,
                VerificationResult.class
        );
        method.setAccessible(true);
        String prompt = (String) method.invoke(engine, context(decision, activeClient(true)), plan, results,
                new VerificationResult("success", true, "能力执行完成"));

        assertThat(prompt).startsWith("# SpringClaw Agent Runtime Finalizer");
        assertThat(prompt.indexOf("# DYNAMIC_REQUEST")).isGreaterThan(prompt.indexOf("# RESPONSE_CONTRACT"));
        assertThat(prompt.indexOf("用户目标：")).isGreaterThan(prompt.indexOf("# DYNAMIC_REQUEST"));
    }

    @Test
    void shouldTruncateLargeCapabilityPayloadInSummaryPrompt() throws Exception {
        AgentRuntimeEngine engine = new AgentRuntimeEngine(
                mock(CapabilityExecutorRegistry.class),
                mock(ModelCallExecutor.class),
                mock(ConversationAdvisorSupport.class),
                mock(ModelTransportGuardService.class),
                new ChatResponsePolicyService("")
        );
        AgentDecision decision = new AgentDecision("local_files", "agent_tools", List.of("local-files"), "read", false, "本地文件");
        CapabilityPlan plan = new CapabilityPlan("local_files", "agent_tools", List.of("local-files"), List.of("local-files"), "read", false, "本地文件");
        String largePayload = "A".repeat(2600) + "TAIL_SHOULD_NOT_APPEAR";
        List<CapabilityResult> results = List.of(new CapabilityResult(
                "local-files.list-desktop",
                "local-files",
                "success",
                "列出桌面目录文件",
                largePayload,
                15L,
                "read"
        ));

        Method method = AgentRuntimeEngine.class.getDeclaredMethod(
                "renderSummaryPrompt",
                ChatContext.class,
                CapabilityPlan.class,
                List.class,
                VerificationResult.class
        );
        method.setAccessible(true);
        String prompt = (String) method.invoke(engine, context(decision, activeClient(true)), plan, results,
                new VerificationResult("success", true, "能力执行完成"));

        assertThat(prompt).contains("不要逐项复写超过 30 项的文件列表");
        assertThat(prompt).contains("...<TRUNCATED>");
        assertThat(prompt).doesNotContain("TAIL_SHOULD_NOT_APPEAR");
    }

    private ChatContext context(AgentDecision decision, AiProviderService.ActiveChatClient activeClient) {
        AgentSession session = new AgentSession();
        session.setId(1L);
        session.setSessionKey("s1");
        return new ChatContext(
                session,
                "api",
                "u1",
                "USER",
                "分析当前项目结构",
                "分析当前项目结构",
                "req-1",
                "system",
                assembled(),
                activeClient,
                "simplified",
                "agent runtime",
                "agent",
                decision.intent(),
                decision
        );
    }

    private AssembledContext assembled() {
        return new AssembledContext("s1", "api", "u1", "分析当前项目结构", "", "", "observe");
    }

    private AiProviderService.ActiveChatClient activeClient(boolean available) {
        return new AiProviderService.ActiveChatClient(
                "deepseek",
                "deepseek-v4-pro",
                "https://api.deepseek.com",
                mock(ChatClient.class),
                available,
                available ? "" : "未配置可用模型提供方"
        );
    }
}
