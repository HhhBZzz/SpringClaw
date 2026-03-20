package com.openclaw.controller;

import com.openclaw.common.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 项目主页接口。
 *
 * 设计说明：
 * 1. 避免用户直接访问 "/" 时出现资源不存在异常。
 * 2. 明确返回核心入口，便于本地联调和演示。
 */
@RestController
public class HomeController {

    @GetMapping("/")
    public ApiResponse<Map<String, Object>> home() {
        return ApiResponse.success(Map.of(
                "project", "openclaw-java",
                "status", "running",
                "chatEndpoint", "/api/chat/send",
                "streamEndpoint", "/api/chat/stream",
                "authLoginEndpoint", "/api/auth/login",
                "auditLogsEndpoint", "/api/admin/audit/logs",
                "adminPage", "/admin",
                "healthEndpoint", "/actuator/health"
        ));
    }
}
