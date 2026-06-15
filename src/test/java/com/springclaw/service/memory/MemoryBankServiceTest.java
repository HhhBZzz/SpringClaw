package com.springclaw.service.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryBankServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldRenderOrderedMarkdownMemoryBank() throws Exception {
        Files.writeString(tempDir.resolve("current-state.md"), "# Current State\n\n正在稳定 harness。");
        Files.writeString(tempDir.resolve("project-brief.md"), "# Project Brief\n\nSpringClaw 是本地 Agent Harness。");
        Files.writeString(tempDir.resolve("random.txt"), "不应该读取");

        MemoryBankService service = new MemoryBankService(true, tempDir.toString(), 600);

        String context = service.renderContext();

        assertThat(context)
                .contains("### project-brief")
                .contains("SpringClaw 是本地 Agent Harness。")
                .contains("### current-state")
                .contains("正在稳定 harness。")
                .doesNotContain("不应该读取");
        assertThat(context.indexOf("### project-brief"))
                .isLessThan(context.indexOf("### current-state"));
    }

    @Test
    void shouldReturnEmptyContextWhenDisabledOrMissing() {
        MemoryBankService disabled = new MemoryBankService(false, tempDir.toString(), 600);
        MemoryBankService missing = new MemoryBankService(true, tempDir.resolve("missing").toString(), 600);

        assertThat(disabled.renderContext()).isEmpty();
        assertThat(missing.renderContext()).isEmpty();
    }

    @Test
    void shouldPrioritizeAgentLearningsBeforeProgressWhenRenderingContext() throws Exception {
        Files.writeString(tempDir.resolve("project-brief.md"), "# Project Brief\n\nSpringClaw 是本地 Agent Harness。");
        Files.writeString(tempDir.resolve("current-state.md"), "# Current State\n\n正在稳定 harness。");
        Files.writeString(tempDir.resolve("architecture-decisions.md"), "# Architecture\n\nMemory Bank 是非 RAG 主线。");
        Files.writeString(tempDir.resolve("agent-learnings.md"), "# Agent Learnings\n\n- rule: 不要重复执行同一失败命令。");
        Files.writeString(tempDir.resolve("progress.md"), "# Progress\n\n这段进度可能被截断。");

        MemoryBankService service = new MemoryBankService(true, tempDir.toString(), 900);

        String context = service.renderContext();

        assertThat(context)
                .contains("### agent-learnings")
                .contains("不要重复执行同一失败命令");
        assertThat(context.indexOf("### agent-learnings"))
                .isLessThan(context.indexOf("### progress"));
    }

    @Test
    void shouldFilterInactiveAgentLearningsWhenRenderingContext() throws Exception {
        Files.writeString(tempDir.resolve("agent-learnings.md"), """
                # Agent Learnings

                ## active learning

                - status: active
                - rule: 保留 active 经验。

                ## approved learning

                - status: approved
                - rule: 保留 approved 经验。

                ## legacy learning

                - rule: 保留旧格式经验。

                ## disabled learning

                - status: disabled
                - rule: 不要带入 disabled 经验。

                ## rejected learning

                - status: rejected
                - rule: 不要带入 rejected 经验。

                ## superseded learning

                - status: superseded
                - rule: 不要带入 superseded 经验。
                """);

        MemoryBankService service = new MemoryBankService(true, tempDir.toString(), 1200);

        String context = service.renderContext();

        assertThat(context)
                .contains("保留 active 经验。")
                .contains("保留 approved 经验。")
                .contains("保留旧格式经验。")
                .doesNotContain("不要带入 disabled 经验。")
                .doesNotContain("不要带入 rejected 经验。")
                .doesNotContain("不要带入 superseded 经验。");
    }
}
