package com.openclaw.service.chat.impl;

import com.openclaw.common.support.ConversationScopeSupport;
import com.openclaw.service.memory.MemoryService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 在 Spring AI 1.0.0 正式版下补齐语义记忆 Advisor。
 */
@Service
public class SemanticMemoryAdvisor implements CallAdvisor, StreamAdvisor {

    public static final String CONTEXT_CONVERSATION_ID = "chat_memory_conversation_id";
    public static final String CONTEXT_USER_ID = "openclaw.user_id";

    private final MemoryService memoryService;
    private final int maxDocs;
    private final int maxChars;

    public SemanticMemoryAdvisor(MemoryService memoryService,
                                 @Value("${openclaw.memory.recall-top-k:8}") int maxDocs,
                                 @Value("${openclaw.memory.recall-max-chars:400}") int maxChars) {
        this.memoryService = memoryService;
        this.maxDocs = Math.max(1, Math.min(maxDocs, 12));
        this.maxChars = Math.max(120, maxChars);
    }

    @Override
    public String getName() {
        return "semanticMemoryAdvisor";
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(augment(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(augment(request));
    }

    private ChatClientRequest augment(ChatClientRequest request) {
        String conversationId = asText(request.context().get(CONTEXT_CONVERSATION_ID));
        String userId = asText(request.context().get(CONTEXT_USER_ID));
        String query = request.prompt() == null || request.prompt().getUserMessage() == null
                ? ""
                : request.prompt().getUserMessage().getText();
        String semanticMemory = renderSemanticMemory(conversationId, userId, query);
        if (!StringUtils.hasText(semanticMemory)) {
            return request;
        }
        Prompt augmentedPrompt = request.prompt().augmentSystemMessage("""
                你还可以参考以下长期语义记忆，它来自同会话优先、同用户补充的向量召回结果。
                这些内容只能作为辅助证据，不能编造不存在的历史。

                LONG_TERM_MEMORY:
                %s
                """.formatted(semanticMemory));
        return request.mutate().prompt(augmentedPrompt).build();
    }

    private String renderSemanticMemory(String conversationId, String userId, String query) {
        Set<String> lines = new LinkedHashSet<>();
        List<Document> sessionDocs = memoryService.recallBySession(conversationId, query, maxDocs);
        List<Document> userDocs = ConversationScopeSupport.shouldUseCrossSessionUserMemory(conversationId)
                ? memoryService.recallByUser(userId, query, Math.max(1, maxDocs / 2))
                : List.of();

        for (Document doc : sessionDocs) {
            lines.add(renderDoc("SESSION", doc));
        }
        for (Document doc : userDocs) {
            String docSession = String.valueOf(doc.getMetadata().getOrDefault("sessionKey", ""));
            if (!conversationId.equals(docSession)) {
                lines.add(renderDoc("USER", doc));
            }
        }
        return lines.stream()
                .filter(StringUtils::hasText)
                .limit(maxDocs)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String renderDoc(String scope, Document doc) {
        if (doc == null || !StringUtils.hasText(doc.getText())) {
            return "";
        }
        String role = ConversationScopeSupport.renderSpeakerRole(
                String.valueOf(doc.getMetadata().getOrDefault("channel", "unknown")),
                String.valueOf(doc.getMetadata().getOrDefault("sessionKey", "")),
                String.valueOf(doc.getMetadata().getOrDefault("role", "UNKNOWN")),
                String.valueOf(doc.getMetadata().getOrDefault("userId", ""))
        );
        String text = doc.getText().replaceAll("\\s+", " ").trim();
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars) + "...";
        }
        return "- [" + scope + "] " + role + ": " + text;
    }

    private String asText(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
