package com.springclaw.controller.runtime;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.service.knowledge.MarkdownKnowledgeSourceService;
import com.springclaw.web.auth.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/runtime-console/knowledge-sources")
public class RuntimeKnowledgeSourceController {

    private final MarkdownKnowledgeSourceService knowledgeSourceService;

    public RuntimeKnowledgeSourceController(MarkdownKnowledgeSourceService knowledgeSourceService) {
        this.knowledgeSourceService = knowledgeSourceService;
    }

    @GetMapping
    @RequireRole({"ADMIN"})
    public ApiResponse<List<MarkdownKnowledgeSourceService.KnowledgeSourceReviewItem>> knowledgeSources(
            @RequestParam(defaultValue = "50") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return ApiResponse.success(knowledgeSourceService.listSources(safeLimit));
    }

    @PostMapping("/status")
    @RequireRole({"ADMIN"})
    public ApiResponse<MarkdownKnowledgeSourceService.KnowledgeSourceStatusUpdate> updateStatus(
            @RequestBody UpdateKnowledgeSourceStatusRequest request) {
        if (request == null
                || !StringUtils.hasText(request.path())
                || !StringUtils.hasText(request.status())) {
            throw new BusinessException(40103, "path 和 status 不能为空");
        }
        return knowledgeSourceService.updateStatus(request.path(), request.status(), request.reason())
                .map(ApiResponse::success)
                .orElseThrow(() -> new BusinessException(40404, "未找到可更新的知识源: " + request.path()));
    }

    @GetMapping("/snapshot")
    @RequireRole({"ADMIN"})
    public ApiResponse<KnowledgeSourceSnapshotPreview> snapshot() {
        MarkdownKnowledgeSourceService.KnowledgeSourceSnapshot snapshot = knowledgeSourceService.renderSnapshot();
        return ApiResponse.success(new KnowledgeSourceSnapshotPreview(
                snapshot.context(),
                snapshot.includedCount(),
                snapshot.filteredCount(),
                snapshot.context().length(),
                false,
                "review_only_not_runtime_prompt"
        ));
    }

    public record KnowledgeSourceSnapshotPreview(String contextPreview,
                                                 int includedCount,
                                                 int filteredCount,
                                                 int contextChars,
                                                 boolean injectedToRuntimePrompt,
                                                 String contextPolicy) {
    }

    public record UpdateKnowledgeSourceStatusRequest(String path, String status, String reason) {
    }
}
