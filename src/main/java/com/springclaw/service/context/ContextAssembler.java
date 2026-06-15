package com.springclaw.service.context;

import com.springclaw.common.util.TextUtils;
import com.springclaw.common.support.ConversationScopeSupport;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.chat.ConversationEventTextSupport;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.MemoryBankService;
import com.springclaw.service.memory.MemoryService;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final MemoryBankService memoryBankService;
    private final int memoryWindowTurns;
    private final int memoryWindowEvents;
    private final int sessionRecallTopK;
    private final int userRecallTopK;
    private final int recallMaxChars;

    @Autowired
    public ContextAssembler(MessageEventService messageEventService,
                            MemoryService memoryService,
                            MemoryBankService memoryBankService,
                            @Value("${springclaw.chat.memory-window-size:8}") int memoryWindowSize,
                            @Value("${springclaw.memory.recall-top-k:8}") int recallTopK,
                            @Value("${springclaw.memory.recall-max-chars:400}") int recallMaxChars) {
        this.messageEventService = messageEventService;
        this.memoryService = memoryService;
        this.memoryBankService = memoryBankService;
        this.memoryWindowTurns = Math.max(1, Math.min(memoryWindowSize, 20));
        this.memoryWindowEvents = Math.max(2, this.memoryWindowTurns * 2);
        int safeTopK = Math.max(1, recallTopK);
        this.sessionRecallTopK = safeTopK;
        this.userRecallTopK = Math.max(1, safeTopK / 2);
        this.recallMaxChars = Math.max(120, recallMaxChars);
    }

    public ContextAssembler(MessageEventService messageEventService,
                            MemoryService memoryService,
                            @Value("${springclaw.chat.memory-window-size:8}") int memoryWindowSize,
                            @Value("${springclaw.memory.recall-top-k:8}") int recallTopK,
                            @Value("${springclaw.memory.recall-max-chars:400}") int recallMaxChars) {
        this(messageEventService, memoryService, new MemoryBankService(false, "", 400), memoryWindowSize, recallTopK, recallMaxChars);
    }

    public AssembledContext assemble(String sessionKey,
                                     String channel,
                                     String userId,
                                     String question) {
        String eventContext = buildEventContext(sessionKey);
        String semanticContext = buildSemanticContext(sessionKey, userId, question);
        MemoryBankService.MemoryBankSnapshot projectMemory = memoryBankService.renderSnapshot();
        String projectMemoryContext = projectMemory.context();

        String observePrompt = """
                # 当前问题
                %s

                # 项目记忆（Memory Bank）
                %s

                # 短期会话上下文（事件流）
                %s

                # 长期语义记忆（同会话优先）
                %s
                """.formatted(
                TextUtils.normalizeWS(question),
                TextUtils.normalizeWS(projectMemoryContext),
                TextUtils.normalizeWS(eventContext),
                TextUtils.normalizeWS(semanticContext)
        );

        return new AssembledContext(
                sessionKey,
                channel,
                userId,
                question,
                eventContext,
                semanticContext,
                observePrompt,
                projectMemory.activeLearningCount(),
                projectMemory.filteredLearningCount()
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
        String text = TextUtils.truncate(TextUtils.normalizeWS(doc.getText()), recallMaxChars);
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
                TextUtils.normalizeWS(event.getRole()),
                event.getUserId()
        );
        String eventType = TextUtils.normalizeWS(event.getEventType()).toUpperCase();
        String content;
        if (role.startsWith("USER")) {
            content = "CHAT".equals(eventType)
                    ? ConversationEventTextSupport.extractUserQuestion(event.getContent())
                    : TextUtils.normalizeWS(event.getContent());
        } else if ("ASSISTANT".equals(role)) {
            content = "CHAT".equals(eventType)
                    ? ConversationEventTextSupport.extractAssistantAnswer(event.getContent())
                    : TextUtils.normalizeWS(event.getContent());
        } else {
            content = TextUtils.normalizeWS(event.getContent());
        }
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String baseLine = "- " + role + ": " + TextUtils.truncate(content, 220);
        // 对 ASSISTANT 的文件列表回答，提取文件名候选并附加（限制：最多20个文件名，单个名过长截断）
        if ("ASSISTANT".equals(role) && looksLikeFileListing(content)) {
            String fileSummary = extractFileNamesFromListing(content);
            if (StringUtils.hasText(fileSummary)) {
                baseLine += "\n  文件候选: " + fileSummary;
            }
        }
        return baseLine;
    }

    /**
     * 判断 assistant 回答是否包含文件列表（表格或编号列表格式）。
     */
    private boolean looksLikeFileListing(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        return content.contains("| 序号 |") || content.contains("| 文件名 |")
                || content.contains("[F] ") || content.matches("(?s).*\\d+\\.\\s+\\S.{2,}.*");
    }

    /**
     * 从文件列表回答中提取文件名摘要，最多20个，单个文件名过长截断。
     */
    private String extractFileNamesFromListing(String content) {
        List<String> names = new ArrayList<>();
        // 匹配 [F] xxx 格式
        java.util.regex.Matcher fileMarkerMatcher = java.util.regex.Pattern.compile("\\[F\\]\\s*(.+?)(?:\\n|$)")
                .matcher(content);
        while (fileMarkerMatcher.find() && names.size() < 20) {
            String name = extractFileNameFromPath(fileMarkerMatcher.group(1).trim());
            if (StringUtils.hasText(name) && name.length() >= 3) {
                names.add(truncateFileName(name));
            }
        }
        // 匹配表格第三列 | 文件名 |
        if (names.isEmpty()) {
            java.util.regex.Matcher tableMatcher = java.util.regex.Pattern.compile(
                    "\\|\\s*\\d+\\s*\\|\\s*\\S+?\\s*\\|\\s*(.+?)\\s*\\|")
                    .matcher(content);
            while (tableMatcher.find() && names.size() < 20) {
                String name = tableMatcher.group(1).trim();
                if (StringUtils.hasText(name) && name.length() >= 3
                        && !"文件".equals(name) && !"文件夹".equals(name)) {
                    names.add(truncateFileName(name));
                }
            }
        }
        // 匹配编号列表 1. xxx
        if (names.isEmpty()) {
            java.util.regex.Matcher listMatcher = java.util.regex.Pattern.compile(
                    "\\d+\\.\\s+(.+?)(?:\\n|$)")
                    .matcher(content);
            while (listMatcher.find() && names.size() < 20) {
                String name = listMatcher.group(1).trim();
                if (StringUtils.hasText(name) && name.length() >= 3) {
                    names.add(truncateFileName(name));
                }
            }
        }
        if (names.isEmpty()) {
            return "";
        }
        return String.join(", ", names);
    }

    private String extractFileNameFromPath(String path) {
        int slash = path.lastIndexOf('/');
        if (slash >= 0 && slash < path.length() - 1) {
            return path.substring(slash + 1);
        }
        int colon = path.indexOf(':');
        if (colon >= 0 && colon < path.length() - 1) {
            String afterColon = path.substring(colon + 1);
            int slashAfter = afterColon.lastIndexOf('/');
            if (slashAfter >= 0 && slashAfter < afterColon.length() - 1) {
                return afterColon.substring(slashAfter + 1);
            }
            return afterColon;
        }
        return path;
    }

    private String truncateFileName(String name) {
        if (name == null) return "";
        return name.length() > 60 ? name.substring(0, 60) + "..." : name;
    }
}
