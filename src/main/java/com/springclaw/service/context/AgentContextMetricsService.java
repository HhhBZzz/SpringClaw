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
    private static final String LEDGER_COMPACTION_METRIC = "springclaw.agent.context.ledger.compaction";

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

    /** 账本压缩计量(spec 2026-11-09 §3.6):方式计数 + 前后 token/丢弃条数分布。 */
    public void recordLedgerCompaction(String method, int droppedMessages,
                                        int beforeTokens, int afterTokens) {
        if (!enabled || meterRegistry == null) {
            return;
        }
        meterRegistry.counter(LEDGER_COMPACTION_METRIC, "method", method == null ? "unknown" : method)
                .increment();
        meterRegistry.summary(LEDGER_COMPACTION_METRIC + ".tokens", "phase", "before")
                .record(Math.max(0, beforeTokens));
        meterRegistry.summary(LEDGER_COMPACTION_METRIC + ".tokens", "phase", "after")
                .record(Math.max(0, afterTokens));
        meterRegistry.summary(LEDGER_COMPACTION_METRIC + ".dropped")
                .record(Math.max(0, droppedMessages));
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
