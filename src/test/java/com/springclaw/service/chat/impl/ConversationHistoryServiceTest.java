package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.runtime.history.ConversationHistoryDeriver;
import com.springclaw.runtime.history.ConversationTurn;
import com.springclaw.service.event.MessageEventService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationHistoryServiceTest {

    private final MessageEventService messageEventService = mock(MessageEventService.class);
    private final ConversationHistoryDeriver deriver = mock(ConversationHistoryDeriver.class);
    private final ConversationHistoryService historyService = new ConversationHistoryService(messageEventService);

    private ConversationHistoryService canonicalService() {
        return new ConversationHistoryService(messageEventService, deriver, "canonical");
    }

    private static ConversationTurn userTurn(String content, String runId, Instant at) {
        return ConversationTurn.canonicalUntruncated(
                ConversationTurn.Role.USER, content, runId, "api", "u1", at);
    }

    @Test
    void canonicalSourceReadsFromDeriverAndUnwrapsObserveEnvelope() {
        // T5d(spec 2026-08-18 §3.4): canonical 源直读派生器,且与 legacy 一样剥 [OBSERVE] 信封。
        // limit 语义钉住: findFirst 传 2000*2=4000(USER 占半凑满),findLatest 传 50*2=100
        when(deriver.deriveFull(eq("s1"), eq(4000))).thenReturn(List.of(
                userTurn("[OBSERVE] # 当前问题\n你都有什么功能？\n\n# 短期会话上下文（事件流）\n- SYSTEM: ...",
                        "r1", Instant.parse("2026-08-18T00:00:01Z")),
                userTurn("第二个问题", "r2", Instant.parse("2026-08-18T00:01:00Z"))
        ));
        when(deriver.deriveFull(eq("s1"), eq(100))).thenReturn(List.of(
                userTurn("[OBSERVE] # 当前问题\n你都有什么功能？\n\n# 短期会话上下文（事件流）\n- SYSTEM: ...",
                        "r1", Instant.parse("2026-08-18T00:00:01Z")),
                userTurn("第二个问题", "r2", Instant.parse("2026-08-18T00:01:00Z"))
        ));

        assertThat(canonicalService().findFirstUserQuestion("s1")).contains("你都有什么功能？");
        assertThat(canonicalService().findLatestUserQuestion("s1")).contains("第二个问题");
        verify(messageEventService, never()).listSessionEvents(
                anyString(), anyString(), anyString(), anyInt(), eq(true));
    }

    @Test
    void canonicalSourceFallsBackToLegacyWhenDeriverThrows() {
        when(deriver.deriveFull(anyString(), anyInt())).thenThrow(new IllegalStateException("store down"));
        when(messageEventService.listSessionEvents("s1", "USER", "CHAT", 50, false))
                .thenReturn(List.of(observeEvent("在么")));

        assertThat(canonicalService().findLatestUserQuestion("s1")).contains("在么");
    }

    @Test
    void canonicalSourceFallsBackToLegacyWhenDeriverReturnsEmpty() {
        when(deriver.deriveFull(anyString(), anyInt())).thenReturn(List.of());
        when(messageEventService.listSessionEvents("s1", "USER", "CHAT", 50, false))
                .thenReturn(List.of(observeEvent("在么")));

        assertThat(canonicalService().findLatestUserQuestion("s1")).contains("在么");
    }

    @Test
    void canonicalSourceCountsUserTurns() {
        // 计数窗口语义钉住: derive(eq(400))——CANONICAL_COUNT_LIMIT
        when(deriver.derive(eq("s1"), eq(400))).thenReturn(List.of(
                userTurn("q1", "r1", Instant.parse("2026-08-18T00:00:01Z")),
                ConversationTurn.canonical(ConversationTurn.Role.ASSISTANT, "a1", "r1",
                        "api", "u1", Instant.parse("2026-08-18T00:00:02Z")),
                userTurn("q2", "r2", Instant.parse("2026-08-18T00:01:00Z"))
        ));

        assertThat(canonicalService().countRememberedUserQuestions("s1")).isEqualTo(2L);
        verify(messageEventService, never()).countSessionEvents(anyString(), anyString(), anyString());
    }

    @Test
    void canonicalCountFallsBackToLegacyWhenDeriverThrows() {
        // 计数路径异常回退腿(spec §5.3)
        when(deriver.derive(anyString(), anyInt())).thenThrow(new IllegalStateException("store down"));
        when(messageEventService.countSessionEvents("s1", "USER", "CHAT")).thenReturn(6L);

        assertThat(canonicalService().countRememberedUserQuestions("s1")).isEqualTo(6L);
    }

    @Test
    void shouldExtractFirstUserQuestionFromObservedEvent() {
        when(messageEventService.listSessionEvents("s1", "USER", "CHAT", 2000, true))
                .thenReturn(List.of(observeEvent("""
                        [OBSERVE] # 当前问题
                        你都有什么功能？

                        # 短期会话上下文（事件流）
                        - SYSTEM: ...
                        """)));

        assertThat(historyService.findFirstUserQuestion("s1"))
                .contains("你都有什么功能？");
    }

    @Test
    void shouldReturnLatestUserQuestionFromRecentChatEvent() {
        when(messageEventService.listSessionEvents("s1", "USER", "CHAT", 50, false))
                .thenReturn(List.of(observeEvent("""
                        [OBSERVE] # 当前问题
                        我之前问你的第一个消息是什么

                        # 短期会话上下文（事件流）
                        - SYSTEM: ...
                        """)));

        assertThat(historyService.findLatestUserQuestion("s1"))
                .contains("我之前问你的第一个消息是什么");
    }

    @Test
    void shouldCountRememberedUserQuestions() {
        when(messageEventService.countSessionEvents("s1", "USER", "CHAT")).thenReturn(6L);

        assertThat(historyService.countRememberedUserQuestions("s1")).isEqualTo(6L);
    }

    @Test
    void shouldReturnRawUserQuestionWithoutObserveEnvelope() {
        when(messageEventService.listSessionEvents("s1", "USER", "CHAT", 50, false))
                .thenReturn(List.of(observeEvent("在么")));

        assertThat(historyService.findLatestUserQuestion("s1"))
                .contains("在么");
    }

    private MessageEvent observeEvent(String content) {
        MessageEvent event = new MessageEvent();
        event.setContent(content);
        event.setRole("USER");
        event.setEventType("CHAT");
        return event;
    }
}
