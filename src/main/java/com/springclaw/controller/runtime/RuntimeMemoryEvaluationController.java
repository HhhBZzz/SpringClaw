package com.springclaw.controller.runtime;

import com.springclaw.common.response.ApiResponse;
import com.springclaw.service.memory.evaluation.MemoryEffectivenessRedlineReport;
import com.springclaw.service.memory.evaluation.MemoryEffectivenessRedlineReportService;
import com.springclaw.service.memory.evaluation.MemoryProviderEvaluationHarnessService;
import com.springclaw.service.memory.evaluation.MemoryProviderEvaluationReport;
import com.springclaw.service.memory.evaluation.RuntimeEvaluationHistoryService;
import com.springclaw.service.memory.evaluation.RuntimeEvaluationRun;
import com.springclaw.service.memory.evaluation.RuntimeEvaluationStatusSummary;
import com.springclaw.web.auth.RequireRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/runtime-console/memory/evaluation")
public class RuntimeMemoryEvaluationController {

    private static final Logger log =
            LoggerFactory.getLogger(RuntimeMemoryEvaluationController.class);

    private final MemoryEffectivenessRedlineReportService reportService;
    private final MemoryProviderEvaluationHarnessService providerEvaluationHarnessService;
    private final RuntimeEvaluationHistoryService historyService;

    public RuntimeMemoryEvaluationController(
            MemoryEffectivenessRedlineReportService reportService,
            MemoryProviderEvaluationHarnessService providerEvaluationHarnessService,
            RuntimeEvaluationHistoryService historyService
    ) {
        this.reportService = reportService;
        this.providerEvaluationHarnessService = providerEvaluationHarnessService;
        this.historyService = historyService;
    }

    @GetMapping("/redline")
    @RequireRole({"ADMIN"})
    public ApiResponse<MemoryEffectivenessRedlineReport> redlineReport() {
        MemoryEffectivenessRedlineReport report = reportService.evaluate();
        try {
            historyService.recordRedline(report);
        } catch (RuntimeException ex) {
            log.warn("failed to persist memory redline evaluation, reason={}",
                    ex.getMessage());
        }
        return ApiResponse.success(report);
    }

    @GetMapping("/provider-harness")
    @RequireRole({"ADMIN"})
    public ApiResponse<MemoryProviderEvaluationReport> providerHarnessReport() {
        MemoryProviderEvaluationReport report =
                providerEvaluationHarnessService.evaluate();
        try {
            historyService.recordProviderHarness(report);
        } catch (RuntimeException ex) {
            log.warn("failed to persist memory provider evaluation, reason={}",
                    ex.getMessage());
        }
        return ApiResponse.success(report);
    }

    @GetMapping("/history")
    @RequireRole({"ADMIN"})
    public ApiResponse<List<RuntimeEvaluationRun>> evaluationHistory(
            @RequestParam String type,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.success(historyService.history(type, limit));
    }

    @GetMapping("/latest")
    @RequireRole({"ADMIN"})
    public ApiResponse<RuntimeEvaluationRun> latestEvaluation(
            @RequestParam String type
    ) {
        return ApiResponse.success(historyService.latest(type).orElse(null));
    }

    @GetMapping("/summary")
    @RequireRole({"ADMIN"})
    public ApiResponse<RuntimeEvaluationStatusSummary> evaluationSummary() {
        return ApiResponse.success(historyService.summary());
    }
}
