package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.runtime.history.ConversationHistoryDeriver;
import com.springclaw.runtime.history.ConversationTurn;
import com.springclaw.service.chat.ConversationEventTextSupport;
import com.springclaw.service.event.MessageEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 会话历史查询服务。
 *
 * 设计说明：
 * 1. 为“第一条消息/上一条消息/你记住了什么”提供真实历史读取能力，避免让模型猜测。
 * 2. 历史源切换(spec 2026-08-18-message-event-chat-write-closure §3.4):
 *    springclaw.runtime.conversation-history-source=canonical(默认)时从 canonical
 *    事件日志派生,派生异常/空结果回退 message_event 原路径(打 WARN,不 fail)。
 *    canonical 派生是最近窗口语义(runScanLimit≤200):"第一条消息"在超长会话
 *    退化为"窗口内第一条"——精确全文首条依赖尚未实现的 findEventsBySession。
 */
@Service
class ConversationHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationHistoryService.class);
    /** canonical 计数窗口: USER 问题计数按最近 400 条对话 turn 计(约 200 run)。 */
    private static final int CANONICAL_COUNT_LIMIT = 400;

    private final MessageEventService messageEventService;
    private final ConversationHistoryDeriver conversationHistoryDeriver;
    private final String conversationHistorySource;

    @Autowired
    ConversationHistoryService(
            MessageEventService messageEventService,
            ConversationHistoryDeriver conversationHistoryDeriver,
            @Value("${springclaw.runtime.conversation-history-source:canonical}")
            String conversationHistorySource) {
        this.messageEventService = messageEventService;
        this.conversationHistoryDeriver = conversationHistoryDeriver;
        this.conversationHistorySource = conversationHistorySource;
    }

    ConversationHistoryService(MessageEventService messageEventService) {
        this(messageEventService, null, "message-event");
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
        if (canonicalActive()) {
            try {
                List<ConversationTurn> turns =
                        conversationHistoryDeriver.derive(sessionKey, CANONICAL_COUNT_LIMIT);
                if (!turns.isEmpty()) {
                    return turns.stream()
                            .filter(turn -> turn.role() == ConversationTurn.Role.USER)
                            .count();
                }
            } catch (Exception ex) {
                log.warn("canonical 历史派生失败,回退 message_event 计数: sessionKey={}, reason={}",
                        sessionKey, ex.getMessage());
            }
        }
        return messageEventService.countSessionEvents(sessionKey, "USER", "CHAT");
    }

    private List<ConversationEntry> listUserChatEntries(String sessionKey, int limit, boolean ascending) {
        if (canonicalActive()) {
            try {
                // USER turn 约占一半,limit*2 保证凑满;deriveFull 不截断(精确查询要全文)
                List<ConversationTurn> turns =
                        conversationHistoryDeriver.deriveFull(sessionKey, limit * 2);
                if (!turns.isEmpty()) {
                    List<ConversationEntry> entries = new ArrayList<>(turns.stream()
                            .filter(turn -> turn.role() == ConversationTurn.Role.USER)
                            .map(turn -> new ConversationEntry(
                                    extractQuestion(turn.content()),
                                    toLocalDateTime(turn.at())
                            ))
                            .filter(entry -> StringUtils.hasText(entry.question()))
                            .distinct()
                            .toList());
                    if (!ascending) {
                        Collections.reverse(entries);
                    }
                    return entries.size() > limit ? entries.subList(0, limit) : entries;
                }
            } catch (Exception ex) {
                log.warn("canonical 历史派生失败,回退 message_event: sessionKey={}, reason={}",
                        sessionKey, ex.getMessage());
            }
        }
        return messageEventService.listSessionEvents(sessionKey, "USER", "CHAT", limit, ascending).stream()
                .map(event -> new ConversationEntry(
                        extractQuestion(event.getContent()),
                        event.getCreateTime()
                ))
                .filter(entry -> StringUtils.hasText(entry.question()))
                .distinct()
                .toList();
    }

    private boolean canonicalActive() {
        return "canonical".equals(conversationHistorySource) && conversationHistoryDeriver != null;
    }

    private static LocalDateTime toLocalDateTime(Instant at) {
        return at == null ? null : LocalDateTime.ofInstant(at, ZoneId.systemDefault());
    }

    private String extractQuestion(String content) {
        return ConversationEventTextSupport.extractUserQuestion(content);
    }

    record ConversationEntry(String question, LocalDateTime createdAt) {
    }
}
