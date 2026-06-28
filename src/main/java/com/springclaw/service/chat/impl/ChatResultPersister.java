package com.springclaw.service.chat.impl;

import com.springclaw.common.util.TextUtils;
import com.springclaw.service.event.MessageEventReceipt;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.event.MessageEventWrite;
import com.springclaw.service.memory.ShortTermMemoryWriter;
import com.springclaw.service.memory.extraction.MemoryExtractionTrigger;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.session.AgentSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 对话结果持久化组件，负责保存对话结果到会话、记忆和事件流。
 */
@Component
public class ChatResultPersister {

    private static final Logger log = LoggerFactory.getLogger(ChatResultPersister.class);

    private final AgentSessionService agentSessionService;
    private final MessageEventService messageEventService;
    private final SoulPromptService soulPromptService;
    private final ShortTermMemoryWriter shortTermMemoryWriter;
    private final MemoryExtractionTrigger memoryExtractionTrigger;

    public ChatResultPersister(AgentSessionService agentSessionService,
                               MessageEventService messageEventService,
                               SoulPromptService soulPromptService,
                               ShortTermMemoryWriter shortTermMemoryWriter) {
        this(agentSessionService, messageEventService, soulPromptService, shortTermMemoryWriter, null);
    }

    @Autowired
    public ChatResultPersister(AgentSessionService agentSessionService,
                               MessageEventService messageEventService,
                               SoulPromptService soulPromptService,
                               ShortTermMemoryWriter shortTermMemoryWriter,
                               MemoryExtractionTrigger memoryExtractionTrigger) {
        this.agentSessionService = agentSessionService;
        this.messageEventService = messageEventService;
        this.soulPromptService = soulPromptService;
        this.shortTermMemoryWriter = shortTermMemoryWriter;
        this.memoryExtractionTrigger = memoryExtractionTrigger;
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
        String assistantForMemory = normalizeAssistantForMemory(assistantMessage);
        MessageEventReceipt userReceipt = messageEventService.append(new MessageEventWrite(
                "chat:" + requestId + ":user", sessionKey, channel, userId,
                "USER", "CHAT", context.effectiveUserMessage(), requestId));
        MessageEventReceipt assistantReceipt = messageEventService.append(new MessageEventWrite(
                "chat:" + requestId + ":assistant:terminal", sessionKey, channel, userId,
                "ASSISTANT", "CHAT",
                "[REFLECT] " + TextUtils.truncate(assistantForMemory, 1600),
                requestId));
        shadowTerminal(context, userReceipt, context.effectiveUserMessage(), assistantReceipt, assistantForMemory);
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
        triggerMemoryExtraction(requestId, userId);
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
        MessageEventReceipt userReceipt = messageEventService.append(new MessageEventWrite(
                "chat:" + requestId + ":user", sessionKey, channel, userId,
                "USER", "CHAT", context.effectiveUserMessage(), requestId));
        messageEventService.append(new MessageEventWrite(
                "chat:" + requestId + ":suspension", sessionKey, channel, userId,
                "ASSISTANT", "CHAT",
                TextUtils.truncate(assistantMessage, 1600),
                requestId));
        shadowSuspension(context, userReceipt, context.effectiveUserMessage());
    }

    private void shadowTerminal(ChatContext context,
                                MessageEventReceipt userReceipt,
                                String userContent,
                                MessageEventReceipt assistantReceipt,
                                String assistantContent) {
        if (shortTermMemoryWriter == null || userReceipt == null || assistantReceipt == null) {
            return;
        }
        try {
            shortTermMemoryWriter.appendTerminal(
                    context, userReceipt, userContent, assistantReceipt, assistantContent);
        } catch (Exception ex) {
            log.warn("短期记忆 terminal shadow 写入失败，继续使用 MySQL 恢复源。requestId={}, reason={}",
                    context.requestId(), ex.getMessage());
        }
    }

    private void shadowSuspension(ChatContext context,
                                  MessageEventReceipt userReceipt,
                                  String userContent) {
        if (shortTermMemoryWriter == null || userReceipt == null) {
            return;
        }
        try {
            shortTermMemoryWriter.appendSuspension(context, userReceipt, userContent);
        } catch (Exception ex) {
            log.warn("短期记忆 suspension shadow 写入失败，继续使用 MySQL 恢复源。requestId={}, reason={}",
                    context.requestId(), ex.getMessage());
        }
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

    private void triggerMemoryExtraction(String requestId, String userId) {
        if (memoryExtractionTrigger == null) {
            return;
        }
        try {
            memoryExtractionTrigger.afterTerminalPersistence(requestId, userId);
        } catch (RuntimeException ex) {
            log.warn("终态记忆抽取触发失败，忽略。requestId={}, reason={}",
                    requestId, ex.getMessage());
        }
    }

}
