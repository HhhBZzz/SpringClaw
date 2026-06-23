package com.springclaw.service.chat.impl;

import com.springclaw.common.util.TextUtils;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.event.MessageEventWrite;
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
                        ChatExecutionResult executionResult,
                        ChatPersistenceIntent intent) {
        if (intent == ChatPersistenceIntent.CONFIRMATION_SUSPENSION) {
            persistSuspension(context, assistantMessage, executionResult);
            return;
        }
        persistTerminal(context, assistantMessage, executionResult);
    }

    private void persistTerminal(ChatContext context,
                                 String assistantMessage,
                                 ChatExecutionResult executionResult) {
        String sessionKey = context.session().getSessionKey();
        String channel = context.channel();
        String userId = context.userId();
        String requestId = context.requestId();
        agentSessionService.persistConversation(
                context.session(),
                context.effectiveUserMessage(),
                assistantMessage,
                soulPromptService.soulVersion()
        );
        memoryService.storeConversationTurn(
                sessionKey,
                channel,
                userId,
                context.effectiveUserMessage(),
                normalizeAssistantForMemory(assistantMessage)
        );
        messageEventService.append(new MessageEventWrite(
                "chat:" + requestId + ":user", sessionKey, channel, userId,
                "USER", "CHAT", context.effectiveUserMessage(), requestId));
        messageEventService.append(new MessageEventWrite(
                "chat:" + requestId + ":assistant:terminal", sessionKey, channel, userId,
                "ASSISTANT", "CHAT",
                "[REFLECT] " + TextUtils.truncate(normalizeAssistantForMemory(assistantMessage), 1600),
                requestId));
        messageEventService.recordSingle(
                sessionKey, channel, userId, "SYSTEM", "OPAR",
                "ROUTING=mode=%s, role=%s, reason=%s".formatted(
                        context.executionMode(),
                        context.roleCode(),
                        TextUtils.truncate(context.routingReason(), 300)
                ),
                requestId
        );
        messageEventService.recordSingle(
                sessionKey, channel, userId, "SYSTEM", "OPAR",
                "PLAN=" + TextUtils.truncate(executionResult.plan(), 1200),
                requestId
        );
        messageEventService.recordSingle(
                sessionKey, channel, userId, "SYSTEM", "OPAR",
                "ACT=" + TextUtils.truncate(executionResult.action(), 1200),
                requestId
        );
    }

    private void persistSuspension(ChatContext context,
                                   String assistantMessage,
                                   ChatExecutionResult executionResult) {
        String sessionKey = context.session().getSessionKey();
        String channel = context.channel();
        String userId = context.userId();
        String requestId = context.requestId();
        // 挂起：更新用户消息与会话状态，保留 lastAssistantMessage，不写语义记忆，
        // 不写终端 assistant 事件。
        agentSessionService.persistUserMessage(
                context.session(),
                context.effectiveUserMessage(),
                soulPromptService.soulVersion()
        );
        messageEventService.append(new MessageEventWrite(
                "chat:" + requestId + ":user", sessionKey, channel, userId,
                "USER", "CHAT", context.effectiveUserMessage(), requestId));
        messageEventService.append(new MessageEventWrite(
                "chat:" + requestId + ":suspension", sessionKey, channel, userId,
                "ASSISTANT", "CHAT",
                TextUtils.truncate(assistantMessage, 1600),
                requestId));
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

}
