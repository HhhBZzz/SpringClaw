package com.springclaw.controller.runtime;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.service.memory.review.MemoryCandidateReviewService;
import com.springclaw.web.auth.RequireRole;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/runtime-console/memory/candidates")
public class RuntimeMemoryCandidateController {

    private final MemoryCandidateReviewService reviewService;

    public RuntimeMemoryCandidateController(MemoryCandidateReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    @RequireRole({"ADMIN"})
    public ApiResponse<List<MemoryCandidateReviewService.MemoryCandidateReviewItem>> memoryCandidates(
            @RequestParam(defaultValue = "CANDIDATE") String status,
            @RequestParam(defaultValue = "50") int limit
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return ApiResponse.success(reviewService.list(status, safeLimit));
    }

    @PostMapping("/status")
    @RequireRole({"ADMIN"})
    public ApiResponse<MemoryCandidateReviewService.MemoryCandidateStatusUpdate> updateCandidateStatus(
            @RequestBody UpdateMemoryCandidateStatusRequest request
    ) {
        if (request == null
                || !StringUtils.hasText(request.memoryVersionId())
                || !StringUtils.hasText(request.status())) {
            throw new BusinessException(40103, "memoryVersionId 和 status 不能为空");
        }
        try {
            return ApiResponse.success(reviewService.updateStatus(
                    request.memoryVersionId(),
                    request.status(),
                    request.reason()
            ));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            throw new BusinessException(40404, ex.getMessage());
        }
    }

    public record UpdateMemoryCandidateStatusRequest(
            String memoryVersionId,
            String status,
            String reason
    ) {
    }
}
