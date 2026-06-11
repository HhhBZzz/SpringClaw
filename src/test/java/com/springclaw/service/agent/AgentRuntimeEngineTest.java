package com.springclaw.service.agent;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatResponsePolicyService;
import com.springclaw.service.chat.impl.ConversationAdvisorSupport;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import com.springclaw.service.chat.impl.ModelTransportGuardService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.runtime.CapabilityRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        assertThat(run.executionResult().reflect())
                .contains("ChatServiceImpl.java");
        verifyNoInteractions(modelCallExecutor);
    }

    @Test
    void shouldAttachQualityScoreWhenEvidenceIsSufficient() {
        CapabilityExecutorRegistry registry = mock(CapabilityExecutorRegistry.class);
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
        AgentDecision decision = new AgentDecision("web_research", "agent_tools", List.of("weather"), "read", false, "天气查询");
        CapabilityPlan plan = new CapabilityPlan("web_research", "agent_tools", List.of("weather"), List.of("weather"), "read", false, "天气查询");
        List<CapabilityResult> results = List.of(new CapabilityResult(
                "weather.current",
                "weather",
                "success",
                "查询实时天气",
                "城市: 北京\n来源: open-meteo\n温度: 18℃\n湿度: 75%",
                42L,
                "read"
        ));
        AiProviderService.ActiveChatClient activeClient = activeClient(false);
        when(registry.plan(decision)).thenReturn(plan);
        when(registry.execute(eq(decision), any(AssembledContext.class), eq("req-1"))).thenReturn(results);
        when(modelTransportGuardService.isModelCallEnabled(activeClient)).thenReturn(false);
        when(modelTransportGuardService.disabledModelReason(activeClient)).thenReturn("未配置可用模型提供方");

        AgentRun run = engine.run(context(decision, activeClient, "今天北京天气怎样"));

        assertThat(run.verification().sufficient()).isTrue();
        assertThat(run.verification().quality().overallScore()).isGreaterThanOrEqualTo(80);
        assertThat(run.verification().quality().toolScore()).isGreaterThanOrEqualTo(90);
        assertThat(run.verification().quality().evidenceScore()).isGreaterThanOrEqualTo(85);
        assertThat(run.verification().quality().level()).isIn("strong", "acceptable");
        assertThat(run.steps()).extracting(AgentStep::stepName).contains("EVALUATE_RUN");
        verifyNoInteractions(modelCallExecutor);
    }

    @Test
    void shouldSkipReflectionModelWhenDeterministicEvidenceIsSufficient() throws Exception {
        CapabilityExecutorRegistry registry = mock(CapabilityExecutorRegistry.class);
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
        AgentDecision decision = new AgentDecision("web_research", "agent_tools", List.of("weather"), "read", false, "天气查询");
        CapabilityPlan plan = new CapabilityPlan("web_research", "agent_tools", List.of("weather"), List.of("weather"), "read", false, "天气查询");
        List<CapabilityResult> results = List.of(new CapabilityResult(
                "weather.current",
                "weather",
                "success",
                "查询实时天气",
                "城市: 北京\n来源: open-meteo\n温度: 18℃\n湿度: 75%",
                42L,
                "read"
        ));
        AiProviderService.ActiveChatClient activeClient = activeClient(true);
        when(registry.plan(decision)).thenReturn(plan);
        when(registry.execute(eq(decision), any(AssembledContext.class), eq("req-1"))).thenReturn(results);
        when(modelTransportGuardService.isModelCallEnabled(activeClient)).thenReturn(true);
        when(modelCallExecutor.executeChat(eq(activeClient), eq("agent-runtime-summary"), any(), eq(true), any()))
                .thenReturn(new ModelCallExecutor.ModelCallResult<>(
                        "北京今天 18℃，湿度 75%。",
                        activeClient,
                        List.of("deepseek:deepseek-v4-pro"),
                        false
                ));

        AgentRun run = engine.run(context(decision, activeClient, "今天北京天气怎样"));

        assertThat(run.verification().sufficient()).isTrue();
        verify(modelCallExecutor, never())
                .executeChat(eq(activeClient), eq("agent-runtime-reflection"), any(), eq(true), any());
    }

    @Test
    void shouldCallSummaryModelWhenEvidenceIsSufficient() throws Exception {
        CapabilityExecutorRegistry registry = mock(CapabilityExecutorRegistry.class);
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
        AgentDecision decision = new AgentDecision("web_research", "agent_tools", List.of("weather"), "read", false, "天气查询");
        CapabilityPlan plan = new CapabilityPlan("web_research", "agent_tools", List.of("weather"), List.of("weather"), "read", false, "天气查询");
        List<CapabilityResult> results = List.of(new CapabilityResult(
                "weather.current",
                "weather",
                "success",
                "查询实时天气",
                "城市: 北京\n来源: open-meteo\n观测时间: 2026-06-05T22:15\n天气: 阴\n温度: 23.3℃\n湿度: 41%",
                42L,
                "read"
        ));
        AiProviderService.ActiveChatClient activeClient = activeClient(true);
        when(registry.plan(decision)).thenReturn(plan);
        when(registry.execute(eq(decision), any(AssembledContext.class), eq("req-1"))).thenReturn(results);
        when(modelTransportGuardService.isModelCallEnabled(activeClient)).thenReturn(true);
        when(modelCallExecutor.executeChat(eq(activeClient), eq("agent-runtime-summary"), any(), eq(true), any()))
                .thenReturn(new ModelCallExecutor.ModelCallResult<>(
                        "北京现在阴天，23.3℃，湿度 41%。",
                        activeClient,
                        List.of("deepseek:deepseek-v4-pro"),
                        false
                ));

        AgentRun run = engine.run(context(decision, activeClient, "今天北京天气怎样"));

        assertThat(run.executionResult().modelEnabled()).isTrue();
        assertThat(run.executionResult().reflect()).contains("北京");
        verify(modelCallExecutor).executeChat(eq(activeClient), eq("agent-runtime-summary"), any(), eq(true), any());
    }

    @Test
    void shouldNormalizeModelSummaryIntoStableRuntimeAnswerContract() throws Exception {
        CapabilityExecutorRegistry registry = mock(CapabilityExecutorRegistry.class);
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
        AgentDecision decision = new AgentDecision("workspace_analysis", "agent_tools", List.of("workspace-review"), "read", false, "项目分析");
        CapabilityPlan plan = new CapabilityPlan("workspace_analysis", "agent_tools", List.of("workspace-review"), List.of("workspace"), "read", false, "项目分析");
        List<CapabilityResult> results = List.of(new CapabilityResult(
                "workspace-review",
                "workspace",
                "success",
                "审查当前工作区",
                "src/main/java/com/springclaw/service/agent/AgentRuntimeEngine.java\nsrc/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java",
                42L,
                "read"
        ));
        AiProviderService.ActiveChatClient activeClient = activeClient(true);
        when(registry.plan(decision)).thenReturn(plan);
        when(registry.execute(eq(decision), any(AssembledContext.class), eq("req-1"))).thenReturn(results);
        when(modelTransportGuardService.isModelCallEnabled(activeClient)).thenReturn(true);
        when(modelCallExecutor.executeChat(eq(activeClient), eq("agent-runtime-summary"), any(), eq(true), any()))
                .thenReturn(new ModelCallExecutor.ModelCallResult<>(
                        "项目主链路已经收敛到 AgentRuntimeEngine。",
                        activeClient,
                        List.of("deepseek:deepseek-v4-pro"),
                        false
                ));

        AgentRun run = engine.run(context(decision, activeClient, "分析当前项目 Agent 链路"));

        assertThat(run.executionResult().reflect())
                .contains("项目主链路已经收敛到 AgentRuntimeEngine。");
    }

    @Test
    void shouldKeepQualityLowWhenWeatherQuestionNeverUsesWeatherTool() {
        CapabilityExecutorRegistry registry = mock(CapabilityExecutorRegistry.class);
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
        AgentDecision decision = new AgentDecision("web_research", "agent_tools", List.of("weather"), "read", false, "天气查询");
        CapabilityPlan plan = new CapabilityPlan("web_research", "agent_tools", List.of("weather"), List.of("web"), "read", false, "天气查询");
        List<CapabilityResult> results = List.of(new CapabilityResult(
                "web.search",
                "web",
                "success",
                "联网搜索公开信息",
                "北京天气 - 搜索结果来自 weather.com.cn，但摘要被截断。",
                120L,
                "read"
        ));
        AiProviderService.ActiveChatClient activeClient = activeClient(true);
        when(registry.plan(decision)).thenReturn(plan);
        when(registry.execute(eq(decision), any(AssembledContext.class), eq("req-1"))).thenReturn(results);
        when(modelTransportGuardService.isModelCallEnabled(activeClient)).thenReturn(true);

        AgentRun run = engine.run(context(decision, activeClient, "今天北京天气怎样"));

        assertThat(run.verification().sufficient()).isFalse();
        assertThat(run.verification().quality().overallScore()).isLessThan(60);
        assertThat(run.verification().quality().toolScore()).isLessThan(70);
        assertThat(run.verification().quality().evidenceScore()).isLessThan(60);
        assertThat(run.verification().quality().reasons()).contains("weather 能力未成功执行");
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
        assertThat(run.executionResult().reflect()).contains("暂时无法完成");
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

        assertThat(prompt).startsWith("# SpringClaw Agent 最终回答整理");
        assertThat(prompt.indexOf("# 用户问题")).isGreaterThan(prompt.indexOf("# 输出规范"));
        assertThat(prompt.indexOf("Agent 决策")).isGreaterThan(prompt.indexOf("# 用户问题"));
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
                new ChatResponsePolicyService(""),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new CapabilityRegistry(List.of(
                        CapabilityRegistry.entryForTest("weather", "web",
                                new String[]{"天气", "weather", "wttr.in"}, true, "read", "查询天气")
                ))
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
        assertThat(executor.capabilities).containsExactly(List.of("web"), List.of("weather"));
        assertThat(run.capabilityResults()).hasSize(2);
        assertThat(run.capabilityResults()).extracting(CapabilityResult::capabilityId)
                .containsExactly("web.search", "weather.current");
        assertThat(run.verification().sufficient()).isTrue();
        assertThat(run.executionResult().reflect())
                .contains("哈尔滨今天有明确天气结果。");
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
        private final List<List<String>> capabilities = new ArrayList<>();

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
            capabilities.add(decision.selectedCapabilities());
            boolean weatherSelected = decision.selectedCapabilities().stream()
                    .anyMatch("weather"::equalsIgnoreCase);
            String capabilityId = weatherSelected ? "weather.current" : "web.search";
            String payload = weatherSelected
                    ? "城市: 哈尔滨\n来源: weather.com.cn\n温度: 18℃"
                    : "All Images News Argentina Australia Brazil Canada";
            return List.of(new CapabilityResult(
                    capabilityId,
                    weatherSelected ? "weather" : "web",
                    "success",
                    weatherSelected ? "查询实时天气" : "联网搜索公开信息",
                    payload,
                    10L,
                    "read"
            ));
        }
    }
}
