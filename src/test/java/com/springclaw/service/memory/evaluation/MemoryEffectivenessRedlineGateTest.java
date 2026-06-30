package com.springclaw.service.memory.evaluation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryEffectivenessRedlineGateTest {

    @Test
    void memoryEffectivenessRedlinesMustPassForMemoryChanges() {
        MemoryEffectivenessRedlineReportService service =
                new MemoryEffectivenessRedlineReportService();

        MemoryEffectivenessRedlineReport report = service.evaluate();

        assertThat(report.failed()).isZero();
        assertThat(report.passed()).isEqualTo(report.total());
        assertThat(report.cases())
                .allSatisfy(item -> {
                    assertThat(item.passed())
                            .describedAs(item.caseId() + " must pass: " + item.summary())
                            .isTrue();
                    assertThat(item.evidence())
                            .describedAs(item.caseId() + " must keep evidence")
                            .isNotEmpty();
                });
    }
}
