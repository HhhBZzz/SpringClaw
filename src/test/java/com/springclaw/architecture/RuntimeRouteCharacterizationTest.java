package com.springclaw.architecture;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.agent.AgentRuntimeEngine;
import com.springclaw.service.agent.CapabilityExecutorRegistry;
import com.springclaw.service.agent.EngineSelector;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.chat.impl.AutonomousLoopEngine;
import com.springclaw.service.chat.impl.BasicStreamEngine;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatResponsePolicyService;
import com.springclaw.service.chat.impl.ChatResultPersister;
import com.springclaw.service.chat.impl.ChatRoutingPolicyService;
import com.springclaw.service.chat.impl.ChatRoutingPolicyService.RoutingDecision;
import com.springclaw.service.chat.impl.ConversationAdvisorSupport;
import com.springclaw.service.chat.impl.LocalExecutionNarrator;
import com.springclaw.service.chat.impl.LocalExecutionSupport;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import com.springclaw.service.chat.impl.ModelLedStreamEngine;
import com.springclaw.service.chat.impl.ModelTransportGuardService;
import com.springclaw.service.chat.impl.OparLoopEngine;
import com.springclaw.service.chat.impl.SimplifiedOparEngine;
import com.springclaw.service.chat.impl.SseEventBridge;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.usage.LlmUsageRecordService;
import com.springclaw.tool.runtime.CapabilityRegistry;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterization test — records the current routing behaviour so the
 * unified-runtime spec has a falsifiable baseline. Does not modify production
 * code. Findings are referenced from
 * docs/architecture/runtime-current-state-audit.md sections 2 and 14.
 *
 * <p>Documented finding under test: routing today flows through three
 * sequential deciders — {@link ChatRoutingPolicyService},
 * {@link com.springclaw.service.agent.AgentDecisionService},
 * {@link com.springclaw.service.agent.EngineSelector} — with overlapping value
 * spaces. This file pins both {@code ChatRoutingPolicyService.decide} and the
 * production {@code supports()} / {@code EngineSelector} composition of all
 * six concrete engines.
 *
 * <p>If any assertion here changes, that change must appear in the
 * unified-runtime design as a deliberate behaviour migration, not a regression.
 */
class RuntimeRouteCharacterizationTest {

    private SkillRegistryService skillRegistry;
    private CapabilityRegistry capabilityRegistry;
    private ChatRoutingPolicyService service;

    @BeforeEach
    void setUp() {
        skillRegistry = Mockito.mock(SkillRegistryService.class);
        Mockito.when(skillRegistry.matchHighConfidenceDefinition(Mockito.anyString()))
                .thenReturn(Optional.empty());
        capabilityRegistry = Mockito.mock(CapabilityRegistry.class);
        service = new ChatRoutingPolicyService(skillRegistry, capabilityRegistry);
    }

    @Nested
    @DisplayName("ResponseMode short-circuit (1 of 3 routing layers)")
    class ResponseModeShortCircuit {

        @Test
        void fastResponseModeForcesSimplifiedAndDoesNotAutoUpgrade() {
            RoutingDecision decision = service.decide(
                    "请帮我分析一下这个项目的启动配置和调用链",
                    "USER", "simplified", true, Set.of(), "fast");

            assertThat(decision.executionMode()).isEqualTo("simplified");
            assertThat(decision.responseMode()).isEqualTo("fast");
            assertThat(decision.manualOverride()).isTrue();
            assertThat(decision.autoUpgraded()).isFalse();
            assertThat(decision.reason()).contains("快速模式");
        }

        @Test
        void deepResponseModeForcesOpar() {
            RoutingDecision decision = service.decide(
                    "你好", "USER", "simplified", true, Set.of(), "deep");

            assertThat(decision.executionMode()).isEqualTo("opar");
            assertThat(decision.responseMode()).isEqualTo("deep");
            assertThat(decision.manualOverride()).isTrue();
        }

        @Test
        void toolResponseModePrefixesIntentWithToolMarker() {
            RoutingDecision decision = service.decide(
                    "查询天气", "USER", "simplified", true, Set.of(), "tool");

            assertThat(decision.executionMode()).isEqualTo("simplified");
            assertThat(decision.responseMode()).isEqualTo("tool");
            // Documented quirk: tool responseMode rewrites intent into "tool:<intent>".
            assertThat(decision.intent()).startsWith("tool:");
        }
    }

    @Nested
    @DisplayName("Manual prefix overrides depend on role")
    class ManualPrefixOverrides {

        @Test
        void deepPrefixForAdminUpgradesToOpar() {
            RoutingDecision decision = service.decide(
                    "深度分析：当前 OPAR 引擎结构",
                    "ADMIN", "simplified", true, Set.of(), null);

            assertThat(decision.executionMode()).isEqualTo("opar");
            assertThat(decision.manualOverride()).isTrue();
            // Effective question has the prefix stripped — engines see the cleaned text.
            assertThat(decision.effectiveQuestion()).doesNotContain("深度分析");
        }

