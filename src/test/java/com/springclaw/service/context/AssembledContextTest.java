package com.springclaw.service.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssembledContextTest {

    @Test
    void shouldKeepProjectMemoryBankWhenQuestionChanges() {
        AssembledContext context = new AssembledContext(
                "s1",
                "api",
                "u1",
                "旧问题",
                "- USER: 之前的问题",
                "- [SESSION] ASSISTANT: 之前的答案",
                """
                        # 当前问题
                        旧问题

                        # 项目记忆（Memory Bank）
                        ### current-state
                        停止合并 engine，优先稳定 harness。

                        # 短期会话上下文（事件流）
                        - USER: 之前的问题

                        # 长期语义记忆（同会话优先）
                        - [SESSION] ASSISTANT: 之前的答案
                        """
        );

        AssembledContext updated = context.withQuestion("新问题");

        assertThat(updated.observePrompt())
                .contains("# 当前问题\n新问题")
                .contains("### current-state")
                .contains("停止合并 engine，优先稳定 harness。")
                .contains("- USER: 之前的问题")
                .contains("- [SESSION] ASSISTANT: 之前的答案")
                .doesNotContain("旧问题");
    }

    @Test
    void shouldSummarizeContextSourcesWithoutExposingContent() {
        AssembledContext context = new AssembledContext(
                "s1",
                "api",
                "u1",
                "当前问题",
                "- USER: 历史问题",
                "- [SESSION] ASSISTANT: 长期记忆",
                """
                        # 当前问题
                        当前问题

                        # 项目记忆（Memory Bank）
                        ### current-state
                        停止合并 engine，优先稳定 harness。

                        # 短期会话上下文（事件流）
                        - USER: 历史问题

                        # 长期语义记忆（同会话优先）
                        - [SESSION] ASSISTANT: 长期记忆
                        """
        );

        AssembledContext.ContextSourceSummary summary = context.sourceSummary();

        assertThat(summary.schema()).isEqualTo("springclaw.context-source.v1");
        assertThat(summary.memoryBankUsed()).isTrue();
        assertThat(summary.memoryBankChars()).isPositive();
        assertThat(summary.shortTermChars()).isEqualTo("- USER: 历史问题".length());
        assertThat(summary.semanticMemoryChars()).isEqualTo("- [SESSION] ASSISTANT: 长期记忆".length());
        assertThat(summary.observePromptChars()).isEqualTo(context.observePrompt().length());
    }
}
