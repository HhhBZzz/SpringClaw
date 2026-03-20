package com.openclaw.controller.ops;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.openclaw.common.response.ApiResponse;
import com.openclaw.domain.entity.MessageEvent;
import com.openclaw.service.event.MessageEventService;
import com.openclaw.web.auth.RequireRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计日志查询接口。
 */
@RestController
@RequestMapping("/api/admin/audit")
@RequireRole({"ADMIN"})
public class AuditController {

    private final MessageEventService messageEventService;

    public AuditController(MessageEventService messageEventService) {
        this.messageEventService = messageEventService;
    }

    @GetMapping("/logs")
    public ApiResponse<Map<String, Object>> logs(@RequestParam(required = false) String sessionKey,
                                                 @RequestParam(required = false) String userId,
                                                 @RequestParam(required = false) String role,
                                                 @RequestParam(required = false) String eventType,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        IPage<MessageEvent> result = messageEventService.pageQuery(sessionKey, userId, role, eventType, page, size);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("page", result.getCurrent());
        payload.put("size", result.getSize());
        payload.put("total", result.getTotal());
        payload.put("records", result.getRecords());
        return ApiResponse.success(payload);
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats(@RequestParam(defaultValue = "500") int recentLimit) {
        return ApiResponse.success(messageEventService.summaryStats(recentLimit));
    }
}
