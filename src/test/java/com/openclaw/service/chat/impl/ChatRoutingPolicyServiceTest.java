package com.openclaw.service.chat.impl;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ChatRoutingPolicyServiceTest {

    private final ChatRoutingPolicyService service = new ChatRoutingPolicyService();

    @Test
    void shouldKeepDefaultModeForNormalQuestion() {
        ChatRoutingPolicyService.RoutingDecision decision = service.decide("你好", "USER", "simplified", true);

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
                true
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
                true
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
                false
        );

        Assertions.assertEquals("simplified", decision.executionMode());
        Assertions.assertEquals("当前模型是什么", decision.effectiveQuestion());
        Assertions.assertFalse(decision.manualOverride());
    }
}
