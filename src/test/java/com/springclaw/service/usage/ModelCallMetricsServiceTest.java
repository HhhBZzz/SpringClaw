package com.springclaw.service.usage;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ModelCallMetricsServiceTest {

    @Test
    void shouldRecordModelCallOutcomeDurationFallbackAndRetryMetrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ModelCallMetricsService service = new ModelCallMetricsService(meterRegistry, true);

        service.record(new ModelCallMetricsService.ModelCallMetric(
                true,
                42,
                1,
                2
        ));

        Assertions.assertEquals(1.0D, meterRegistry.counter(
                "springclaw.ai.model.calls",
                "outcome", "success",
                "failover", "used",
                "retry", "used"
        ).count());
        Assertions.assertEquals(1L, meterRegistry.get("springclaw.ai.model.call.duration")
                .tag("outcome", "success")
                .summary()
                .count());
        Assertions.assertEquals(1.0D, meterRegistry.counter("springclaw.ai.model.failovers").count());
        Assertions.assertEquals(2.0D, meterRegistry.counter("springclaw.ai.model.retries").count());
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
    void shouldRecordFailureWithoutFallbackOrRetry() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ModelCallMetricsService service = new ModelCallMetricsService(meterRegistry, true);

        service.record(new ModelCallMetricsService.ModelCallMetric(false, 7, 0, 0));

        Assertions.assertEquals(1.0D, meterRegistry.counter(
                "springclaw.ai.model.calls",
                "outcome", "failure",
                "failover", "none",
                "retry", "none"
        ).count());
        Assertions.assertEquals(1L, meterRegistry.get("springclaw.ai.model.call.duration")
                .tag("outcome", "failure")
                .summary()
                .count());
    }

    @Test
    void shouldSkipRecordingWhenMetricsAreDisabled() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ModelCallMetricsService service = new ModelCallMetricsService(meterRegistry, false);

        service.record(new ModelCallMetricsService.ModelCallMetric(true, 42, 1, 1));

        Assertions.assertTrue(meterRegistry.getMeters().isEmpty());
    }
}
