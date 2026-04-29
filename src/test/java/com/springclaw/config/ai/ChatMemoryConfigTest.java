package com.springclaw.config.ai;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.event.MessageEventService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
}
