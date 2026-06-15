package com.springclaw.service.context;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AgentContextMetricsServiceTest {

    @Test
    void shouldRecordContextSourceSummaryAsLowCardinalityMetrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentContextMetricsService service = new AgentContextMetricsService(meterRegistry, true);
        AssembledContext.ContextSourceSummary summary = new AssembledContext.ContextSourceSummary(
                "springclaw.context-source.v1",
                true,
                12,
                34,
                56,
                78,
                2,
                1
        );

        service.record(summary);

        Assertions.assertEquals(12.0D, contextChars(meterRegistry, "memory_bank").totalAmount());
        Assertions.assertEquals(34.0D, contextChars(meterRegistry, "short_term").totalAmount());
        Assertions.assertEquals(56.0D, contextChars(meterRegistry, "semantic_memory").totalAmount());
        Assertions.assertEquals(78.0D, contextChars(meterRegistry, "observe_prompt").totalAmount());
        Assertions.assertEquals(2.0D, learningEntries(meterRegistry, "active").totalAmount());
        Assertions.assertEquals(1.0D, learningEntries(meterRegistry, "filtered").totalAmount());
        Assertions.assertEquals(1.0D, meterRegistry.counter("springclaw.agent.context.memory_bank.used").count());
        meterRegistry.getMeters().stream()
                .map(Meter::getId)
                .flatMap(id -> id.getTags().stream())
                .forEach(tag -> Assertions.assertFalse(
                        tag.getKey().equals("requestId")
                                || tag.getKey().equals("sessionKey")
                                || tag.getKey().equals("userId"),
                        () -> "metric should not expose high-cardinality tag: " + tag
                ));
    }

    @Test
    void shouldSkipRecordingWhenMetricsAreDisabled() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentContextMetricsService service = new AgentContextMetricsService(meterRegistry, false);

        service.record(new AssembledContext.ContextSourceSummary(
                "springclaw.context-source.v1",
                true,
                12,
                34,
                56,
                78
        ));

        Assertions.assertTrue(meterRegistry.getMeters().isEmpty());
    }

    private DistributionSummary contextChars(SimpleMeterRegistry meterRegistry, String source) {
        return meterRegistry.get("springclaw.agent.context.chars")
                .tag("source", source)
                .summary();
    }

    private DistributionSummary learningEntries(SimpleMeterRegistry meterRegistry, String status) {
        return meterRegistry.get("springclaw.agent.context.learning.entries")
                .tag("status", status)
                .summary();
    }
}
