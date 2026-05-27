package com.springclaw.service.chat.impl;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 统一为请求挂载 Spring AI Advisor。
 */
@Service
public class ConversationAdvisorSupport {

    private static final String CHAT_MEMORY_CONVERSATION_ID = "chat_memory_conversation_id";

    private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;
    private final SemanticMemoryAdvisor semanticMemoryAdvisor;
    private final boolean springAiChatMemoryEnabled;

    public ConversationAdvisorSupport(MessageChatMemoryAdvisor messageChatMemoryAdvisor,
                                      SemanticMemoryAdvisor semanticMemoryAdvisor,
                                      @Value("${springclaw.chat.spring-ai-chat-memory-enabled:false}") boolean springAiChatMemoryEnabled) {
        this.messageChatMemoryAdvisor = messageChatMemoryAdvisor;
        this.semanticMemoryAdvisor = semanticMemoryAdvisor;
        this.springAiChatMemoryEnabled = springAiChatMemoryEnabled;
    }

    public ChatClient.ChatClientRequestSpec apply(ChatClient.ChatClientRequestSpec requestSpec,
                                                  String conversationId,
                                                  String userId) {
        List<Advisor> advisors = springAiChatMemoryEnabled
                ? List.of(messageChatMemoryAdvisor, semanticMemoryAdvisor)
                : List.of(semanticMemoryAdvisor);
        return requestSpec.advisors(spec -> spec
                .params(Map.of(
                        CHAT_MEMORY_CONVERSATION_ID, conversationId,
                        SemanticMemoryAdvisor.CONTEXT_USER_ID, userId == null ? "" : userId
                ))
                .advisors(advisors));
    }
}
