package com.springclaw.controller.runtime;

import com.springclaw.common.response.ApiResponse;
import com.springclaw.service.memory.evaluation.MemoryEffectivenessRedlineReport;
import com.springclaw.service.memory.evaluation.MemoryEffectivenessRedlineReportService;
import com.springclaw.web.auth.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime-console/memory/evaluation")
public class RuntimeMemoryEvaluationController {

    private final MemoryEffectivenessRedlineReportService reportService;

    public RuntimeMemoryEvaluationController(
            MemoryEffectivenessRedlineReportService reportService
    ) {
        this.reportService = reportService;
    }

    @GetMapping("/redline")
    @RequireRole({"ADMIN"})
    public ApiResponse<MemoryEffectivenessRedlineReport> redlineReport() {
        return ApiResponse.success(reportService.evaluate());
    }
}
