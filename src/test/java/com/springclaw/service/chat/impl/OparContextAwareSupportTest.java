package com.springclaw.service.chat.impl;

import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.files.LocalFilesystemService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OparContextAwareSupportTest {

    private final ConversationHistoryService conversationHistoryService = mock(ConversationHistoryService.class);
    private final MessageEventService messageEventService = mock(MessageEventService.class);
    private final LocalFilesystemService localFilesystemService = mock(LocalFilesystemService.class);
    private final OparContextAwareSupport support = new OparContextAwareSupport(conversationHistoryService, messageEventService, localFilesystemService);

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

    @org.junit.jupiter.api.Test
    void canonicalSourceExtractsFileCandidatesFromDerivedTurns() {
        // T5d(spec 2026-08-18 §3.4): canonical 源从派生 turn 直解析文件候选
        // (payload 存原文,无 [REFLECT] 前缀),不读 message_event
        com.springclaw.runtime.history.ConversationHistoryDeriver deriver =
                org.mockito.Mockito.mock(com.springclaw.runtime.history.ConversationHistoryDeriver.class);
        OparContextAwareSupport canonicalSupport = new OparContextAwareSupport(
                conversationHistoryService, messageEventService, localFilesystemService,
                deriver, "canonical");
        // 窗口语义钉住: 与 legacy 同为最近 4 条
        org.mockito.Mockito.when(deriver.deriveFull(org.mockito.Mockito.eq("s1"),
                        org.mockito.Mockito.eq(4)))
                .thenReturn(java.util.List.of(
                        com.springclaw.runtime.history.ConversationTurn.canonicalUntruncated(
                                com.springclaw.runtime.history.ConversationTurn.Role.ASSISTANT,
                                "找到了这些文件：\n1. report-final.xlsx\n2. summary.docx",
                                "r1", "feishu", "u1",
                                java.time.Instant.parse("2026-08-18T00:00:07Z"))
                ));

        LocalSkillFallbackService.LocalSkillResult result =
                canonicalSupport.tryContextAwareLocalResult(context("好"));

        assertThat(result).isNotNull();
        assertThat(result.route()).isEqualTo("FILE_CONFIRMATION_MULTIPLE");
        assertThat(result.fallbackAnswer()).contains("report-final.xlsx").contains("summary.docx");
        org.mockito.Mockito.verify(messageEventService, org.mockito.Mockito.never())
                .listSessionEvents(org.mockito.Mockito.anyString(), org.mockito.Mockito.any(),
                        org.mockito.Mockito.anyString(), org.mockito.Mockito.anyInt(),
                        org.mockito.Mockito.anyBoolean());
    }

    @org.junit.jupiter.api.Test
    void canonicalSourceFallsBackToLegacyFileCandidatesWhenDeriverThrows() {
        com.springclaw.runtime.history.ConversationHistoryDeriver deriver =
                org.mockito.Mockito.mock(com.springclaw.runtime.history.ConversationHistoryDeriver.class);
        OparContextAwareSupport canonicalSupport = new OparContextAwareSupport(
                conversationHistoryService, messageEventService, localFilesystemService,
                deriver, "canonical");
        org.mockito.Mockito.when(deriver.deriveFull(org.mockito.Mockito.anyString(),
                        org.mockito.Mockito.anyInt()))
                .thenThrow(new IllegalStateException("store down"));
        MessageEvent legacyAssistant = new MessageEvent();
        legacyAssistant.setRole("ASSISTANT");
        legacyAssistant.setEventType("CHAT");
        legacyAssistant.setContent("[REFLECT] 找到了这些文件：\n1. legacy-report.xlsx\n2. legacy-summary.docx");
        org.mockito.Mockito.when(messageEventService.listSessionEvents(
                        org.mockito.Mockito.eq("s1"), org.mockito.Mockito.isNull(),
                        org.mockito.Mockito.eq("CHAT"), org.mockito.Mockito.eq(4),
                        org.mockito.Mockito.eq(false)))
                .thenReturn(java.util.List.of(legacyAssistant));

        LocalSkillFallbackService.LocalSkillResult result =
                canonicalSupport.tryContextAwareLocalResult(context("好"));

        assertThat(result).isNotNull();
        assertThat(result.route()).isEqualTo("FILE_CONFIRMATION_MULTIPLE");
        assertThat(result.fallbackAnswer()).contains("legacy-report.xlsx");
    }

    @org.junit.jupiter.api.Test
    void legacyPathTakesNewestAssistantCandidatesFirst() {
        // legacy 方向修复钉住(2026-08-18): listSessionEvents 降序返回,从最新一条
        // ASSISTANT 开始取候选——两条都带候选时必须命中较新者
        MessageEvent newer = new MessageEvent();
        newer.setRole("ASSISTANT");
        newer.setEventType("CHAT");
        newer.setContent("[REFLECT] 找到了这些文件：\n1. newer-report.xlsx\n2. newer-summary.docx");
        MessageEvent older = new MessageEvent();
        older.setRole("ASSISTANT");
        older.setEventType("CHAT");
        older.setContent("[REFLECT] 找到了这些文件：\n1. older-report.xlsx\n2. older-summary.docx");
        org.mockito.Mockito.when(messageEventService.listSessionEvents(
                        org.mockito.Mockito.eq("s1"), org.mockito.Mockito.isNull(),
                        org.mockito.Mockito.eq("CHAT"), org.mockito.Mockito.eq(4),
                        org.mockito.Mockito.eq(false)))
                .thenReturn(java.util.List.of(newer, older));

        LocalSkillFallbackService.LocalSkillResult result =
                support.tryContextAwareLocalResult(context("好"));

        assertThat(result).isNotNull();
        assertThat(result.route()).isEqualTo("FILE_CONFIRMATION_MULTIPLE");
        assertThat(result.fallbackAnswer()).contains("newer-report.xlsx");
    }

    @org.junit.jupiter.api.Test
    void canonicalSourceFallsBackToLegacyFileCandidatesWhenDeriverReturnsEmpty() {
        // 空回退腿(spec §5.3): canonical 无记录(纯存量会话)→ legacy 候选路径
        com.springclaw.runtime.history.ConversationHistoryDeriver deriver =
                org.mockito.Mockito.mock(com.springclaw.runtime.history.ConversationHistoryDeriver.class);
        OparContextAwareSupport canonicalSupport = new OparContextAwareSupport(
                conversationHistoryService, messageEventService, localFilesystemService,
                deriver, "canonical");
        org.mockito.Mockito.when(deriver.deriveFull(org.mockito.Mockito.eq("s1"),
                        org.mockito.Mockito.eq(4)))
                .thenReturn(java.util.List.of());
        MessageEvent legacyAssistant = new MessageEvent();
        legacyAssistant.setRole("ASSISTANT");
        legacyAssistant.setEventType("CHAT");
        legacyAssistant.setContent("[REFLECT] 找到了这些文件：\n1. legacy-only.xlsx\n2. legacy-second.docx");
        org.mockito.Mockito.when(messageEventService.listSessionEvents(
                        org.mockito.Mockito.eq("s1"), org.mockito.Mockito.isNull(),
                        org.mockito.Mockito.eq("CHAT"), org.mockito.Mockito.eq(4),
                        org.mockito.Mockito.eq(false)))
                .thenReturn(java.util.List.of(legacyAssistant));

        LocalSkillFallbackService.LocalSkillResult result =
                canonicalSupport.tryContextAwareLocalResult(context("好"));

        assertThat(result).isNotNull();
        assertThat(result.route()).isEqualTo("FILE_CONFIRMATION_MULTIPLE");
        assertThat(result.fallbackAnswer()).contains("legacy-only.xlsx");
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
