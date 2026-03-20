package com.openclaw.controller.ops;

import com.openclaw.common.response.ApiResponse;
import com.openclaw.service.prompt.SoulPromptService;
import com.openclaw.web.auth.RequireRole;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 轻量运维接口（本地调试场景）。
 */
@RestController
@RequestMapping("/api/admin")
@RequireRole({"ADMIN"})
public class AdminController {

    private final SoulPromptService soulPromptService;

    public AdminController(SoulPromptService soulPromptService) {
        this.soulPromptService = soulPromptService;
    }

    @PostMapping("/soul/reload")
    public ApiResponse<Map<String, String>> reloadSoul() {
        soulPromptService.reloadSoul();
        return ApiResponse.success(Map.of(
                "soulVersion", soulPromptService.soulVersion(),
                "message", "SOUL 已重新加载"
        ));
    }
}
