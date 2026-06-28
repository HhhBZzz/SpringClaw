package com.springclaw.service.memory.extraction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MemoryExtractionTrigger {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionTrigger.class);

    private final TerminalMemoryExtractionService extractionService;
    private final TaskExecutor executor;
    private final boolean enabled;

    public MemoryExtractionTrigger(
            TerminalMemoryExtractionService extractionService,
            @Qualifier("memoryExtractionExecutor") TaskExecutor executor,
            @Value("${springclaw.memory.semantic-extraction.enabled:false}") boolean enabled
    ) {
        this.extractionService = extractionService;
        this.executor = executor;
        this.enabled = enabled;
    }

    public void afterTerminalPersistence(String runId, String userId) {
        if (!enabled || !StringUtils.hasText(runId) || !StringUtils.hasText(userId)) {
            return;
        }
        executor.execute(() -> {
            try {
                extractionService.extractTerminalRun(runId, userId);
            } catch (RuntimeException ex) {
                log.warn("terminal memory extraction failed, runId={}, reason={}",
                        runId, ex.getMessage());
            }
        });
    }
}
