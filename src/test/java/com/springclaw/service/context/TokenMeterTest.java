package com.springclaw.service.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TokenMeter 契约测试(dsh 二轮差距 #2):请求前 token 估算——
 * 压缩/剪枝/预算的唯一计量事实源。估算策略:保守近似
 * (CJK 字符 1:1,其余 4 字符≈1 token)。
 */
class TokenMeterTest {

    private final TokenMeter meter = new TokenMeter();

    @Test
    void emptyAndNullEstimateZero() {
        assertThat(meter.estimate("")).isZero();
        assertThat(meter.estimate((String) null)).isZero();
        assertThat(meter.estimate("   ")).isZero();
    }

    @Test
    void cjkCharCountsOneTokenEach() {
        // 120 个中文字符 ≈ 120 token
        assertThat(meter.estimate("长".repeat(120))).isEqualTo(120);
    }

    @Test
    void asciiCountsFourCharsPerToken() {
        // 40 个 ASCII 字符 ≈ 10 token
        assertThat(meter.estimate("a".repeat(40))).isEqualTo(10);
    }

    @Test
    void mixedTextSumsBothSides() {
        // 10 中文 + 20 ASCII = 10 + 5
        assertThat(meter.estimate("长".repeat(10) + "a".repeat(20))).isEqualTo(15);
    }

    @Test
    void estimateWithinBudgetChecks() {
        assertThat(meter.withinBudget("长".repeat(90), 100)).isTrue();
        assertThat(meter.withinBudget("长".repeat(120), 100)).isFalse();
        assertThat(meter.withinBudget("", 0)).isTrue();
    }

    @Test
    void budgetRatioReportsFraction() {
        // 60 token 文本 / 100 预算 = 0.6
        assertThat(meter.pressure("长".repeat(60), 100)).isEqualTo(0.6);
        assertThat(meter.pressure("", 100)).isZero();
        assertThat(meter.pressure("长".repeat(60), 0)).isZero();
    }
}
