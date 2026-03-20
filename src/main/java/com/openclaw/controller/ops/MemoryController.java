package com.openclaw.controller.ops;

import com.openclaw.common.exception.BusinessException;
import com.openclaw.common.response.ApiResponse;
import com.openclaw.service.memory.MemoryService;
import com.openclaw.service.memory.impl.VectorMemoryService;
import com.openclaw.web.auth.RequireRole;
import org.springframework.ai.document.Document;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记忆检索接口（用于联调与演示）。
 */
@RestController
@RequestMapping("/api/memory")
@RequireRole({"ADMIN"})
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping("/search")
    public ApiResponse<List<MemoryHit>> search(@RequestParam String query,
                                               @RequestParam(required = false) String sessionKey,
                                               @RequestParam(required = false) String userId,
                                               @RequestParam(defaultValue = "6") int topK) {
        if (!StringUtils.hasText(sessionKey) && !StringUtils.hasText(userId)) {
            throw new BusinessException(40071, "sessionKey 和 userId 至少传一个");
        }
        int safeTopK = Math.max(1, Math.min(topK, 20));

        List<Document> docs = new ArrayList<>();
        if (StringUtils.hasText(sessionKey)) {
            docs.addAll(memoryService.recallBySession(sessionKey.trim(), query, safeTopK));
        }
        if (StringUtils.hasText(userId)) {
            docs.addAll(memoryService.recallByUser(userId.trim(), query, safeTopK));
        }

        Set<String> dedup = new LinkedHashSet<>();
        List<MemoryHit> hits = new ArrayList<>();
        for (Document doc : docs) {
            String key = doc.getId() + "|" + doc.getText();
            if (dedup.add(key)) {
                hits.add(new MemoryHit(doc.getId(), doc.getText(), doc.getMetadata()));
            }
        }
        if (hits.size() > safeTopK) {
            hits = hits.subList(0, safeTopK);
        }

        return ApiResponse.success(hits);
    }

    @GetMapping("/status")
    public ApiResponse<MemoryStatus> status() {
        if (memoryService instanceof VectorMemoryService vectorMemoryService) {
            VectorMemoryService.MemoryRuntimeStatus runtimeStatus = vectorMemoryService.runtimeStatus();
            return ApiResponse.success(new MemoryStatus(
                    runtimeStatus.vectorEnabled(),
                    runtimeStatus.vectorStoreAvailable(),
                    runtimeStatus.vectorStoreType(),
                    runtimeStatus.activeMode(),
                    runtimeStatus.lastWriteRoute(),
                    runtimeStatus.lastRecallRoute(),
                    runtimeStatus.vectorWriteSuccessCount(),
                    runtimeStatus.localWriteFallbackCount(),
                    runtimeStatus.vectorRecallHitCount(),
                    runtimeStatus.localRecallHitCount(),
                    runtimeStatus.vectorFailureCount(),
                    runtimeStatus.localSessionCount(),
                    runtimeStatus.localDocumentCount(),
                    runtimeStatus.localMaxTurns()
            ));
        }
        return ApiResponse.success(new MemoryStatus(
                false,
                false,
                memoryService.getClass().getName(),
                "unknown",
                "UNKNOWN",
                "UNKNOWN",
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0
        ));
    }

    public record MemoryHit(String id, String text, Map<String, Object> metadata) {
    }

    public record MemoryStatus(boolean vectorEnabled,
                               boolean vectorStoreAvailable,
                               String vectorStoreType,
                               String activeMode,
                               String lastWriteRoute,
                               String lastRecallRoute,
                               long vectorWriteSuccessCount,
                               long localWriteFallbackCount,
                               long vectorRecallHitCount,
                               long localRecallHitCount,
                               long vectorFailureCount,
                               int localSessionCount,
                               int localDocumentCount,
                               int localMaxTurns) {
    }
}
