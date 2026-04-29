package com.springclaw.service.chat.impl;

import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.MemoryService;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.session.AgentSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 对话结果持久化组件，负责保存对话结果到会话、记忆和事件流。
 */
@Component
public class ChatResultPersister {

    private static final Logger log = LoggerFactory.getLogger(ChatResultPersister.class);

    private final AgentSessionService agentSessionService;
    private final MemoryService memoryService;
    private final MessageEventService messageEventService;
    private final SoulPromptService soulPromptService;

    public ChatResultPersister(AgentSessionService agentSessionService,
                               MemoryService memoryService,
                               MessageEventService messageEventService,
                               SoulPromptService soulPromptService) {
        this.agentSessionService = agentSessionService;
        this.memoryService = memoryService;
        this.messageEventService = messageEventService;
        this.soulPromptService = soulPromptService;
    }

    public void persist(ChatContext context,
                        String assistantMessage,
                        ChatExecutionResult executionResult) {
        agentSessionService.persistConversation(
                context.session(),
                context.effectiveUserMessage(),
                assistantMessage,
                soulPromptService.soulVersion()
        );
        memoryService.storeConversationTurn(
                context.session().getSessionKey(),
                context.channel(),
                context.userId(),
                context.effectiveUserMessage(),
                normalizeAssistantForMemory(assistantMessage)
        );
        messageEventService.recordTurn(
                context.session().getSessionKey(),
                context.channel(),
                context.userId(),
                context.effectiveUserMessage(),
                "[REFLECT] " + truncate(normalizeAssistantForMemory(assistantMessage), 1600),
                "CHAT",
                context.requestId()
        );
        messageEventService.recordSingle(
                context.session().getSessionKey(),
                context.channel(),
                context.userId(),
                "SYSTEM",
                "OPAR",
                "ROUTING=mode=%s, role=%s, reason=%s".formatted(
                        context.executionMode(),
                        context.roleCode(),
                        truncate(context.routingReason(), 300)
                ),
                context.requestId()
        );
        messageEventService.recordSingle(
                context.session().getSessionKey(),
                context.channel(),
                context.userId(),
                "SYSTEM",
                "OPAR",
                "PLAN=" + truncate(executionResult.plan(), 1200),
                context.requestId()
        );
        messageEventService.recordSingle(
                context.session().getSessionKey(),
                context.channel(),
                context.userId(),
                "SYSTEM",
                "OPAR",
                "ACT=" + truncate(executionResult.action(), 1200),
                context.requestId()
        );
    }

    private String normalizeAssistantForMemory(String answer) {
        if (!StringUtils.hasText(answer)) {
            return "";
        }
        if (answer.startsWith("当前已进入降级模式")) {
            return "系统处于降级模式，返回了兜底响应。";
        }
        return answer;
    }

    private String truncate(String text, int maxLen) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
