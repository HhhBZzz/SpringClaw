package com.springclaw.service.memory.impl;

import com.springclaw.service.memory.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/**
 * 向量记忆服务实现。
 *
 * 设计说明：
 * 1. 采用“Redis VectorStore + 本地降级”双通道，保障语义记忆能力与稳定性。
 * 2. 通过 metadata filter 优先召回同会话记忆，避免跨会话信息污染。
 */
@Service
public class VectorMemoryService implements MemoryService {

    private static final Logger log = LoggerFactory.getLogger(VectorMemoryService.class);

    private final VectorStore vectorStore;
    private final boolean vectorEnabled;
    private final int localMaxTurns;

    private final ConcurrentMap<String, Deque<Document>> localMemory = new ConcurrentHashMap<>();
    private final LongAdder vectorWriteSuccessCount = new LongAdder();
    private final LongAdder localWriteFallbackCount = new LongAdder();
    private final LongAdder vectorRecallHitCount = new LongAdder();
    private final LongAdder localRecallHitCount = new LongAdder();
    private final LongAdder vectorFailureCount = new LongAdder();
    private final AtomicReference<String> lastWriteRoute = new AtomicReference<>("NONE");
    private final AtomicReference<String> lastRecallRoute = new AtomicReference<>("NONE");

    public VectorMemoryService(@Autowired(required = false) VectorStore vectorStore,
                               @Value("${springclaw.memory.vector-enabled:true}") boolean vectorEnabled,
                               @Value("${springclaw.memory.local-max-turns:12}") int localMaxTurns) {
        this.vectorStore = vectorStore;
        this.vectorEnabled = vectorEnabled;
        this.localMaxTurns = Math.max(2, localMaxTurns);
    }

    @Override
    public List<Document> recallBySession(String sessionKey, String query, int topK) {
        if (!StringUtils.hasText(sessionKey)) {
            return List.of();
        }

        List<Document> vectorDocs = searchFromVector(
                query,
                topK,
                "sessionKey == '%s'".formatted(escapeFilter(sessionKey)),
                doc -> sessionKey.equals(String.valueOf(doc.getMetadata().get("sessionKey")))
        );
        if (!vectorDocs.isEmpty()) {
            vectorRecallHitCount.increment();
            lastRecallRoute.set("VECTOR");
            return vectorDocs;
        }

        List<Document> localDocs = searchFromLocal(doc -> sessionKey.equals(String.valueOf(doc.getMetadata().get("sessionKey"))), topK);
        if (!localDocs.isEmpty()) {
            localRecallHitCount.increment();
            lastRecallRoute.set("LOCAL");
        }
        return localDocs;
    }

    @Override
    public List<Document> recallByUser(String userId, String query, int topK) {
        if (!StringUtils.hasText(userId)) {
            return List.of();
        }

        List<Document> vectorDocs = searchFromVector(
                query,
                topK,
                "userId == '%s'".formatted(escapeFilter(userId)),
                doc -> userId.equals(String.valueOf(doc.getMetadata().get("userId")))
        );
        if (!vectorDocs.isEmpty()) {
            vectorRecallHitCount.increment();
            lastRecallRoute.set("VECTOR");
            return vectorDocs;
        }

        List<Document> localDocs = searchFromLocal(doc -> userId.equals(String.valueOf(doc.getMetadata().get("userId"))), topK);
        if (!localDocs.isEmpty()) {
            localRecallHitCount.increment();
            lastRecallRoute.set("LOCAL");
        }
        return localDocs;
    }

    @Override
    public void storeConversationTurn(String sessionKey,
                                      String channel,
                                      String userId,
                                      String userMessage,
                                      String assistantMessage) {
        if (!StringUtils.hasText(sessionKey)) {
            return;
        }

        List<Document> docs = buildTurnDocuments(sessionKey, channel, userId, userMessage, assistantMessage);
        if (docs.isEmpty()) {
            return;
        }

        boolean writtenToVector = false;
        if (vectorEnabled && vectorStore != null) {
            try {
                vectorStore.add(docs);
                writtenToVector = true;
                vectorWriteSuccessCount.increment();
                lastWriteRoute.set("VECTOR");
            } catch (Exception ex) {
                vectorFailureCount.increment();
                log.warn("向量记忆写入失败，降级本地记忆。sessionKey={}, reason={}", sessionKey, ex.getMessage());
            }
        }

        if (!writtenToVector) {
            localWriteFallbackCount.increment();
            lastWriteRoute.set("LOCAL");
            Deque<Document> deque = localMemory.computeIfAbsent(sessionKey, key -> new ArrayDeque<>());
            for (Document doc : docs) {
                deque.addLast(doc);
            }
            while (deque.size() > localMaxTurns * 2L) {
                deque.pollFirst();
            }
        }
    }

