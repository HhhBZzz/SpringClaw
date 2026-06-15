package com.springclaw.service.usage;

import com.springclaw.domain.entity.LlmUsageRecord;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Records LLM usage as low-cardinality numeric metrics.
 */
@Service
public class LlmUsageMetricsService {

    private static final String USAGE_RESPONSE_METRIC = "springclaw.ai.usage.responses";
    private static final String USAGE_TOKEN_METRIC = "springclaw.ai.usage.tokens";
    private static final String PROMPT_CACHE_RECORD_METRIC = "springclaw.ai.prompt_cache.records";

    private final MeterRegistry meterRegistry;
    private final boolean enabled;

    public LlmUsageMetricsService(MeterRegistry meterRegistry,
                                  @Value("${springclaw.ai.usage.metrics-enabled:true}") boolean enabled) {
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
    }

    public void record(LlmUsageRecord record) {
        if (!enabled || meterRegistry == null || record == null) {
            return;
        }
        String usageStatus = record.getUsageKnown() != null && record.getUsageKnown() == 1 ? "known" : "unknown";
        meterRegistry.counter(USAGE_RESPONSE_METRIC, "usage", usageStatus).increment();

        recordTokens("prompt", record.getPromptTokens());
        recordTokens("prompt_cache_hit", record.getPromptCacheHitTokens());
        recordTokens("prompt_cache_miss", record.getPromptCacheMissTokens());
        recordTokens("completion", record.getCompletionTokens());
        recordTokens("total", record.getTotalTokens());

        String promptCacheStatus = record.getPromptCacheHitTokens() != null || record.getPromptCacheMissTokens() != null
                ? "known"
                : "unknown";
        meterRegistry.counter(PROMPT_CACHE_RECORD_METRIC, "status", promptCacheStatus).increment();
    }

    private void recordTokens(String kind, Integer tokens) {
        if (tokens == null || tokens <= 0) {
            return;
        }
        meterRegistry.summary(USAGE_TOKEN_METRIC, "kind", kind).record(tokens);
    }
}
