package com.springclaw.service.context;

import com.springclaw.common.support.ConversationScopeSupport;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.chat.ConversationEventTextSupport;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.MemoryService;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 上下文组装器。
 *
 * 设计说明：
 * 1. 保留一层轻量上下文摘要，给 OPAR/降级链路提供可读观察视图。
 * 2. 真正的消息记忆由 Spring AI ChatMemory Advisor 管理，这里不再手工承担完整上下文窗口。
 */
@Service
public class ContextAssembler {

    private final MessageEventService messageEventService;
    private final MemoryService memoryService;
    private final int memoryWindowTurns;
    private final int memoryWindowEvents;
    private final int sessionRecallTopK;
    private final int userRecallTopK;
    private final int recallMaxChars;

    public ContextAssembler(MessageEventService messageEventService,
                            MemoryService memoryService,
                            @Value("${springclaw.chat.memory-window-size:8}") int memoryWindowSize,
                            @Value("${springclaw.memory.recall-top-k:8}") int recallTopK,
                            @Value("${springclaw.memory.recall-max-chars:400}") int recallMaxChars) {
        this.messageEventService = messageEventService;
        this.memoryService = memoryService;
        this.memoryWindowTurns = Math.max(1, Math.min(memoryWindowSize, 20));
        this.memoryWindowEvents = Math.max(2, this.memoryWindowTurns * 2);
        int safeTopK = Math.max(1, recallTopK);
        this.sessionRecallTopK = safeTopK;
        this.userRecallTopK = Math.max(1, safeTopK / 2);
        this.recallMaxChars = Math.max(120, recallMaxChars);
    }

    public AssembledContext assemble(String sessionKey,
                                     String channel,
                                     String userId,
                                     String question) {
        String eventContext = buildEventContext(sessionKey);
        String semanticContext = buildSemanticContext(sessionKey, userId, question);

        String observePrompt = """
                # 当前问题
                %s

                # 短期会话上下文（事件流）
                %s

                # 长期语义记忆（同会话优先）
                %s
                """.formatted(
                safe(question),
                safe(eventContext),
                safe(semanticContext)
        );

        return new AssembledContext(
                sessionKey,
                channel,
                userId,
                question,
                eventContext,
                semanticContext,
                observePrompt
        );
    }

    private String buildEventContext(String sessionKey) {
        List<MessageEvent> events = messageEventService.listRecent(sessionKey, memoryWindowEvents);
        if (events.isEmpty()) {
            return "（暂无短期事件流）";
        }
        return events.stream()
                .map(this::renderEventLine)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n"));
    }

    private String buildSemanticContext(String sessionKey, String userId, String question) {
        List<Document> sessionDocs = memoryService.recallBySession(sessionKey, question, sessionRecallTopK);
        List<Document> userDocs = ConversationScopeSupport.shouldUseCrossSessionUserMemory(sessionKey)
                ? memoryService.recallByUser(userId, question, userRecallTopK)
                : List.of();

        Set<String> lines = new LinkedHashSet<>();
        for (Document doc : sessionDocs) {
            lines.add(renderDoc("SESSION", doc));
        }
        for (Document doc : userDocs) {
            String docSession = String.valueOf(doc.getMetadata().getOrDefault("sessionKey", ""));
            if (!sessionKey.equals(docSession)) {
                lines.add(renderDoc("USER", doc));
            }
        }

        List<String> filtered = new ArrayList<>(lines).stream()
                .filter(StringUtils::hasText)
                .limit(10)
                .toList();

        if (filtered.isEmpty()) {
            return "（暂无长期语义记忆）";
        }
        return String.join("\n", filtered);
    }

    private String renderDoc(String scope, Document doc) {
        String channel = String.valueOf(doc.getMetadata().getOrDefault("channel", "unknown"));
        String sessionKey = String.valueOf(doc.getMetadata().getOrDefault("sessionKey", ""));
        String userId = String.valueOf(doc.getMetadata().getOrDefault("userId", ""));
        String role = ConversationScopeSupport.renderSpeakerRole(
                channel,
                sessionKey,
                String.valueOf(doc.getMetadata().getOrDefault("role", "UNKNOWN")),
                userId
        );
        String text = truncate(safe(doc.getText()), recallMaxChars);
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return "- [" + scope + "] " + role + ": " + text;
    }

    private String renderEventLine(MessageEvent event) {
        if (event == null) {
            return "";
        }
        String role = ConversationScopeSupport.renderSpeakerRole(
                event.getChannel(),
                event.getSessionKey(),
                safe(event.getRole()),
                event.getUserId()
        );
        String eventType = safe(event.getEventType()).toUpperCase();
        String content;
        if (role.startsWith("USER")) {
            content = "CHAT".equals(eventType)
                    ? ConversationEventTextSupport.extractUserQuestion(event.getContent())
                    : safe(event.getContent());
        } else if ("ASSISTANT".equals(role)) {
            content = "CHAT".equals(eventType)
                    ? ConversationEventTextSupport.extractAssistantAnswer(event.getContent())
                    : safe(event.getContent());
        } else {
            content = safe(event.getContent());
        }
        if (!StringUtils.hasText(content)) {
            return "";
        }
        return "- " + role + ": " + truncate(content, 220);
    }

    private String truncate(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private String safe(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replaceAll("\\s+", " ").trim();
    }
}
