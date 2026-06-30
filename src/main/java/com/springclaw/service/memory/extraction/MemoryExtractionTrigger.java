package com.springclaw.service.memory.extraction;

import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.service.memory.consolidation.MemoryConsolidationService;
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
    private final MemoryConsolidationService consolidationService;
    private final TaskExecutor executor;
    private final boolean enabled;
    private final boolean consolidationEnabled;
    private final int consolidationEpisodeLimit;

    public MemoryExtractionTrigger(
            TerminalMemoryExtractionService extractionService,
            MemoryConsolidationService consolidationService,
            @Qualifier("memoryExtractionExecutor") TaskExecutor executor,
            @Value("${springclaw.memory.semantic-extraction.enabled:false}") boolean enabled,
            @Value("${springclaw.memory.consolidation.auto-enabled:${SPRINGCLAW_MEMORY_CONSOLIDATION_AUTO_ENABLED:true}}")
            boolean consolidationEnabled,
            @Value("${springclaw.memory.consolidation.auto-episode-limit:${SPRINGCLAW_MEMORY_CONSOLIDATION_AUTO_EPISODE_LIMIT:50}}")
            int consolidationEpisodeLimit
    ) {
        this.extractionService = extractionService;
        this.consolidationService = consolidationService;
        this.executor = executor;
        this.enabled = enabled;
        this.consolidationEnabled = consolidationEnabled;
        this.consolidationEpisodeLimit = Math.max(2, Math.min(consolidationEpisodeLimit, 500));
    }

    public void afterTerminalPersistence(String runId, String userId) {
        if (!enabled || !StringUtils.hasText(runId) || !StringUtils.hasText(userId)) {
            return;
        }
        executor.execute(() -> {
            try {
                TerminalMemoryExtractionResult result = extractionService.extractTerminalRun(runId, userId);
                if (consolidationEnabled && result != null && result.reflectionWritten() > 0) {
                    autoConsolidate(userId);
                }
            } catch (RuntimeException ex) {
                log.warn("terminal memory extraction or consolidation failed, runId={}, reason={}",
                        runId, ex.getMessage());
            }
        });
    }

    private void autoConsolidate(String userId) {
        consolidationService.consolidate(
                MemoryScope.user("api", "consolidation", userId),
                consolidationEpisodeLimit
        );
    }
}
