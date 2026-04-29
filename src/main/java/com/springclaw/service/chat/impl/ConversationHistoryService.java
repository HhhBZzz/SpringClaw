package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.chat.ConversationEventTextSupport;
import com.springclaw.service.event.MessageEventService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

/**
 * 会话历史查询服务。
 *
 * 设计说明：
 * 1. 为“第一条消息/上一条消息/你记住了什么”提供真实历史读取能力，避免让模型猜测。
 * 2. 基于 message_event 做精确查询，并解析 OPAR 包装后的 OBSERVE 文本拿到原始问题。
 */
@Service
class ConversationHistoryService {

    private final MessageEventService messageEventService;

    ConversationHistoryService(MessageEventService messageEventService) {
        this.messageEventService = messageEventService;
    }

    Optional<String> findFirstUserQuestion(String sessionKey) {
        return findFirstUserQuestionEntry(sessionKey).map(ConversationEntry::question);
    }

    Optional<String> findLatestUserQuestion(String sessionKey) {
        return findLatestUserQuestionEntry(sessionKey).map(ConversationEntry::question);
    }

    Optional<ConversationEntry> findFirstUserQuestionEntry(String sessionKey) {
        return listUserChatEntries(sessionKey, 2000, true).stream().findFirst();
    }

    Optional<ConversationEntry> findLatestUserQuestionEntry(String sessionKey) {
        return listUserChatEntries(sessionKey, 50, false).stream().findFirst();
    }

    long countRememberedUserQuestions(String sessionKey) {
        return messageEventService.countSessionEvents(sessionKey, "USER", "CHAT");
    }

    private List<ConversationEntry> listUserChatEntries(String sessionKey, int limit, boolean ascending) {
        return messageEventService.listSessionEvents(sessionKey, "USER", "CHAT", limit, ascending).stream()
                .map(event -> new ConversationEntry(
                        extractQuestion(event.getContent()),
                        event.getCreateTime()
                ))
                .filter(entry -> StringUtils.hasText(entry.question()))
                .distinct()
                .toList();
    }

    private String extractQuestion(String content) {
        return ConversationEventTextSupport.extractUserQuestion(content);
    }

    record ConversationEntry(String question, LocalDateTime createdAt) {
    }
}
