package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;

/**
 * 聊天请求的运行时上下文，封装了一次对话所需的所有元数据。
 */
public record ChatContext(AgentSession session,
                          String channel,
                          String userId,
                          String roleCode,
                          String userMessage,
                          String effectiveUserMessage,
                          String requestId,
                          String systemPrompt,
                          AssembledContext assembled,
                          AiProviderService.ActiveChatClient activeClient,
                          String executionMode,
                          String routingReason,
                          String responseMode,
                          String intent,
                          AgentDecision decision,
                          ContextInjection contextInjection,
                          ContextSnapshot contextSnapshot) {

    public ChatContext {
        contextInjection = contextInjection == null ? ContextInjection.empty() : contextInjection;
    }

    // 兼容旧 12 参构造（无 responseMode/intent/decision/contextInjection）
    public ChatContext(AgentSession session,
                       String channel,
                       String userId,
                       String roleCode,
                       String userMessage,
                       String effectiveUserMessage,
                       String requestId,
                       String systemPrompt,
                       AssembledContext assembled,
                       AiProviderService.ActiveChatClient activeClient,
                       String executionMode,
                       String routingReason) {
        this(session, channel, userId, roleCode, userMessage, effectiveUserMessage, requestId, systemPrompt,
                assembled, activeClient, executionMode, routingReason,
                "agent", "general",
                AgentDecision.general("兼容旧构造器，默认普通聊天。"),
                ContextInjection.empty(),
                null);
    }

    // 兼容旧 15 参构造（无 contextInjection）
    public ChatContext(AgentSession session,
                       String channel,
                       String userId,
                       String roleCode,
                       String userMessage,
                       String effectiveUserMessage,
                       String requestId,
                       String systemPrompt,
                       AssembledContext assembled,
                       AiProviderService.ActiveChatClient activeClient,
                       String executionMode,
                       String routingReason,
                       String responseMode,
                       String intent,
                       AgentDecision decision) {
        this(session, channel, userId, roleCode, userMessage, effectiveUserMessage, requestId, systemPrompt,
                assembled, activeClient, executionMode, routingReason, responseMode, intent, decision,
                ContextInjection.empty(),
                null);
    }

    // 兼容旧 16 参构造（无 contextSnapshot）
    public ChatContext(AgentSession session,
                       String channel,
                       String userId,
                       String roleCode,
                       String userMessage,
                       String effectiveUserMessage,
                       String requestId,
                       String systemPrompt,
                       AssembledContext assembled,
                       AiProviderService.ActiveChatClient activeClient,
                       String executionMode,
                       String routingReason,
                       String responseMode,
                       String intent,
                       AgentDecision decision,
                       ContextInjection contextInjection) {
        this(session, channel, userId, roleCode, userMessage, effectiveUserMessage, requestId, systemPrompt,
                assembled, activeClient, executionMode, routingReason, responseMode, intent, decision,
                contextInjection,
                null);
    }
}
