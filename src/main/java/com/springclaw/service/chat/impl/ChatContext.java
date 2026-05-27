package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;

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
                          String intent) {
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
                assembled, activeClient, executionMode, routingReason, "agent", "general");
    }
}
