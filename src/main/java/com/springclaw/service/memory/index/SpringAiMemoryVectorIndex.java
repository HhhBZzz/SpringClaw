package com.springclaw.service.memory.index;

import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
@ConditionalOnBean(VectorStore.class)
public class SpringAiMemoryVectorIndex implements MemoryVectorIndex {

    private final VectorStore vectorStore;

    public SpringAiMemoryVectorIndex(VectorStore vectorStore) {
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
    }

    @Override
    public void createGeneration(String generation) {
        // Spring AI VectorStore creates physical storage lazily on add.
    }

    @Override
    public void upsert(MemoryRecordVersion version, String generation) {
        Objects.requireNonNull(version, "version");
        vectorStore.add(List.of(new Document(
                version.memoryVersionId(),
                version.content(),
                metadata(version, generation)
        )));
    }

    @Override
    public void delete(String memoryVersionId, String generation) {
        vectorStore.delete(List.of(requireText(memoryVersionId, "memoryVersionId")));
    }

    @Override
    public IndexedPage listIndexedVersionIds(String generation, String cursor, int limit) {
        // Generic Spring AI VectorStore has no portable scan API. Reconciliation is
        // implemented for indexes that expose scan support via another adapter.
        return new IndexedPage(List.of(), null);
    }

    private static Map<String, Object> metadata(
            MemoryRecordVersion version,
            String generation
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("logicalMemoryId", version.logicalMemoryId());
        metadata.put("memoryVersionId", version.memoryVersionId());
        metadata.put("memoryType", version.memoryType().name());
        metadata.put("scopeType", version.scopeType().name());
        metadata.put("scopeId", version.scopeId());
        metadata.put("ownerUserId", version.ownerUserId());
        metadata.put("version", version.version());
        metadata.put("indexRevision", version.indexRevision());
        metadata.put("generation", requireText(generation, "generation"));
        metadata.put("contentHash", version.contentHash());
        return metadata;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
