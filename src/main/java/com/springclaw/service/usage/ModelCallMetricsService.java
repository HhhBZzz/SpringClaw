package com.springclaw.service.usage;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Records model call process metrics without provider/model high-cardinality tags.
 */
@Service
public class ModelCallMetricsService {

    private static final String MODEL_CALLS_METRIC = "springclaw.ai.model.calls";
    private static final String MODEL_CALL_DURATION_METRIC = "springclaw.ai.model.call.duration";
    private static final String MODEL_FAILOVERS_METRIC = "springclaw.ai.model.failovers";
    private static final String MODEL_RETRIES_METRIC = "springclaw.ai.model.retries";

    private final MeterRegistry meterRegistry;
    private final boolean enabled;

    public ModelCallMetricsService(MeterRegistry meterRegistry,
                                   @Value("${springclaw.ai.model-call.metrics-enabled:true}") boolean enabled) {
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
    }

    public void record(ModelCallMetric metric) {
        if (!enabled || meterRegistry == null || metric == null) {
            return;
        }
        String outcome = metric.success() ? "success" : "failure";
        String failover = metric.failoversUsed() > 0 ? "used" : "none";
        String retry = metric.sameModelRetriesUsed() > 0 ? "used" : "none";
        meterRegistry.counter(MODEL_CALLS_METRIC, "outcome", outcome, "failover", failover, "retry", retry)
                .increment();
        meterRegistry.summary(MODEL_CALL_DURATION_METRIC, "outcome", outcome)
                .record(Math.max(0L, metric.durationMs()));
        if (metric.failoversUsed() > 0) {
            meterRegistry.counter(MODEL_FAILOVERS_METRIC).increment(metric.failoversUsed());
        }
        if (metric.sameModelRetriesUsed() > 0) {
            meterRegistry.counter(MODEL_RETRIES_METRIC).increment(metric.sameModelRetriesUsed());
        }
    }

    public record ModelCallMetric(boolean success,
                                  long durationMs,
                                  int failoversUsed,
                                  int sameModelRetriesUsed) {
    }
}