        @Test
        void deepPrefixForRegularUserDoesNotOverride() {
            RoutingDecision decision = service.decide(
                    "深度分析：当前 OPAR 引擎结构",
                    "USER", "simplified", true, Set.of(), null);

            // Documented behaviour: USER cannot manually upgrade — falls back to default mode.
            assertThat(decision.executionMode()).isEqualTo("simplified");
            assertThat(decision.manualOverride()).isFalse();
            assertThat(decision.reason()).contains("无手动切换权限");
        }

        @Test
        void simplifiedPrefixAlwaysDowngrades() {
            RoutingDecision decision = service.decide(
                    "普通回答：解释一下 OPAR",
                    "USER", "opar", true, Set.of(), null);

            assertThat(decision.executionMode()).isEqualTo("simplified");
            assertThat(decision.responseMode()).isEqualTo("fast");
        }
    }

    @Nested
    @DisplayName("Auto-upgrade scoring (≥ 3 promotes simplified to opar)")
    class AutoUpgradeScoring {

        @Test
        void shortSimpleQuestionStaysSimplified() {
            RoutingDecision decision = service.decide(
                    "你好", "USER", "simplified", true, Set.of(), null);

            assertThat(decision.executionMode()).isEqualTo("simplified");
            assertThat(decision.autoUpgraded()).isFalse();
        }

        @Test
        void multiCategoryComplexQuestionAutoUpgradesToOpar() {
            // A problem-type match, a domain match, and a multi-step match are
            // three distinct scoring categories, so the public decision upgrades.
            RoutingDecision decision = service.decide(
                    "分析日志然后说明",
                    "USER", "simplified", true, Set.of(), null);

            assertThat(decision.executionMode()).isEqualTo("opar");
            assertThat(decision.autoUpgraded()).isTrue();
            assertThat(decision.reason()).contains("自动升级");
        }

        @Test
        void multipleKeywordsInsideAnyKeywordCategoryContributeAtMostOnePoint() {
            List<String> twoCategoryQuestions = List.of(
                    "分析排查定位修复对比日志",
                    "日志报错异常堆栈配置分析",
                    "先再然后同时并且分别分析"
            );

            assertThat(twoCategoryQuestions)
                    .allSatisfy(question -> {
                        RoutingDecision decision = service.decide(
                                question,
                                "USER", "simplified", true, Set.of(), null);

                        assertThat(decision.executionMode()).isEqualTo("simplified");
                        assertThat(decision.autoUpgraded()).isFalse();
                    });
        }

        @Test
        void lengthCategoryAddsOnlyOnePointToTwoKeywordCategories() {
            RoutingDecision decision = service.decide(
                    "分析日志这是为了达到二十八个字符而补充的一段普通说明文字",
                    "USER", "simplified", true, Set.of(), null);

            assertThat(decision.executionMode()).isEqualTo("opar");
            assertThat(decision.autoUpgraded()).isTrue();
        }

        @Test
        void autoUpgradeDisabledKeepsSimplifiedEvenForComplexQuestion() {
            RoutingDecision decision = service.decide(
                    "请帮我分析并排查项目启动时日志里的配置加载异常,确认正确的依赖注入顺序",
                    "USER", "simplified", false, Set.of(), null);

            // Documented behaviour: auto-upgrade flag gates the entire scoring path.
            assertThat(decision.executionMode()).isEqualTo("simplified");
            assertThat(decision.autoUpgraded()).isFalse();
        }
    }

    @Nested
    @DisplayName("Default fallthrough")
    class DefaultFallthrough {

        @Test
        void noPrefixNoUpgradeKeepsConfiguredDefaultMode() {
            RoutingDecision decision = service.decide(
                    "你好",
                    "USER", "opar", false, Set.of(), null);

            assertThat(decision.executionMode()).isEqualTo("opar");
            assertThat(decision.autoUpgraded()).isFalse();
            assertThat(decision.manualOverride()).isFalse();
            assertThat(decision.reason()).contains("默认链路");
        }

        @Test
        void emptyQuestionStillReturnsADecision() {
            // Documented behaviour: empty input does not throw; it returns a default-mode decision.
            RoutingDecision decision = service.decide(
                    "", "USER", "simplified", true, Set.of(), null);

            assertThat(decision.executionMode()).isEqualTo("simplified");
            assertThat(decision.effectiveQuestion()).isEmpty();
        }
    }

    @Nested
    @DisplayName("RoutingDecision shape — duplicate routing axes carried in one record")
    class RecordShape {

