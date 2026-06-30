package com.springclaw.service.memory.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryEffectivenessRedlineReportServiceTest {

    @Test
    void shouldExposeDeterministicMemoryEffectivenessRedlineReport() {
        MemoryEffectivenessRedlineReportService service =
                new MemoryEffectivenessRedlineReportService();

        MemoryEffectivenessRedlineReport report = service.evaluate();

        assertThat(report.schema()).isEqualTo("springclaw.memory-effectiveness-redline.v1");
        assertThat(report.total()).isEqualTo(10);
        assertThat(report.passed()).isEqualTo(10);
        assertThat(report.failed()).isZero();
        assertThat(report.evaluatedAt()).isNotNull();
        assertThat(report.cases())
                .extracting(MemoryEffectivenessRedlineReportCase::caseId)
                .containsExactly(
                        "cross_session_preference_recall",
                        "conflict_replacement",
                        "irrelevant_memory_rejection",
                        "selective_forgetting",
                        "token_budget_saturation",
                        "source_evidence_preservation",
                        "hypothetical_statement_rejection",
                        "sensitive_data_non_write",
                        "stale_vector_hit_rejection",
                        "injected_memory_usage_trace"
                );
        assertThat(report.cases())
                .extracting(MemoryEffectivenessRedlineReportCase::passed)
                .containsOnly(true);
        assertThat(report.cases())
                .extracting(MemoryEffectivenessRedlineReportCase::evidence)
                .allSatisfy(evidence -> assertThat((List<String>) evidence).isNotEmpty());
    }
}
