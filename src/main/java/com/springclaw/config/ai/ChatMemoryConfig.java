package com.springclaw.config.ai;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.chat.ConversationEventTextSupport;
import com.springclaw.service.event.MessageEventService;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Chat Memory Advisor 配置。
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory messageEventChatMemory(MessageEventService messageEventService,
                                             @Value("${springclaw.chat.memory-window-size:8}") int memoryWindowSize) {
        return new MessageEventChatMemory(messageEventService, Math.max(1, Math.min(memoryWindowSize, 20)));
    }

    @Bean
    public MessageChatMemoryAdvisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory)
                .conversationId("default")
                .build();
    }

    private static final class MessageEventChatMemory implements ChatMemory {

        private final MessageEventService messageEventService;
        private final int memoryWindowTurns;
        private final int memoryWindowMessages;

        private MessageEventChatMemory(MessageEventService messageEventService, int memoryWindowSize) {
            this.messageEventService = messageEventService;
            this.memoryWindowTurns = memoryWindowSize;
            this.memoryWindowMessages = Math.max(2, memoryWindowSize * 2);
        }

        @Override
        public void add(String conversationId, List<Message> messages) {
            // 当前项目已有独立的 message_event / memory 持久化链路，Advisor 这里只负责读取最近会话消息，避免重复写入。
        }

        @Override
        public List<Message> get(String conversationId) {
            if (!StringUtils.hasText(conversationId)) {
                return List.of();
            }
            List<MessageEvent> events = messageEventService.listSessionEvents(
                    conversationId,
                    null,
                    "CHAT",
                    Math.max(20, memoryWindowTurns * 4),
                    false
            );
            if (events.isEmpty()) {
                return List.of();
            }
            Collections.reverse(events);
            List<Message> messages = new ArrayList<>();
            for (MessageEvent event : events) {
                Message message = toChatMessage(event);
                if (message != null) {
                    messages.add(message);
                }
            }
            if (messages.size() <= memoryWindowMessages) {
                return messages;
            }
            return messages.subList(messages.size() - memoryWindowMessages, messages.size());
        }

        @Override
        public void clear(String conversationId) {
            // 不在 Advisor 层删除持久化会话历史。
        }

        private Message toChatMessage(MessageEvent event) {
            if (event == null || !StringUtils.hasText(event.getContent())) {
                return null;
            }
            String role = StringUtils.hasText(event.getRole()) ? event.getRole().trim().toUpperCase() : "";
            return switch (role) {
                case "USER" -> {
                    String question = ConversationEventTextSupport.extractUserQuestion(event.getContent());
                    yield StringUtils.hasText(question) ? new UserMessage(question) : null;
                }
                case "ASSISTANT" -> {
                    String answer = ConversationEventTextSupport.extractAssistantAnswer(event.getContent());
                    yield StringUtils.hasText(answer) ? new AssistantMessage(answer) : null;
                }
                default -> null;
            };
        }
    }
}
