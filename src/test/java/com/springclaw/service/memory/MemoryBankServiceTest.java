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
}
