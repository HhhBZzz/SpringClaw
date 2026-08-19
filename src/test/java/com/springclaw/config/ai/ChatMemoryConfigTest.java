package com.springclaw.config.ai;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.runtime.history.ConversationHistoryDeriver;
import com.springclaw.runtime.history.ConversationTurn;
import com.springclaw.service.event.MessageEventService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryConfigTest {

    @Test
    void shouldTreatConfiguredWindowAsConversationTurns() {
        MessageEventService messageEventService = mock(MessageEventService.class);
        when(messageEventService.listSessionEvents("session-1", null, "CHAT", 32, false))
                .thenReturn(buildDescendingChatEvents(10));

        ChatMemory chatMemory = new ChatMemoryConfig().messageEventChatMemory(messageEventService, 8);

        List<Message> messages = chatMemory.get("session-1");

        assertThat(messages).hasSize(16);
        assertThat(messages.get(0).getText()).isEqualTo("第3轮问题");
        assertThat(messages.get(15).getText()).isEqualTo("第10轮回答");
    }

    private List<MessageEvent> buildDescendingChatEvents(int turns) {
        List<MessageEvent> events = new ArrayList<>();
        for (int i = turns; i >= 1; i--) {
            events.add(event("ASSISTANT", "[REFLECT] 第" + i + "轮回答"));
            events.add(event("USER", """
                    [OBSERVE] # 当前问题
                    第%s轮问题

                    # 短期会话上下文（事件流）
                    ...
                    """.formatted(i)));
        }
        return events;
    }

    private MessageEvent event(String role, String content) {
        MessageEvent event = new MessageEvent();
        event.setRole(role);
        event.setContent(content);
        return event;
    }

    @Test
    void canonicalSourceReadsTurnsFromDeriver() {
        // T5d(spec 2026-08-18 §3.4): canonical 源从派生器读,不碰 message_event;
        // 内容提取与 legacy 对齐(USER 剥 OBSERVE 信封)
        MessageEventService messageEventService = mock(MessageEventService.class);
        ConversationHistoryDeriver deriver = mock(ConversationHistoryDeriver.class);
        // 窗口语义钉住: memoryWindowMessages = max(2, 8*2) = 16
        when(deriver.deriveFull(eq("session-1"), eq(16))).thenReturn(List.of(
                ConversationTurn.canonicalUntruncated(ConversationTurn.Role.USER,
                        "[OBSERVE] # 当前问题\n第1轮问题\n\n# 短期会话上下文（事件流）\n...",
                        "r1", "api", "u1", Instant.parse("2026-08-18T00:00:01Z")),
                ConversationTurn.canonicalUntruncated(ConversationTurn.Role.ASSISTANT,
                        "第1轮回答", "r1", "api", "u1", Instant.parse("2026-08-18T00:00:07Z"))
        ));

        ChatMemory chatMemory = new ChatMemoryConfig()
                .messageEventChatMemory(messageEventService, 8, deriver, "canonical");
        List<Message> messages = chatMemory.get("session-1");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getText()).isEqualTo("第1轮问题");
        assertThat(messages.get(1).getText()).isEqualTo("第1轮回答");
        verify(messageEventService, never()).listSessionEvents(
                anyString(), any(), anyString(), anyInt(), anyBoolean());
    }

    @Test
    void canonicalSourceFallsBackToLegacyWhenDeriverThrows() {
        MessageEventService messageEventService = mock(MessageEventService.class);
        ConversationHistoryDeriver deriver = mock(ConversationHistoryDeriver.class);
        when(deriver.deriveFull(anyString(), anyInt())).thenThrow(new IllegalStateException("store down"));
        when(messageEventService.listSessionEvents("session-1", null, "CHAT", 32, false))
                .thenReturn(buildDescendingChatEvents(2));

        ChatMemory chatMemory = new ChatMemoryConfig()
                .messageEventChatMemory(messageEventService, 8, deriver, "canonical");
        List<Message> messages = chatMemory.get("session-1");

        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).getText()).isEqualTo("第1轮问题");
    }

    @Test
    void canonicalSourceFallsBackToLegacyWhenDeriverReturnsEmpty() {
        // 空回退腿(spec §5.3): 纯存量会话 canonical 无记录 → legacy 读
        MessageEventService messageEventService = mock(MessageEventService.class);
        ConversationHistoryDeriver deriver = mock(ConversationHistoryDeriver.class);
        when(deriver.deriveFull(anyString(), anyInt())).thenReturn(List.of());
        when(messageEventService.listSessionEvents("session-1", null, "CHAT", 32, false))
                .thenReturn(buildDescendingChatEvents(1));

        ChatMemory chatMemory = new ChatMemoryConfig()
                .messageEventChatMemory(messageEventService, 8, deriver, "canonical");
        List<Message> messages = chatMemory.get("session-1");

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).getText()).isEqualTo("第1轮问题");
    }
}
