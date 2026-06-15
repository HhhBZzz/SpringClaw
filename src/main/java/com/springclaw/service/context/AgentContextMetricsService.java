package com.springclaw.service.context;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Records context source metadata as numeric metrics only.
 */
@Service
public class AgentContextMetricsService {

    private static final String CONTEXT_CHARS_METRIC = "springclaw.agent.context.chars";
    private static final String MEMORY_BANK_USED_METRIC = "springclaw.agent.context.memory_bank.used";
    private static final String LEARNING_ENTRIES_METRIC = "springclaw.agent.context.learning.entries";

    private final MeterRegistry meterRegistry;
    private final boolean enabled;

    public AgentContextMetricsService(MeterRegistry meterRegistry,
                                      @Value("${springclaw.context.metrics-enabled:true}") boolean enabled) {
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
    }

    public void record(AssembledContext.ContextSourceSummary summary) {
        if (!enabled || meterRegistry == null || summary == null) {
            return;
        }
        recordChars("memory_bank", summary.memoryBankChars());
        recordChars("short_term", summary.shortTermChars());
        recordChars("semantic_memory", summary.semanticMemoryChars());
        recordChars("observe_prompt", summary.observePromptChars());
        recordLearningEntries("active", summary.memoryLearningActiveCount());
        recordLearningEntries("filtered", summary.memoryLearningFilteredCount());
        if (summary.memoryBankUsed()) {
            meterRegistry.counter(MEMORY_BANK_USED_METRIC).increment();
        }
    }

    private void recordChars(String source, int chars) {
        meterRegistry.summary(CONTEXT_CHARS_METRIC, "source", source)
                .record(Math.max(0, chars));
    }

    private void recordLearningEntries(String status, int count) {
        meterRegistry.summary(LEARNING_ENTRIES_METRIC, "status", status)
                .record(Math.max(0, count));
    }
}
