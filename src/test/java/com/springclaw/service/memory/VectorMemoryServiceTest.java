package com.springclaw.service.memory;

import com.springclaw.service.memory.impl.VectorMemoryService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.List;

class VectorMemoryServiceTest {

    @Test
    void shouldFallbackToLocalMemoryWhenVectorStoreUnavailable() {
        VectorMemoryService service = new VectorMemoryService(null, true, 12);

        service.storeConversationTurn("s1", "api", "u1", "你好", "你好，我是助手");
        List<Document> docs = service.recallBySession("s1", "你还记得我吗", 6);

        Assertions.assertFalse(docs.isEmpty());
        Assertions.assertTrue(docs.stream().anyMatch(d -> "USER".equals(d.getMetadata().get("role"))));
        VectorMemoryService.MemoryRuntimeStatus status = service.runtimeStatus();
        Assertions.assertEquals("local-fallback-only", status.activeMode());
        Assertions.assertEquals("LOCAL", status.lastWriteRoute());
        Assertions.assertEquals("LOCAL", status.lastRecallRoute());
        Assertions.assertEquals(1L, status.localWriteFallbackCount());
        Assertions.assertEquals(1L, status.localRecallHitCount());
    }

    @Test
    void shouldRecallFromVectorStoreWhenAvailable() {
        InMemoryVectorStore vectorStore = new InMemoryVectorStore();
        VectorMemoryService service = new VectorMemoryService(vectorStore, true, 12);

        service.storeConversationTurn("s2", "api", "u2", "我喜欢Java", "好的，后续会按Java给建议");
        List<Document> docs = service.recallBySession("s2", "给我一份学习路线", 6);

        Assertions.assertFalse(docs.isEmpty());
        Assertions.assertTrue(docs.stream().allMatch(d -> "s2".equals(String.valueOf(d.getMetadata().get("sessionKey")))));
        VectorMemoryService.MemoryRuntimeStatus status = service.runtimeStatus();
        Assertions.assertEquals("redis-vector-store", status.activeMode());
        Assertions.assertEquals("VECTOR", status.lastWriteRoute());
        Assertions.assertEquals("VECTOR", status.lastRecallRoute());
        Assertions.assertEquals(1L, status.vectorWriteSuccessCount());
        Assertions.assertEquals(1L, status.vectorRecallHitCount());
    }

    static class InMemoryVectorStore implements VectorStore {

        private final List<Document> docs = new ArrayList<>();

        @Override
        public void add(List<Document> documents) {
            docs.addAll(documents);
        }

        @Override
        public void delete(List<String> idList) {
            docs.removeIf(doc -> idList.contains(doc.getId()));
        }

        @Override
        public void delete(org.springframework.ai.vectorstore.filter.Filter.Expression filterExpression) {
            // no-op
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return new ArrayList<>(docs);
        }
    }
}
