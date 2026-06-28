package com.springclaw.service.memory.extraction;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class MemoryExtractionTriggerTest {

    @Test
    void disabledTriggerDoesNotInvokeExtractionService() {
        TerminalMemoryExtractionService service = mock(TerminalMemoryExtractionService.class);
        MemoryExtractionTrigger trigger = new MemoryExtractionTrigger(
                service,
                new SyncTaskExecutor(),
                false
        );

        trigger.afterTerminalPersistence("run-1", "alice");

        verify(service, never()).extractTerminalRun("run-1", "alice");
    }

    @Test
    void enabledTriggerRunsExtractionAsSubmittedWork() {
        TerminalMemoryExtractionService service = mock(TerminalMemoryExtractionService.class);
        MemoryExtractionTrigger trigger = new MemoryExtractionTrigger(
                service,
                new SyncTaskExecutor(),
                true
        );

        trigger.afterTerminalPersistence("run-1", "alice");

        verify(service).extractTerminalRun("run-1", "alice");
    }
}
