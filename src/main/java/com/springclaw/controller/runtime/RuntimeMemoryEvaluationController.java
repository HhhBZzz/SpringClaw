package com.springclaw.controller.runtime;

import com.springclaw.common.response.ApiResponse;
import com.springclaw.service.memory.evaluation.MemoryEffectivenessRedlineReport;
import com.springclaw.service.memory.evaluation.MemoryEffectivenessRedlineReportService;
import com.springclaw.service.memory.evaluation.MemoryProviderEvaluationHarnessService;
import com.springclaw.service.memory.evaluation.MemoryProviderEvaluationReport;
import com.springclaw.web.auth.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime-console/memory/evaluation")
public class RuntimeMemoryEvaluationController {

    private final MemoryEffectivenessRedlineReportService reportService;
    private final MemoryProviderEvaluationHarnessService providerEvaluationHarnessService;

    public RuntimeMemoryEvaluationController(
            MemoryEffectivenessRedlineReportService reportService,
            MemoryProviderEvaluationHarnessService providerEvaluationHarnessService
    ) {
        this.reportService = reportService;
        this.providerEvaluationHarnessService = providerEvaluationHarnessService;
    }

    @GetMapping("/redline")
    @RequireRole({"ADMIN"})
    public ApiResponse<MemoryEffectivenessRedlineReport> redlineReport() {
        return ApiResponse.success(reportService.evaluate());
    }

    @GetMapping("/provider-harness")
    @RequireRole({"ADMIN"})
    public ApiResponse<MemoryProviderEvaluationReport> providerHarnessReport() {
        return ApiResponse.success(providerEvaluationHarnessService.evaluate());
    }
}
