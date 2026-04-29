package com.springclaw.service.memory;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * 会话记忆服务。
 *
 * 设计说明：
 * 1. 对上层屏蔽“向量检索/本地回退”差异，ChatService 只关心拿上下文和存记忆。
 * 2. 这是典型的“端口-适配器”思路，后续切换 Milvus/ES 只改实现类。
 */
public interface MemoryService {

    List<Document> recallBySession(String sessionKey, String query, int topK);

    List<Document> recallByUser(String userId, String query, int topK);

    /**
     * 持久化一轮问答记忆。
     */
    void storeConversationTurn(String sessionKey, String channel, String userId, String userMessage, String assistantMessage);
}
