package com.springclaw.service.memory.index;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;

import static com.springclaw.service.memory.index.MemoryIndexWorkerTest.activeVersion;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SpringAiMemoryVectorIndexTest {

    private final VectorStore vectorStore = mock(VectorStore.class);
    private final SpringAiMemoryVectorIndex index = new SpringAiMemoryVectorIndex(vectorStore);

    @Test
    void upsertUsesMemoryVersionIdAsDocumentIdAndStoresCanonicalMetadata() {
        index.upsert(activeVersion("version-1", 7), "gen-2");

        ArgumentCaptor<List<Document>> docs = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(docs.capture());
        Document doc = docs.getValue().get(0);
        assertThat(doc.getId()).isEqualTo("version-1");
        assertThat(doc.getMetadata())
                .containsEntry("logicalMemoryId", "logical-1")
                .containsEntry("memoryVersionId", "version-1")
                .containsEntry("memoryType", "SEMANTIC")
                .containsEntry("scopeType", "PERSONAL_SESSION")
                .containsEntry("scopeId", "api:s1:u1")
                .containsEntry("ownerUserId", "u1")
                .containsEntry("version", 1)
                .containsEntry("indexRevision", 7L)
                .containsEntry("generation", "gen-2")
                .containsEntry("contentHash", "hash-version-1");
    }

    @Test
    void deleteUsesMemoryVersionId() {
        index.delete("version-1", "gen-2");

        verify(vectorStore).delete(List.of("version-1"));
    }
}
