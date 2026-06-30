package com.springclaw.controller.runtime;

import com.springclaw.common.response.ApiResponse;
import com.springclaw.service.memory.evaluation.MemoryEffectivenessRedlineReport;
import com.springclaw.service.memory.evaluation.MemoryEffectivenessRedlineReportCase;
import com.springclaw.service.memory.evaluation.MemoryEffectivenessRedlineReportService;
import com.springclaw.service.memory.evaluation.MemoryProviderEvaluationCase;
import com.springclaw.service.memory.evaluation.MemoryProviderEvaluationHarnessService;
import com.springclaw.service.memory.evaluation.MemoryProviderEvaluationReport;
import com.springclaw.web.auth.RequireRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeMemoryEvaluationControllerTest {

    @Test
    void shouldExposeMemoryEffectivenessRedlineReportThroughRuntimeConsole() {
        MemoryEffectivenessRedlineReportService service =
                mock(MemoryEffectivenessRedlineReportService.class);
        MemoryProviderEvaluationHarnessService providerEvaluationService =
                mock(MemoryProviderEvaluationHarnessService.class);
        RuntimeMemoryEvaluationController controller =
                new RuntimeMemoryEvaluationController(service, providerEvaluationService);
        MemoryEffectivenessRedlineReport report = new MemoryEffectivenessRedlineReport(
                "springclaw.memory-effectiveness-redline.v1",
                1,
                1,
                0,
                List.of(new MemoryEffectivenessRedlineReportCase(
                        "cross_session_preference_recall",
                        "Cross-session preference recall",
                        true,
                        "Recalled active user preference with evidence.",
                        List.of("message_event:run-old:user")
                )),
                Instant.parse("2026-06-30T00:00:00Z")
        );
        when(service.evaluate()).thenReturn(report);

        ApiResponse<MemoryEffectivenessRedlineReport> response =
                controller.redlineReport();

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).isEqualTo(report);
        verify(service).evaluate();
    }

    @Test
    void shouldExposeProviderEvaluationHarnessThroughRuntimeConsole() {
        MemoryEffectivenessRedlineReportService redlineService =
                mock(MemoryEffectivenessRedlineReportService.class);
        MemoryProviderEvaluationHarnessService providerEvaluationService =
                mock(MemoryProviderEvaluationHarnessService.class);
        RuntimeMemoryEvaluationController controller =
                new RuntimeMemoryEvaluationController(redlineService, providerEvaluationService);
        MemoryProviderEvaluationReport report = new MemoryProviderEvaluationReport(
                "springclaw.memory-provider-evaluation.v1",
                true,
                1,
                1,
                0,
                0,
                List.of(new MemoryProviderEvaluationCase(
                        "provider_semantic_extraction_json",
                        "Provider semantic extraction JSON",
                        "PASSED",
                        "Extractor returned valid JSON.",
                        List.of("chat:provider-eval-run-1:user")
                )),
                Instant.parse("2026-06-30T00:00:00Z")
        );
        when(providerEvaluationService.evaluate()).thenReturn(report);

        ApiResponse<MemoryProviderEvaluationReport> response =
                controller.providerHarnessReport();

        assertThat(response.getCode()).isZero();
        assertThat(response.getData()).isEqualTo(report);
        verify(providerEvaluationService).evaluate();
    }

    @Test
    void redlineReportEndpointShouldRequireAdminRole() throws Exception {
        Method method = RuntimeMemoryEvaluationController.class.getMethod("redlineReport");
        Method providerHarness = RuntimeMemoryEvaluationController.class.getMethod(
                "providerHarnessReport"
        );

        RequireRole requireRole = method.getAnnotation(RequireRole.class);
        RequireRole providerHarnessRole = providerHarness.getAnnotation(RequireRole.class);

        assertThat(requireRole).isNotNull();
        assertThat(requireRole.value()).containsExactly("ADMIN");
        assertThat(providerHarnessRole).isNotNull();
        assertThat(providerHarnessRole.value()).containsExactly("ADMIN");
    }
}
