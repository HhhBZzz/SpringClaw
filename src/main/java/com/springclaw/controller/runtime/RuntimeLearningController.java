package com.springclaw.controller.runtime;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.service.memory.AgentLearningService;
import com.springclaw.web.auth.RequireRole;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime-console/learning")
public class RuntimeLearningController {

    private final AgentLearningService agentLearningService;

    public RuntimeLearningController(AgentLearningService agentLearningService) {
        this.agentLearningService = agentLearningService;
    }

    @PostMapping("/status")
    @RequireRole({"ADMIN"})
    public ApiResponse<AgentLearningService.AgentLearningStatusUpdate> updateLearningStatus(
            @RequestBody UpdateLearningStatusRequest request) {
        if (request == null
                || !StringUtils.hasText(request.signature())
                || !StringUtils.hasText(request.status())) {
            throw new BusinessException(40103, "signature 和 status 不能为空");
        }
        return agentLearningService.updateStatus(request.signature(), request.status(), request.reason())
                .map(ApiResponse::success)
                .orElseThrow(() -> new BusinessException(40404, "未找到可更新的学习条目: " + request.signature()));
    }

    public record UpdateLearningStatusRequest(String signature, String status, String reason) {
    }
}
