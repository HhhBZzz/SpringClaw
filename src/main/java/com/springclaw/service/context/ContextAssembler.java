package com.springclaw.service.context;

import com.springclaw.common.util.TextUtils;
import com.springclaw.common.support.ConversationScopeSupport;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.runtime.history.ConversationHistoryDeriver;
import com.springclaw.runtime.history.ConversationTurn;
import com.springclaw.service.chat.ConversationEventTextSupport;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.MemoryBankService;
import com.springclaw.service.memory.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 上下文组装器(展示投影层)。
 *
 * 设计说明(spec 2026-11-09 改革后)：
 * 1. 模型可见上下文的事实源已移交原生消息账本(RunMessageLedger,ChatContextFactory 装配);
 *    本类不再承担"LLM 上下文事实源"职责——observePrompt 保留为人类可读观察视图
 *    (透明支柱产品面)与 legacy prompt 回退路径的渲染素材。
 * 2. legacy 路径(springclaw.runtime.native-context-enabled=false 或账本装配失败)仍由
 *    本类的渲染产物构造 prompt;history 截断/展示规则不变。
 */
@Service
public class ContextAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextAssembler.class);

    private final MessageEventService messageEventService;
    private final MemoryService memoryService;
    private final MemoryBankService memoryBankService;
    private final ConversationHistoryDeriver conversationHistoryDeriver;
    private final String conversationHistorySource;
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
                            @Value("${springclaw.memory.recall-max-chars:400}") int recallMaxChars,
                            ConversationHistoryDeriver conversationHistoryDeriver,
                            @Value("${springclaw.runtime.conversation-history-source:canonical}")
                            String conversationHistorySource) {
        this.messageEventService = messageEventService;
        this.memoryService = memoryService;
        this.memoryBankService = memoryBankService;
        this.conversationHistoryDeriver = conversationHistoryDeriver;
        this.conversationHistorySource = conversationHistorySource;
        this.memoryWindowTurns = Math.max(1, Math.min(memoryWindowSize, 20));
        this.memoryWindowEvents = Math.max(2, this.memoryWindowTurns * 2);
        int safeTopK = Math.max(1, recallTopK);
        this.sessionRecallTopK = safeTopK;
        this.userRecallTopK = Math.max(1, safeTopK / 2);
        this.recallMaxChars = Math.max(120, recallMaxChars);
    }

    public ContextAssembler(MessageEventService messageEventService,
                            MemoryService memoryService,
                            int memoryWindowSize,
                            int recallTopK,
                            int recallMaxChars) {
        this(messageEventService, memoryService, new MemoryBankService(false, "", 400), memoryWindowSize, recallTopK, recallMaxChars);
    }

    public ContextAssembler(MessageEventService messageEventService,
                            MemoryService memoryService,
                            MemoryBankService memoryBankService,
                            int memoryWindowSize,
                            int recallTopK,
                            int recallMaxChars) {
        this(messageEventService, memoryService, memoryBankService, memoryWindowSize,
                recallTopK, recallMaxChars, null, "message-event");
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
        // 历史源切换(spec 2026-08-17-canonical-conversation-history §3.3):
        // canonical → 派生器路径,异常/空结果回退 message_event(双源拼接的最小过渡形态)
        if ("canonical".equals(conversationHistorySource) && conversationHistoryDeriver != null) {
            try {
                List<ConversationTurn> turns =
                        conversationHistoryDeriver.derive(sessionKey, memoryWindowEvents);
                if (!turns.isEmpty()) {
                    return turns.stream()
                            .map(this::renderTurnLine)
                            .filter(StringUtils::hasText)
                            .collect(Collectors.joining("\n"));
                }
            } catch (Exception ex) {
                log.warn("canonical 历史派生失败,回退 message_event: sessionKey={}, reason={}",
                        sessionKey, ex.getMessage());
            }
        }
        List<MessageEvent> events = messageEventService.listRecent(sessionKey, memoryWindowEvents);
        if (events.isEmpty()) {
            return "（暂无短期事件流）";
        }
        return events.stream()
                .map(this::renderEventLine)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n"));
    }

    private String renderTurnLine(ConversationTurn turn) {
        if (turn == null || !StringUtils.hasText(turn.content())) {
            return "";
        }
        String role = switch (turn.role()) {
            case USER -> "USER";
            case ASSISTANT -> "ASSISTANT";
            case SYSTEM -> "SYSTEM";
        };
        return "- " + role + ": " + turn.content();
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
        // 正则抢救退役(spec 2026-11-09 §3.9):原生账本路径下历史以原生消息逐字保留,
        // 不再从渲染 markdown 反解析文件名;本渲染仅服务 legacy 回退与展示投影。
        return "- " + role + ": " + TextUtils.truncate(content, 220);
    }
}
