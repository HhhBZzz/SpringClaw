package com.springclaw.service.memory.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(
        named = "SPRINGCLAW_MEMORY_PROVIDER_EVALUATION_IT",
        matches = "true"
)
@SpringBootTest(properties = {
        "springclaw.memory.evaluation.provider-harness-enabled=true",
        "springclaw.redisson.enabled=false",
        "springclaw.ai.state.redis-enabled=false",
        "springclaw.persistence.db-enabled=false",
        "springclaw.runtime.lifecycle.schema-auto-init=false",
        "springclaw.runtime-console.schema-auto-init=false",
        "springclaw.memory.core.schema-auto-init=false",
        "springclaw.tool-proposal.schema-auto-init=false"
})
class MemoryProviderEvaluationHarnessManualIT {

    @Autowired
    private MemoryProviderEvaluationHarnessService service;

    @Test
    void configuredProvidersShouldPassMemoryEvaluationHarness() {
        MemoryProviderEvaluationReport report = service.evaluate();

        assertThat(report.enabled()).isTrue();
        assertThat(report.total()).isEqualTo(3);
        assertThat(report.failed()).isZero();
        assertThat(report.skipped()).isZero();
        assertThat(report.passed()).isEqualTo(report.total());
    }
}