        @Test
        void recordCarriesIntentAndResponseModeBesidesExecutionMode() {
            RoutingDecision decision = service.decide(
                    "今天天气怎么样", "USER", "simplified", true, Set.of(), null);

            // ExecutionMode (simplified|opar) + responseMode (fast|deep|tool|agent) +
            // intent — three orthogonal axes coexist in one record. Same shape today,
            // any redesign that collapses them is a deliberate breaking change.
            assertThat(decision.executionMode()).isIn("simplified", "opar");
            assertThat(decision.responseMode()).isIn("fast", "deep", "tool", "agent");
            assertThat(decision.intent()).isNotNull();
        }

        @Test
        void intentVocabularyDocumentedFromCurrentService() {
            // detectIntent currently returns one of:
            //   "general", "web-research", "control-plane", "local-files",
            //   "workspace-analysis", "tool-skill"
            // (kebab-case). AgentDecision uses snake_case for the same axis —
            // the audit doc § 2 names this duplication.
            RoutingDecision decision = service.decide(
                    "你好", "USER", "simplified", true, Set.of(), null);

            assertThat(decision.intent())
                    .isIn("general", "web-research", "control-plane",
                            "local-files", "workspace-analysis", "tool-skill");
        }
    }

    @Nested
    @DisplayName("Six production engines composed by EngineSelector")
    class ProductionEngineSelection {

        private ModelTransportGuardService modelTransportGuardService;
        private AiProviderService.ActiveChatClient activeClient;
        private BasicStreamEngine basicStreamEngine;
        private AgentRuntimeEngine agentRuntimeEngine;
        private AutonomousLoopEngine autonomousLoopEngine;
        private OparLoopEngine oparLoopEngine;
        private ModelLedStreamEngine modelLedStreamEngine;
        private SimplifiedOparEngine simplifiedOparEngine;
        private EngineSelector selector;

        @BeforeEach
        void setUpEngines() {
            modelTransportGuardService = mock(ModelTransportGuardService.class);
            activeClient = new AiProviderService.ActiveChatClient(
                    "test-provider",
                    "test-model",
                    "http://localhost",
                    mock(ChatClient.class),
                    true,
                    ""
            );
            when(modelTransportGuardService.isModelCallEnabled(any())).thenReturn(true);

            oparLoopEngine = instantiate(
                    OparLoopEngine.class,
                    mock(AiProviderService.class),
                    mock(ToolOrchestrator.class),
                    mock(LocalSkillFallbackService.class),
                    mock(LocalExecutionNarrator.class),
                    modelTransportGuardService,
                    mock(ModelCallExecutor.class),
                    mockByName("com.springclaw.service.chat.impl.OparContextAwareSupport"),
                    mockByName("com.springclaw.service.chat.impl.OparPromptSupport"),
                    mock(ConversationAdvisorSupport.class),
                    mock(LocalExecutionSupport.class),
                    true,
                    true,
                    3
            );
            basicStreamEngine = new BasicStreamEngine(
                    mock(ModelCallExecutor.class),
                    mock(ConversationAdvisorSupport.class),
                    modelTransportGuardService,
                    mock(ChatResponsePolicyService.class),
                    mock(LlmUsageRecordService.class),
                    oparLoopEngine,
                    mock(SseEventBridge.class),
                    mock(ChatResultPersister.class),
                    mock(ChatGuardService.class),
                    true
            );
            agentRuntimeEngine = new AgentRuntimeEngine(
                    mock(CapabilityExecutorRegistry.class),
                    mock(ModelCallExecutor.class),
                    mock(ConversationAdvisorSupport.class),
                    modelTransportGuardService,
                    mock(ChatResponsePolicyService.class)
            );
            autonomousLoopEngine = new AutonomousLoopEngine(
                    mock(AiProviderService.class),
                    mock(ToolOrchestrator.class),
                    modelTransportGuardService,
                    mock(ModelCallExecutor.class),
                    mock(ConversationAdvisorSupport.class),
                    mock(LocalExecutionSupport.class),
                    mock(ChatResponsePolicyService.class),
                    mock(SseEventBridge.class),
                    mock(ChatResultPersister.class),
                    mock(ChatGuardService.class),
                    true,
                    5
            );
            modelLedStreamEngine = new ModelLedStreamEngine(
                    mock(ConversationAdvisorSupport.class),
                    modelTransportGuardService,
                    mock(ChatResponsePolicyService.class),
                    mock(LlmUsageRecordService.class),
                    mock(ModelCallExecutor.class),
                    mock(ToolOrchestrator.class),
                    mock(SseEventBridge.class),
                    mock(ChatResultPersister.class),
                    mock(ChatGuardService.class),
                    true
            );
            simplifiedOparEngine = instantiate(
                    SimplifiedOparEngine.class,
                    mock(AiProviderService.class),
                    mock(ToolOrchestrator.class),
                    mock(LocalSkillFallbackService.class),
                    mock(LocalExecutionNarrator.class),
                    modelTransportGuardService,
                    mock(ModelCallExecutor.class),
                    mockByName("com.springclaw.service.chat.impl.OparContextAwareSupport"),
                    mock(ConversationAdvisorSupport.class),
                    mock(ChatResponsePolicyService.class),
                    mock(LocalExecutionSupport.class)
            );

            selector = new EngineSelector(List.of(
                    simplifiedOparEngine,
                    modelLedStreamEngine,
                    oparLoopEngine,
                    agentRuntimeEngine,
                    autonomousLoopEngine,
                    basicStreamEngine
            ));
        }

