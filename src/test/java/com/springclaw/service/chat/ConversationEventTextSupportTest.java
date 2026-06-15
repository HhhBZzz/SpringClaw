package com.springclaw.service.chat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationEventTextSupportTest {

    @Test
    void shouldExtractOnlyCurrentQuestionBeforeProjectMemoryBank() {
        String content = """
                [OBSERVE] # 当前问题
                当前项目下一步怎么做？

                # 项目记忆（Memory Bank）
                ### current-state
                停止合并 engine，优先稳定 harness。

                # 短期会话上下文（事件流）
                - USER: 之前的问题
                """;

        String question = ConversationEventTextSupport.extractUserQuestion(content);

        assertThat(question)
                .isEqualTo("当前项目下一步怎么做？")
                .doesNotContain("Memory Bank")
                .doesNotContain("停止合并 engine");
    }
}
