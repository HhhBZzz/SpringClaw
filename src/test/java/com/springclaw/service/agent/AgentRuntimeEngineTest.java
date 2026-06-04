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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        when(registry.execute(eq(decision), any(AssembledContext.class), eq("req-1"))).thenReturn(results);
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
    void shouldNotAskModelToPolishAnswerWhenEvidenceVerificationFails() {
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
        AgentDecision decision = new AgentDecision("web_research", "agent_tools", List.of("weather", "web"), "read", false, "天气查询");
        CapabilityPlan plan = new CapabilityPlan("web_research", "agent_tools", List.of("weather", "web"), List.of("web"), "read", false, "天气查询");
        List<CapabilityResult> results = List.of(new CapabilityResult(
                "web.search",
                "web",
                "failed",
                "联网搜索公开信息",
                "BusinessException: 联网检索失败: Unexpected end of file from server",
                1128L,
                "read"
        ));
        AiProviderService.ActiveChatClient activeClient = activeClient(true);
        when(registry.plan(decision)).thenReturn(plan);
        when(registry.execute(eq(decision), any(AssembledContext.class), eq("req-1"))).thenReturn(results);
        when(modelTransportGuardService.isModelCallEnabled(activeClient)).thenReturn(true);

        AgentRun run = engine.run(context(decision, activeClient));

        assertThat(run.verification().sufficient()).isFalse();
        assertThat(run.executionResult().modelEnabled()).isFalse();
        assertThat(run.executionResult().reflect()).contains("校验未通过");
        assertThat(run.steps()).filteredOn(step -> "final".equals(step.type()))
                .extracting(AgentStep::status)
                .containsExactly("failed");
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

    @Test
    void shouldWarnReflectionModelAboutSearchEngineNoise() throws Exception {
        AgentRuntimeEngine engine = new AgentRuntimeEngine(
                mock(CapabilityExecutorRegistry.class),
                mock(ModelCallExecutor.class),
                mock(ConversationAdvisorSupport.class),
                mock(ModelTransportGuardService.class),
                new ChatResponsePolicyService("")
        );

        Method method = AgentRuntimeEngine.class.getDeclaredMethod("renderReflectionSystemPrompt");
        method.setAccessible(true);
        String prompt = (String) method.invoke(engine);

        assertThat(prompt).contains("All Images News");
        assertThat(prompt).contains("Argentina");
        assertThat(prompt).contains("Australia");
        assertThat(prompt).contains("sufficient=false");
        assertThat(prompt).contains("retryable=true");
    }

    @Test
    void shouldRetryWithReflectionNextQueryWhenEvidenceIsInsufficient() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        CapabilityExecutorRegistry registry = new CapabilityExecutorRegistry(List.of(executor));
        ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        ConversationAdvisorSupport conversationAdvisorSupport = mock(ConversationAdvisorSupport.class);
        ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
        AgentRuntimeEngine engine = new AgentRuntimeEngine(
                registry,
                modelCallExecutor,
                conversationAdvisorSupport,
                modelTransportGuardService,
                new ChatResponsePolicyService("")
        );
        AgentDecision decision = new AgentDecision("web_research", "agent_tools", List.of("web"), "read", false, "天气查询");
        AiProviderService.ActiveChatClient activeClient = activeClient(true);
        ChatContext context = context(decision, activeClient, "哈尔滨天气");
        when(modelTransportGuardService.isModelCallEnabled(activeClient)).thenReturn(true);
        when(modelCallExecutor.executeChat(eq(activeClient), eq("agent-runtime-reflection"), any(), eq(true), any()))
                .thenReturn(new ModelCallExecutor.ModelCallResult<>(
                        """
                                {"sufficient":false,"retryable":true,"problem":"搜索结果包含 All Images News 和国家列表，属于搜索引擎前端噪声。","nextQuery":"哈尔滨 今日 天气 wttr.in","preferredIntent":"web_research"}
                                """,
                        activeClient,
                        List.of("deepseek:deepseek-v4-pro"),
                        false
                ))
                .thenReturn(new ModelCallExecutor.ModelCallResult<>(
                        """
                                {"sufficient":true,"retryable":false,"problem":"已拿到可用天气证据。","nextQuery":"","preferredIntent":"web_research"}
                                """,
                        activeClient,
                        List.of("deepseek:deepseek-v4-pro"),
                        false
                ));
        when(modelCallExecutor.executeChat(eq(activeClient), eq("agent-runtime-summary"), any(), eq(true), any()))
                .thenReturn(new ModelCallExecutor.ModelCallResult<>(
                        "哈尔滨今天有明确天气结果。",
                        activeClient,
                        List.of("deepseek:deepseek-v4-pro"),
                        false
                ));

        AgentRun run = engine.run(context);

        assertThat(executor.questions).containsExactly("哈尔滨天气", "哈尔滨 今日 天气 wttr.in");
        assertThat(run.capabilityResults()).hasSize(2);
        assertThat(run.verification().sufficient()).isTrue();
        assertThat(run.executionResult().reflect()).isEqualTo("哈尔滨今天有明确天气结果。");
        assertThat(run.steps()).extracting(AgentStep::stepName).contains("REFLECT_EVIDENCE");
    }

    @Test
    void shouldCreateDerivedAssembledContextWithUpdatedQuestion() {
        AssembledContext updated = assembled().withQuestion("哈尔滨 今日 天气 wttr.in");

        assertThat(updated.question()).isEqualTo("哈尔滨 今日 天气 wttr.in");
        assertThat(updated.observePrompt()).contains("哈尔滨 今日 天气 wttr.in");
        assertThat(updated.sessionKey()).isEqualTo("s1");
    }

    private ChatContext context(AgentDecision decision, AiProviderService.ActiveChatClient activeClient) {
        return context(decision, activeClient, "分析当前项目结构");
    }

    private ChatContext context(AgentDecision decision, AiProviderService.ActiveChatClient activeClient, String question) {
        AgentSession session = new AgentSession();
        session.setId(1L);
        session.setSessionKey("s1");
        return new ChatContext(
                session,
                "api",
                "u1",
                "USER",
                question,
                question,
                "req-1",
                "system",
                new AssembledContext("s1", "api", "u1", question, "", "", question),
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

    private static final class RecordingExecutor implements CapabilityExecutor {

        private final List<String> questions = new ArrayList<>();

        @Override
        public String toolset() {
            return "web";
        }

        @Override
        public boolean supports(AgentDecision decision) {
            return decision != null && "web_research".equalsIgnoreCase(decision.intent());
        }

        @Override
        public List<CapabilityResult> execute(AgentDecision decision, AssembledContext assembled, String requestId) {
            questions.add(assembled.question());
            String payload = questions.size() == 1
                    ? "All Images News Argentina Australia Brazil Canada"
                    : "哈尔滨天气 18C 多云";
            return List.of(new CapabilityResult(
                    "web.search",
                    "web",
                    "success",
                    "联网搜索公开信息",
                    payload,
                    10L,
                    "read"
            ));
        }
    }
}
