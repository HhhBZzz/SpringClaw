package com.springclaw.architecture;

import com.springclaw.service.chat.impl.ChatRoutingPolicyService;
import com.springclaw.service.chat.impl.ChatRoutingPolicyService.RoutingDecision;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.tool.runtime.CapabilityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
 * spaces. This file pins {@code ChatRoutingPolicyService.decide} only;
 * AgentDecisionService and EngineSelector compose Spring application context
 * (their characterization is left for higher-level tests).
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
            // Category A: 分析 +1, 排查 +1; Category B: 日志 +1, 配置 +1, 启动 +1;
            // Category D: length ≥ 28 +1. Total ≥ 3 -> upgrade.
            RoutingDecision decision = service.decide(
                    "请帮我分析并排查项目启动时日志里的配置加载异常,确认正确的依赖注入顺序",
                    "USER", "simplified", true, Set.of(), null);

            assertThat(decision.executionMode()).isEqualTo("opar");
            assertThat(decision.autoUpgraded()).isTrue();
            assertThat(decision.reason()).contains("自动升级");
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
}
