package com.springclaw.service.chat.impl;

import com.springclaw.service.skill.bundle.SkillCatalogService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.tool.runtime.CapabilityRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class ChatRoutingPolicyServiceTest {

    private final ChatRoutingPolicyService service = new ChatRoutingPolicyService(
            new SkillRegistryService(new SkillCatalogService(true, "./skills")),
            new CapabilityRegistry(List.of(
                    CapabilityRegistry.entryForTest("system", "system",
                            new String[]{"当前模型", "当前时间", "今天几号"}, true, "read", "系统状态查询"),
                    CapabilityRegistry.entryForTest("workspace-search", "workspace",
                            new String[]{"项目", "代码", "源码", "架构"}, true, "read", "工作区检索"),
                    CapabilityRegistry.entryForTest("script-skill", "script",
                            new String[]{"skill", "运行", "执行", "脚本"}, false, "execution", "脚本技能执行")
            ))
    );

    @Test
    void shouldKeepDefaultModeForNormalQuestion() {
        ChatRoutingPolicyService.RoutingDecision decision = service.decide("你好", "USER", "simplified", true, Set.of("system", "workspace", "file", "script"));

        Assertions.assertEquals("simplified", decision.executionMode());
        Assertions.assertEquals("你好", decision.effectiveQuestion());
        Assertions.assertFalse(decision.autoUpgraded());
        Assertions.assertFalse(decision.manualOverride());
    }

    @Test
    void shouldAutoUpgradeForComplexDiagnosticTask() {
        ChatRoutingPolicyService.RoutingDecision decision = service.decide(
                "先查启动日志，再定位报错原因，并给出修复方案",
                "USER",
                "simplified",
                true,
                Set.of("workspace", "file", "script")
        );

        Assertions.assertEquals("opar", decision.executionMode());
        Assertions.assertTrue(decision.autoUpgraded());
    }

    @Test
    void shouldAutoUpgradeForBuiltinCodeAnalysisSkill() {
        ChatRoutingPolicyService.RoutingDecision decision = service.decide(
                "用代码分析分析 ChatServiceImpl",
                "USER",
                "simplified",
                true,
                Set.of("workspace", "file", "script")
        );

        Assertions.assertEquals("opar", decision.executionMode());
        Assertions.assertTrue(decision.autoUpgraded());
    }

    @Test
    void shouldAllowAdminManualDeepAnalysis() {
        ChatRoutingPolicyService.RoutingDecision decision = service.decide(
                "深度分析：帮我看这段启动报错",
                "ADMIN",
                "simplified",
                true,
                Set.of("workspace", "file", "script")
        );

        Assertions.assertEquals("opar", decision.executionMode());
        Assertions.assertEquals("帮我看这段启动报错", decision.effectiveQuestion());
        Assertions.assertTrue(decision.manualOverride());
    }

    @Test
    void shouldIgnoreManualOverrideForNormalUser() {
        ChatRoutingPolicyService.RoutingDecision decision = service.decide(
                "深度分析：当前模型是什么",
                "USER",
                "simplified",
                false,
                Set.of("system")
        );

        Assertions.assertEquals("simplified", decision.executionMode());
        Assertions.assertEquals("当前模型是什么", decision.effectiveQuestion());
        Assertions.assertFalse(decision.manualOverride());
    }

    @Test
    void shouldAllowNormalUserToForceSimplifiedMode() {
        ChatRoutingPolicyService.RoutingDecision decision = service.decide(
                "快速回答：先查启动日志，再定位报错原因，并给出修复方案",
                "USER",
                "opar",
                true,
                Set.of("workspace", "file", "script")
        );

        Assertions.assertEquals("simplified", decision.executionMode());
        Assertions.assertEquals("先查启动日志，再定位报错原因，并给出修复方案", decision.effectiveQuestion());
        Assertions.assertFalse(decision.autoUpgraded());
    }

    @Test
    void shouldUseAgentResponseModeByDefault() {
        ChatRoutingPolicyService.RoutingDecision decision = service.decide(
                "你好",
                "USER",
                "simplified",
                true,
                Set.of("workspace", "file", "script"),
                null
        );

        Assertions.assertEquals("agent", decision.responseMode());
        Assertions.assertEquals("simplified", decision.executionMode());
        Assertions.assertEquals("你好", decision.effectiveQuestion());
        Assertions.assertEquals("general", decision.intent());
    }

    @Test
    void shouldKeepAgentResponseModeWhenComplexWorkspaceTaskAutoUpgrades() {
        ChatRoutingPolicyService.RoutingDecision decision = service.decide(
                "帮我分析当前项目结构",
                "USER",
                "simplified",
                true,
                Set.of("workspace", "file", "script"),
                null
        );

        Assertions.assertEquals("agent", decision.responseMode());
        Assertions.assertEquals("opar", decision.executionMode());
        Assertions.assertTrue(decision.intent().contains("workspace"));
    }

    @Test
    void shouldRespectExplicitFastResponseMode() {
        ChatRoutingPolicyService.RoutingDecision decision = service.decide(
                "先查启动日志，再定位报错原因，并给出修复方案",
                "USER",
                "opar",
                true,
                Set.of("workspace", "file", "script"),
                "fast"
        );

        Assertions.assertEquals("fast", decision.responseMode());
        Assertions.assertEquals("simplified", decision.executionMode());
        Assertions.assertFalse(decision.autoUpgraded());
    }

    @Test
    void shouldRespectExplicitDeepResponseMode() {
        ChatRoutingPolicyService.RoutingDecision decision = service.decide(
                "帮我看这段启动报错",
                "USER",
                "simplified",
                false,
                Set.of("workspace", "file", "script"),
                "deep"
        );

        Assertions.assertEquals("deep", decision.responseMode());
        Assertions.assertEquals("opar", decision.executionMode());
        Assertions.assertTrue(decision.manualOverride());
    }

    @Test
    void shouldRespectExplicitToolResponseMode() {
        ChatRoutingPolicyService.RoutingDecision decision = service.decide(
                "运行 web_crawler 抓取 https://example.com",
                "USER",
                "opar",
                true,
                Set.of("script"),
                "tool"
        );

        Assertions.assertEquals("tool", decision.responseMode());
        Assertions.assertEquals("simplified", decision.executionMode());
        Assertions.assertTrue(decision.intent().contains("tool"));
    }

    @Test
    void shouldMarkModelStatusQuestionAsControlIntent() {
        ChatRoutingPolicyService.RoutingDecision decision = service.decide(
                "当前模型是什么",
                "USER",
                "simplified",
                true,
                Set.of("system"),
                null
        );

        Assertions.assertEquals("agent", decision.responseMode());
        Assertions.assertEquals("simplified", decision.executionMode());
        Assertions.assertEquals("control-plane", decision.intent());
    }
}
