package com.springclaw.controller;

import com.springclaw.common.response.ApiResponse;
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
        return ApiResponse.success(Map.ofEntries(
                Map.entry("project", "springclaw-java"),
                Map.entry("status", "running"),
                Map.entry("chatEndpoint", "/api/chat/send"),
                Map.entry("streamEndpoint", "/api/chat/stream"),
                Map.entry("authLoginEndpoint", "/api/auth/login"),
                Map.entry("auditLogsEndpoint", "/api/admin/audit/logs"),
                Map.entry("frontend", "Vue 3 + Vite"),
                Map.entry("vueHomePage", "http://localhost:5173/#/"),
                Map.entry("vueAgentPage", "http://localhost:5173/#/agent"),
                Map.entry("vueAdminPage", "http://localhost:5173/#/admin"),
                Map.entry("healthEndpoint", "/actuator/health")
        ));
    }
}
