package com.springclaw.service.usage;

import com.springclaw.domain.entity.LlmUsageRecord;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class LlmUsageMetricsServiceTest {

    @Test
    void shouldRecordUsageAndPromptCacheMetricsWithoutHighCardinalityTags() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LlmUsageMetricsService service = new LlmUsageMetricsService(meterRegistry, true);
        LlmUsageRecord record = new LlmUsageRecord();
        record.setRequestId("req-1");
        record.setSessionKey("s-1");
        record.setUserId("u-1");
        record.setProviderId("deepseek");
        record.setModel("deepseek-v4-pro");
        record.setSource("agent-runtime-summary");
        record.setUsageKnown(1);
        record.setPromptTokens(200);
        record.setPromptCacheHitTokens(160);
        record.setPromptCacheMissTokens(40);
        record.setCompletionTokens(50);
        record.setTotalTokens(250);

        service.record(record);

        Assertions.assertEquals(1.0D, meterRegistry.counter("springclaw.ai.usage.responses", "usage", "known").count());
        Assertions.assertEquals(200.0D, tokenTotal(meterRegistry, "prompt"));
        Assertions.assertEquals(160.0D, tokenTotal(meterRegistry, "prompt_cache_hit"));
        Assertions.assertEquals(40.0D, tokenTotal(meterRegistry, "prompt_cache_miss"));
        Assertions.assertEquals(50.0D, tokenTotal(meterRegistry, "completion"));
        Assertions.assertEquals(250.0D, tokenTotal(meterRegistry, "total"));
        Assertions.assertEquals(1.0D, meterRegistry.counter("springclaw.ai.prompt_cache.records", "status", "known").count());
        meterRegistry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .forEach(tag -> Assertions.assertFalse(
                        tag.getKey().equals("requestId")
                                || tag.getKey().equals("sessionKey")
                                || tag.getKey().equals("userId")
                                || tag.getKey().equals("provider")
                                || tag.getKey().equals("model")
                                || tag.getKey().equals("source"),
                        () -> "metric should not expose unstable tag: " + tag
                ));
    }

    @Test
    void shouldRecordUnknownPromptCacheWhenProviderDoesNotReturnCacheUsage() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LlmUsageMetricsService service = new LlmUsageMetricsService(meterRegistry, true);
        LlmUsageRecord record = new LlmUsageRecord();
        record.setUsageKnown(0);

        service.record(record);

        Assertions.assertEquals(1.0D, meterRegistry.counter("springclaw.ai.usage.responses", "usage", "unknown").count());
        Assertions.assertEquals(1.0D, meterRegistry.counter("springclaw.ai.prompt_cache.records", "status", "unknown").count());
    }

    @Test
    void shouldSkipRecordingWhenMetricsAreDisabled() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LlmUsageMetricsService service = new LlmUsageMetricsService(meterRegistry, false);
        LlmUsageRecord record = new LlmUsageRecord();
        record.setUsageKnown(1);
        record.setPromptTokens(200);

        service.record(record);

        Assertions.assertTrue(meterRegistry.getMeters().isEmpty());
    }

    private double tokenTotal(SimpleMeterRegistry meterRegistry, String kind) {
        return meterRegistry.get("springclaw.ai.usage.tokens")
                .tag("kind", kind)
                .summary()
                .totalAmount();
    }
}
