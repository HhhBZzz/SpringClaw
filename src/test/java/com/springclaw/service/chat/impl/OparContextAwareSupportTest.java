package com.springclaw.service.chat.impl;

import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OparContextAwareSupportTest {

    private final ConversationHistoryService conversationHistoryService = mock(ConversationHistoryService.class);
    private final OparContextAwareSupport support = new OparContextAwareSupport(conversationHistoryService);

    @Test
    void shouldAnswerFirstMessageQuestionFromHistoryService() {
        when(conversationHistoryService.findFirstUserQuestion("s1")).thenReturn(java.util.Optional.of("你都有什么功能？"));

        LocalSkillFallbackService.LocalSkillResult result = support.tryContextAwareLocalResult(context("我之前问你的第一个消息是什么"));

        assertThat(result).isNotNull();
        assertThat(result.route()).isEqualTo("SESSION_FIRST_MESSAGE_QUERY");
        assertThat(result.fallbackAnswer()).contains("你都有什么功能？");
    }

    @Test
    void shouldAnswerMemoryCapabilityQuestionWithPersistedCount() {
        when(conversationHistoryService.countRememberedUserQuestions("s1")).thenReturn(5L);

        LocalSkillFallbackService.LocalSkillResult result = support.tryContextAwareLocalResult(context("那你能记住么"));

        assertThat(result).isNotNull();
        assertThat(result.route()).isEqualTo("CONTEXT_MEMORY_QUERY");
        assertThat(result.executionDetails()).contains("已持久化用户消息条目: 5");
        assertThat(result.fallbackAnswer()).contains("已持久化的 5 条用户消息");
    }

    @Test
    void shouldAnswerFirstMessageTimeQuestionFromHistoryService() {
        when(conversationHistoryService.findFirstUserQuestionEntry("s1"))
                .thenReturn(java.util.Optional.of(new ConversationHistoryService.ConversationEntry(
                        "你都有什么功能？",
                        LocalDateTime.of(2026, 3, 13, 11, 27, 39)
                )));

        LocalSkillFallbackService.LocalSkillResult result = support.tryContextAwareLocalResult(context("我之前第一条消息是什么时候发的"));

        assertThat(result).isNotNull();
        assertThat(result.route()).isEqualTo("SESSION_FIRST_MESSAGE_TIME_QUERY");
        assertThat(result.fallbackAnswer()).contains("2026年3月13日 11:27:39");
    }

    @Test
    void shouldAnswerPreviousMessageTimeQuestionFromHistoryService() {
        when(conversationHistoryService.findLatestUserQuestionEntry("s1"))
                .thenReturn(java.util.Optional.of(new ConversationHistoryService.ConversationEntry(
                        "你现在是什么模型",
                        LocalDateTime.of(2026, 3, 13, 11, 28, 44)
                )));

        LocalSkillFallbackService.LocalSkillResult result = support.tryContextAwareLocalResult(context("我是什么时候发的"));

        assertThat(result).isNotNull();
        assertThat(result.route()).isEqualTo("SESSION_PREVIOUS_MESSAGE_TIME_QUERY");
        assertThat(result.fallbackAnswer()).contains("2026年3月13日 11:28:44");
    }

    @Test
    void shouldExplainRecentDiagnosticFollowUpFromContext() {
        LocalSkillFallbackService.LocalSkillResult result = support.tryContextAwareLocalResult(new AssembledContext(
                "s1",
                "feishu",
                "u1",
                "用代码分析分析他",
                """
                - SYSTEM: tool=FileToolPack.listFiles, status=SUCCESS, phase=ACT, detail=[F] skills/runtime_probe/scripts/run.py
                - SYSTEM: java.net.SocketTimeoutException: Read timed out
                - SYSTEM: tool-session/tool-user
                """,
                "",
                ""
        ));

        assertThat(result).isNotNull();
        assertThat(result.route()).isEqualTo("RECENT_DIAGNOSTIC_ANALYSIS");
        assertThat(result.fallbackAnswer()).contains("工具执行成功后");
        assertThat(result.fallbackAnswer()).contains("响应超时");
        assertThat(result.fallbackAnswer()).doesNotContain("Read timed out");
        assertThat(result.fallbackAnswer()).contains("tool-session/tool-user");
    }

    @Test
    void shouldTreatReadTimedOutAsRecentFailure() {
        LocalSkillFallbackService.LocalSkillResult result = support.tryContextAwareLocalResult(new AssembledContext(
                "s1",
                "feishu",
                "u1",
                "怎么回事",
                "- SYSTEM: java.net.SocketTimeoutException: Read timed out",
                "",
                ""
        ));

        assertThat(result).isNotNull();
        assertThat(result.route()).isEqualTo("RECENT_FAILURE_QUERY");
        assertThat(result.fallbackAnswer()).contains("上游模型超时");
    }

    private AssembledContext context(String question) {
        return new AssembledContext(
                "s1",
                "feishu",
                "u1",
                question,
                "- USER: 你都有什么功能？",
                "- [SESSION] USER: 你都有什么功能？",
                ""
        );
    }
}
