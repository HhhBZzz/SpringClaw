package com.springclaw.service.memory.index;

import com.springclaw.runtime.memory.port.MemoryRecordStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static com.springclaw.service.memory.index.MemoryIndexWorkerTest.activeVersion;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryIndexReconcilerTest {

    private final MemoryRecordStore recordStore = mock(MemoryRecordStore.class);
    private final MemoryVectorIndex index = mock(MemoryVectorIndex.class);
    private final MemoryIndexGenerationStore generationStore = mock(MemoryIndexGenerationStore.class);
    private final MemoryIndexReconciler reconciler =
            new MemoryIndexReconciler(recordStore, index, generationStore);

    @Test
    void deletesIndexedVersionThatIsNotCurrentActiveVersion() {
        when(generationStore.activeGeneration()).thenReturn("gen-1");
        when(index.listIndexedVersionIds("gen-1", null, 100))
                .thenReturn(new MemoryVectorIndex.IndexedPage(
                        List.of("version-1", "version-2"),
                        null));
        when(recordStore.findByVersionId("version-1"))
                .thenReturn(Optional.of(activeVersion("version-1", 1)));
        when(recordStore.findActive("logical-1"))
                .thenReturn(Optional.of(activeVersion("version-2", 2)));
        when(recordStore.findByVersionId("version-2"))
                .thenReturn(Optional.of(activeVersion("version-2", 2)));

        reconciler.reconcileOnce(100);

        verify(index).delete("version-1", "gen-1");
        verify(index, never()).delete("version-2", "gen-1");
    }
}