        @Test
        void selectorSortsAllSixActualInstancesByObservedPriorityOrder() {
            assertThat(selector.listAll())
                    .extracting(AgentEngine::name)
                    .containsExactly(
                            "basic-stream",
                            "agent-runtime",
                            "autonomous-loop",
                            "opar-loop",
                            "model-led-stream",
                            "simplified"
                    );
        }

        @Test
        void representativeContextsSelectTheObservedProductionEngines() {
            assertThat(selector.select(context(
                    "simplified", "默认链路", "agent", "general",
                    AgentDecision.general("普通聊天"))).name())
                    .isEqualTo("basic-stream");

            assertThat(selector.select(context(
                    "simplified", "默认链路", "agent", "workspace_analysis",
                    decision("workspace_analysis", "agent_tools", "read", false))).name())
                    .isEqualTo("agent-runtime");

            assertThat(selector.select(context(
                    "opar", "自动升级", "deep", "workspace_analysis",
                    decision("workspace_analysis", "agent_tools", "write", false))).name())
                    .isEqualTo("autonomous-loop");

            assertThat(selector.select(context(
                    "opar", "用户显式选择 OPAR", "deep", "workspace_analysis",
                    decision("workspace_analysis", "agent_tools", "read", false))).name())
                    .isEqualTo("opar-loop");

            assertThat(selector.select(context(
                    "simplified", "默认链路", "agent", "web_research", null)).name())
                    .isEqualTo("model-led-stream");

            assertThat(selector.select(context(
                    "simplified", "默认链路", "agent", "workspace_analysis",
                    decision("workspace_analysis", "agent_tools", "dangerous", true))).name())
                    .isEqualTo("simplified");
        }

        @Test
        void modelLedDoesNotSupportGeneralDecisionEvenWhenContextIntentIsNonGeneral() {
            ChatContext context = context(
                    "simplified",
                    "默认链路",
                    "agent",
                    "web_research",
                    AgentDecision.general("模型路由判定为普通问答")
            );

            assertThat(modelLedStreamEngine.supports(context)).isFalse();
            assertThat(selector.select(context).name()).isEqualTo("basic-stream");
        }

        private AgentDecision decision(String intent,
                                       String executionPath,
                                       String riskLevel,
                                       boolean requiresConfirmation) {
            return new AgentDecision(
                    intent,
                    executionPath,
                    List.of("representative-capability"),
                    riskLevel,
                    requiresConfirmation,
                    "characterization"
            );
        }

        private ChatContext context(String executionMode,
                                    String routingReason,
                                    String responseMode,
                                    String intent,
                                    AgentDecision decision) {
            return new ChatContext(
                    null,
                    "api",
                    "u1",
                    "USER",
                    "characterize routing",
                    "characterize routing",
                    "req-characterization",
                    "system",
                    null,
                    activeClient,
                    executionMode,
                    routingReason,
                    responseMode,
                    intent,
                    decision
            );
        }

        private Object mockByName(String className) {
            try {
                return mock(Class.forName(className));
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException(ex);
            }
        }

        private <T> T instantiate(Class<T> type, Object... arguments) {
            for (Constructor<?> constructor : type.getConstructors()) {
                Class<?>[] parameterTypes = constructor.getParameterTypes();
                if (parameterTypes.length != arguments.length) {
                    continue;
                }
                boolean compatible = true;
                for (int index = 0; index < parameterTypes.length; index++) {
                    if (!isCompatible(parameterTypes[index], arguments[index])) {
                        compatible = false;
                        break;
                    }
                }
                if (compatible) {
                    try {
                        return type.cast(constructor.newInstance(arguments));
                    } catch (ReflectiveOperationException ex) {
                        throw new IllegalStateException(ex);
                    }
                }
            }
            throw new IllegalStateException("No compatible constructor found for " + type.getName());
        }

        private boolean isCompatible(Class<?> parameterType, Object argument) {
            if (argument == null) {
                return !parameterType.isPrimitive();
            }
            if (!parameterType.isPrimitive()) {
                return parameterType.isInstance(argument);
            }
            return (parameterType == boolean.class && argument instanceof Boolean)
                    || (parameterType == int.class && argument instanceof Integer);
        }
    }
}