    private List<Document> searchFromVector(String query,
                                            int topK,
                                            String filterExpression,
                                            java.util.function.Predicate<Document> fallbackFilter) {
        if (!vectorEnabled || vectorStore == null) {
            return List.of();
        }

        String safeQuery = StringUtils.hasText(query) ? query : "memory";
        int safeTopK = Math.max(1, Math.min(20, topK));

        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(safeQuery)
                            .topK(safeTopK)
                            .similarityThresholdAll()
                            .filterExpression(filterExpression)
                            .build()
            );
            if (docs == null || docs.isEmpty()) {
                return List.of();
            }
            return docs;
        } catch (Exception ex) {
            try {
                List<Document> docs = vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(safeQuery)
                                .topK(safeTopK * 2)
                                .similarityThresholdAll()
                                .build()
                );
                if (docs == null || docs.isEmpty()) {
                    return List.of();
                }
                return docs.stream().filter(fallbackFilter).limit(safeTopK).collect(Collectors.toList());
            } catch (Exception ex2) {
                vectorFailureCount.increment();
                log.warn("向量记忆检索失败，降级本地记忆。reason={}", ex2.getMessage());
                return List.of();
            }
        }
    }

    public MemoryRuntimeStatus runtimeStatus() {
        int localSessionCount = localMemory.size();
        int localDocumentCount = localMemory.values().stream().mapToInt(Deque::size).sum();
        return new MemoryRuntimeStatus(
                vectorEnabled,
                vectorStore != null,
                vectorStore == null ? "none" : vectorStore.getClass().getName(),
                vectorEnabled && vectorStore != null ? "redis-vector-store" : "local-fallback-only",
                lastWriteRoute.get(),
                lastRecallRoute.get(),
                vectorWriteSuccessCount.sum(),
                localWriteFallbackCount.sum(),
                vectorRecallHitCount.sum(),
                localRecallHitCount.sum(),
                vectorFailureCount.sum(),
                localSessionCount,
                localDocumentCount,
                localMaxTurns
        );
    }

    public record MemoryRuntimeStatus(boolean vectorEnabled,
                                      boolean vectorStoreAvailable,
                                      String vectorStoreType,
                                      String activeMode,
                                      String lastWriteRoute,
                                      String lastRecallRoute,
                                      long vectorWriteSuccessCount,
                                      long localWriteFallbackCount,
                                      long vectorRecallHitCount,
                                      long localRecallHitCount,
                                      long vectorFailureCount,
                                      int localSessionCount,
                                      int localDocumentCount,
                                      int localMaxTurns) {
    }

    private List<Document> searchFromLocal(java.util.function.Predicate<Document> predicate, int topK) {
        int safeTopK = Math.max(1, Math.min(20, topK));
        return localMemory.values().stream()
                .flatMap(Deque::stream)
                .filter(predicate)
                .sorted((a, b) -> Long.compare(extractTs(b), extractTs(a)))
                .limit(safeTopK)
                .collect(Collectors.toList());
    }

    private List<Document> buildTurnDocuments(String sessionKey,
                                              String channel,
                                              String userId,
                                              String userMessage,
                                              String assistantMessage) {
        List<Document> docs = new ArrayList<>();
        long now = Instant.now().toEpochMilli();

        if (StringUtils.hasText(userMessage)) {
            docs.add(new Document(
                    sessionKey + ":user:" + now,
                    sanitize(userMessage),
                    metadata(sessionKey, channel, userId, "USER", now)
            ));
        }

        if (StringUtils.hasText(assistantMessage)) {
            docs.add(new Document(
                    sessionKey + ":assistant:" + (now + 1),
                    sanitize(assistantMessage),
                    metadata(sessionKey, channel, userId, "ASSISTANT", now + 1)
            ));
        }

        return docs;
    }

    private Map<String, Object> metadata(String sessionKey, String channel, String userId, String role, long ts) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sessionKey", sessionKey);
        map.put("channel", channel == null ? "unknown" : channel);
        map.put("userId", userId == null ? "anonymous" : userId);
        map.put("role", role);
        map.put("timestamp", ts);
        return map;
    }

    private String sanitize(String input) {
        if (!StringUtils.hasText(input)) {
            return "";
        }
        return input.replaceAll("\\s+", " ").trim();
    }

    private long extractTs(Document document) {
        Object ts = document.getMetadata().get("timestamp");
        if (ts instanceof Number n) {
            return n.longValue();
        }
        return 0L;
    }

    private String escapeFilter(String value) {
        return value == null ? "" : value.replace("'", "\\'");
    }
}
