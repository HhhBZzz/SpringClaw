package com.springclaw.controller.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.knowledge.MarkdownKnowledgeSourceService;
import com.springclaw.web.auth.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/runtime-console/knowledge-sources")
public class RuntimeKnowledgeSourceController {

    private static final String AUDIT_SCHEMA = "springclaw.knowledge-source-review.v1";
    private static final String AUDIT_SESSION_KEY = "runtime-console:knowledge-source";
    private static final String AUDIT_CHANNEL = "runtime-console";
    private static final String AUDIT_USER_ID = "admin";
    private static final String AUDIT_EVENT_TYPE = "KNOWLEDGE_SOURCE";

    private final MarkdownKnowledgeSourceService knowledgeSourceService;
    private final MessageEventService messageEventService;
    private final ObjectMapper objectMapper;

    public RuntimeKnowledgeSourceController(MarkdownKnowledgeSourceService knowledgeSourceService,
                                            MessageEventService messageEventService,
                                            ObjectMapper objectMapper) {
        this.knowledgeSourceService = knowledgeSourceService;
        this.messageEventService = messageEventService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
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
                .map(update -> {
                    auditStatusUpdate(update);
                    return ApiResponse.success(update);
                })
                .orElseThrow(() -> new BusinessException(40404, "未找到可更新的知识源: " + request.path()));
    }

    private void auditStatusUpdate(MarkdownKnowledgeSourceService.KnowledgeSourceStatusUpdate update) {
        if (update == null) {
            return;
        }
        messageEventService.recordSingle(
                AUDIT_SESSION_KEY,
                AUDIT_CHANNEL,
                AUDIT_USER_ID,
                "SYSTEM",
                AUDIT_EVENT_TYPE,
                serializeAuditPayload(update),
                ""
        );
    }

    private String serializeAuditPayload(MarkdownKnowledgeSourceService.KnowledgeSourceStatusUpdate update) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", AUDIT_SCHEMA);
        payload.put("eventType", "knowledge_source.review");
        payload.put("path", safe(update.path()));
        payload.put("previousStatus", safe(update.previousStatus()));
        payload.put("status", safe(update.status()));
        payload.put("reason", safe(update.reason()));
        payload.put("reviewedAt", safe(update.reviewedAt()));
        payload.put("contextIncluded", update.contextIncluded());
        payload.put("contextImpact", safe(update.contextImpact()));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "knowledge_source.review path=" + safe(update.path())
                    + ", status=" + safe(update.status())
                    + ", reason=" + safe(update.reason());
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
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
