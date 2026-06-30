package com.springclaw.service.memory.extraction;

import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.service.memory.consolidation.MemoryConsolidationService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryExtractionTriggerTest {

    @Test
    void disabledTriggerDoesNotInvokeExtractionService() {
        TerminalMemoryExtractionService service = mock(TerminalMemoryExtractionService.class);
        MemoryConsolidationService consolidationService = mock(MemoryConsolidationService.class);
        MemoryExtractionTrigger trigger = new MemoryExtractionTrigger(
                service,
                consolidationService,
                new SyncTaskExecutor(),
                false,
                true,
                50
        );

        trigger.afterTerminalPersistence("run-1", "alice");

        verify(service, never()).extractTerminalRun("run-1", "alice");
        verifyNoInteractions(consolidationService);
    }

    @Test
    void enabledTriggerRunsExtractionAndAutoConsolidationAsSubmittedWork() {
        TerminalMemoryExtractionService service = mock(TerminalMemoryExtractionService.class);
        MemoryConsolidationService consolidationService = mock(MemoryConsolidationService.class);
        when(service.extractTerminalRun("run-1", "alice"))
                .thenReturn(new TerminalMemoryExtractionResult(1, 1));
        MemoryExtractionTrigger trigger = new MemoryExtractionTrigger(
                service,
                consolidationService,
                new SyncTaskExecutor(),
                true,
                true,
                50
        );

        trigger.afterTerminalPersistence("run-1", "alice");

        verify(service).extractTerminalRun("run-1", "alice");
        verify(consolidationService).consolidate(argThat(scope ->
                scope != null
                        && scope.equals(MemoryScope.user("api", "consolidation", "alice"))
        ), eq(50));
    }

    @Test
    void consolidationCanBeDisabledSeparatelyFromExtraction() {
        TerminalMemoryExtractionService service = mock(TerminalMemoryExtractionService.class);
        MemoryConsolidationService consolidationService = mock(MemoryConsolidationService.class);
        MemoryExtractionTrigger trigger = new MemoryExtractionTrigger(
                service,
                consolidationService,
                new SyncTaskExecutor(),
                true,
                false,
                50
        );

        trigger.afterTerminalPersistence("run-1", "alice");

        verify(service).extractTerminalRun("run-1", "alice");
        verifyNoInteractions(consolidationService);
    }

    @Test
    void autoConsolidationRunsOnlyWhenTerminalReflectionWasWritten() {
        TerminalMemoryExtractionService service = mock(TerminalMemoryExtractionService.class);
        MemoryConsolidationService consolidationService = mock(MemoryConsolidationService.class);
        when(service.extractTerminalRun("run-empty", "alice"))
                .thenReturn(TerminalMemoryExtractionResult.empty());
        MemoryExtractionTrigger trigger = new MemoryExtractionTrigger(
                service,
                consolidationService,
                new SyncTaskExecutor(),
                true,
                true,
                50
        );

        trigger.afterTerminalPersistence("run-empty", "alice");

        verify(service).extractTerminalRun("run-empty", "alice");
        verifyNoInteractions(consolidationService);
    }

    @Test
    void consolidationFailureDoesNotPropagateToTerminalPersistence() {
        TerminalMemoryExtractionService service = mock(TerminalMemoryExtractionService.class);
        MemoryConsolidationService consolidationService = mock(MemoryConsolidationService.class);
        when(service.extractTerminalRun("run-1", "alice"))
                .thenReturn(new TerminalMemoryExtractionResult(0, 1));
        doThrow(new IllegalStateException("store unavailable"))
                .when(consolidationService)
                .consolidate(argThat(scope -> scope != null && scope.scopeId().equals("alice")), eq(50));
        MemoryExtractionTrigger trigger = new MemoryExtractionTrigger(
                service,
                consolidationService,
                new SyncTaskExecutor(),
                true,
                true,
                50
        );

        assertThatCode(() -> trigger.afterTerminalPersistence("run-1", "alice"))
                .doesNotThrowAnyException();
    }
}
