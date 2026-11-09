package com.springclaw.service.chat.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * native 账本路径的 phase briefing 渲染(spec 2026-11-09 §3.5):
 * briefing 只携带指令与阶段产物,不重复账本已承载的问题/历史/观察。
 */
class PhaseBriefingTest {

    private final OparPromptSupport support = new OparPromptSupport();

    @Test
    void planBriefingCarriesInstructionAndFormatWithoutQuestionHistory() {
        String briefing = support.renderPlanBriefing(2, "JSON-FORMAT-SPEC");

        assertThat(briefing).contains("[PLAN · Step 2]");
        assertThat(briefing).contains("Agent Planner");
        assertThat(briefing).contains("JSON-FORMAT-SPEC");
        assertThat(briefing).doesNotContain("历史步骤");
        assertThat(briefing).doesNotContain("观察摘要");
    }

    @Test
    void actionBriefingCarriesPlanAndWorkspaceEvidenceRule() {
        String briefing = support.renderActionBriefing(1, "1. 检索 workspace 2. 输出结论");

        assertThat(briefing).contains("[ACT · Step 1]");
        assertThat(briefing).contains("检索 workspace");
        assertThat(briefing).contains("WORKSPACE");
        assertThat(briefing).doesNotContain("历史步骤");
    }

    @Test
    void reflectBriefingInstructsFinalAnswerFromLedgerHistory() {
        String briefing = support.renderReflectBriefing();

        assertThat(briefing).contains("[REFLECT]");
        assertThat(briefing).contains("完整对话历史");
        assertThat(briefing).doesNotContain("计划结果：");
        assertThat(briefing).doesNotContain("行动结果：");
    }
}
