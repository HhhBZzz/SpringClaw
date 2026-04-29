package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.event.MessageEventService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationHistoryServiceTest {

    private final MessageEventService messageEventService = mock(MessageEventService.class);
    private final ConversationHistoryService historyService = new ConversationHistoryService(messageEventService);

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
