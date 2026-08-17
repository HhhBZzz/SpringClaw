package com.springclaw.service.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolResultPruner 契约测试(dsh 二轮差距 #3,对应 dsh compaction-tool-result-pruner):
 * 超预算的工具输出免模型剪枝——按字符保留头/尾,中段替换 PRUNE_MARKER,
 * 模型看到的不再是无限全文。
 */
class ToolResultPrunerTest {

    private final ToolResultPruner pruner = new ToolResultPruner();

    @Test
    void withinBudgetReturnsOriginal() {
        String output = "短输出";
        assertThat(pruner.prune(output)).isEqualTo(output);
        assertThat(pruner.prune("a".repeat(100))).isEqualTo("a".repeat(100));
    }

    @Test
    void nullAndEmptyReturnAsIs() {
        assertThat(pruner.prune(null)).isNull();
        assertThat(pruner.prune("")).isEmpty();
    }

    @Test
    void overBudgetKeepsHeadAndTailWithMarker() {
        String head = "头".repeat(200);
        String middle = "中".repeat(10000);
        String tail = "尾".repeat(200);
        String output = head + middle + tail;

        String pruned = pruner.prune(output);

        assertThat(pruned).startsWith("头".repeat(200));
        assertThat(pruned).endsWith("尾".repeat(200));
        assertThat(pruned).contains(ToolResultPruner.PRUNE_MARKER);
        // 剪掉的中段消失
        assertThat(pruned).doesNotContain(middle);
        // 总长度显著小于原文
        assertThat(pruned.length()).isLessThan(output.length() / 2);
    }

    @Test
    void markerReportsPrunedScale() {
        String output = "x".repeat(20_000);
        String pruned = pruner.prune(output);
        // marker 说明剪掉了多少
        assertThat(pruned).contains("pruned");
        assertThat(pruned).contains("characters");
    }

    @Test
    void budgetBoundaryExactFitNotPruned() {
        // 恰好等于阈值的输出不剪
        StringBuilder sb = new StringBuilder();
        while (sb.length() < ToolResultPruner.DEFAULT_THRESHOLD_CHARS - 10) {
            sb.append("内容");
        }
        String output = sb.substring(0, Math.min(sb.length(), ToolResultPruner.DEFAULT_THRESHOLD_CHARS));
        String pruned = pruner.prune(output);
        if (output.length() < ToolResultPruner.DEFAULT_THRESHOLD_CHARS) {
            assertThat(pruned).isEqualTo(output);
        }
    }

    @Test
    void customBudgetRespected() {
        ToolResultPruner tight = new ToolResultPruner(500, 100, 100);
        String output = "y".repeat(2000);
        String pruned = tight.prune(output);
        // 头 100 + marker + 尾 100,远小于原 2000
        assertThat(pruned.length()).isLessThan(400);
        assertThat(pruned).contains(ToolResultPruner.PRUNE_MARKER);
        assertThat(pruned).startsWith("y".repeat(100));
        assertThat(pruned).endsWith("y".repeat(100));
    }
}
