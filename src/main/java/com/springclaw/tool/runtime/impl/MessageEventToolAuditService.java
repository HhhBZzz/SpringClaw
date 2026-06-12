package com.springclaw.tool.runtime.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.agent.AgentRunTraceService;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.tool.runtime.CapabilityRegistry;
import com.springclaw.tool.runtime.ToolAuditService;
import com.springclaw.tool.runtime.ToolExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于事件流的工具审计实现。
 */
@Service
public class MessageEventToolAuditService implements ToolAuditService {

    private static final String SCHEMA = "springclaw.tool-audit.v1";

    private final MessageEventService messageEventService;
    private final AgentRunTraceService agentRunTraceService;
    private final CapabilityRegistry capabilityRegistry;
    private final ObjectMapper objectMapper;

    @Autowired
    public MessageEventToolAuditService(MessageEventService messageEventService,
                                        @Autowired(required = false) AgentRunTraceService agentRunTraceService,
                                        @Autowired(required = false) CapabilityRegistry capabilityRegistry,
                                        ObjectMapper objectMapper) {
        this.messageEventService = messageEventService;
        this.agentRunTraceService = agentRunTraceService;
        this.capabilityRegistry = capabilityRegistry;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    MessageEventToolAuditService(MessageEventService messageEventService,
                                 AgentRunTraceService agentRunTraceService) {
        this(messageEventService, agentRunTraceService, null, new ObjectMapper());
    }

    // Package-private constructor for tests that want to inject a CapabilityRegistry directly.
    MessageEventToolAuditService(MessageEventService messageEventService,
                                 AgentRunTraceService agentRunTraceService,
                                 CapabilityRegistry capabilityRegistry) {
        this(messageEventService, agentRunTraceService, capabilityRegistry, new ObjectMapper());
    }

    @Override
    public void recordInvoke(String toolName, String status, String detail, ToolExecutionContext context) {
        String sessionKey = context == null || context.sessionKey() == null ? "tool-session" : context.sessionKey();
        String channel = context == null || context.channel() == null ? "tool" : context.channel();
        String userId = context == null || context.userId() == null ? "tool-user" : context.userId();
        String requestId = context == null || context.requestId() == null ? "" : context.requestId();
        String phase = context == null || context.phase() == null ? "ACT" : context.phase();

        String normalizedStatus = normalizeStatus(status);
        WorkspaceGuardAuditDetail guardDetail = parseWorkspaceGuardDetail(detail);
        String effectiveDetail = guardDetail == null ? detail : guardDetail.message();
        String summary = "tool=" + toolName + ", status=" + status + ", phase=" + phase + ", detail=" + effectiveDetail;
        String content = serialize(buildPayload(toolName, status, normalizedStatus, effectiveDetail, sessionKey, channel, userId, requestId, phase, summary, guardDetail), summary);
        messageEventService.recordSingle(sessionKey, channel, userId, "SYSTEM", "TOOL", content, requestId);
        if (agentRunTraceService != null && StringUtils.hasText(requestId)) {
            agentRunTraceService.record(
                    sessionKey,
                    channel,
                    userId,
                    requestId,
                    toolName,
                    "tool",
                    normalizedStatus,
                    content,
                    0L
            );
        }
    }

    private String normalizeStatus(String status) {
        if ("FAILED".equalsIgnoreCase(status) || "DENIED".equalsIgnoreCase(status)) {
            return "failed";
        }
        if ("START".equalsIgnoreCase(status)) {
            return "started";
        }
        return "success";
    }

    private Map<String, Object> buildPayload(String toolName,
                                             String status,
                                             String normalizedStatus,
                                             String detail,
                                             String sessionKey,
                                             String channel,
                                             String userId,
                                             String requestId,
                                             String phase,
                                             String summary,
                                             WorkspaceGuardAuditDetail guardDetail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", SCHEMA);
        payload.put("eventType", "tool.invoke");
        payload.put("toolName", safe(toolName));
        payload.put("toolset", resolveToolset(toolName));
        payload.put("status", safe(status));
        payload.put("normalizedStatus", safe(normalizedStatus));
        payload.put("phase", safe(phase));
        payload.put("detail", safe(detail));
        payload.put("summary", safe(summary));
        payload.put("sessionKey", safe(sessionKey));
        payload.put("channel", safe(channel));
        payload.put("userId", safe(userId));
        payload.put("requestId", safe(requestId));
        if (guardDetail != null) {
            payload.put("guardAction", guardDetail.action());
            payload.put("guardReasonCode", guardDetail.reasonCode());
            payload.put("guardMessage", guardDetail.message());
            payload.put("guardResolvedPath", guardDetail.resolvedPath());
        }
        return payload;
    }

    private String serialize(Map<String, Object> payload, String fallback) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String resolveToolset(String toolName) {
        if (!StringUtils.hasText(toolName)) {
            return "";
        }
        String normalized = toolName.trim();
        int dot = normalized.indexOf('.');
        String classNamePart = dot > 0 ? normalized.substring(0, dot) : normalized;
        int bracket = classNamePart.indexOf('[');
        if (bracket > 0) {
            classNamePart = classNamePart.substring(0, bracket);
        }
        // 优先用 CapabilityRegistry 反查 descriptor.toolset()，
        // 这样 audit JSON 里的 toolset 是 "system" / "web" 而不是 "SystemToolPack" 这种类名。
        if (capabilityRegistry != null) {
            String resolved = capabilityRegistry.findToolsetByClassName(classNamePart);
            if (StringUtils.hasText(resolved)) {
                return resolved;
            }
        }
        // 回退：找不到时使用类名前缀（向后兼容；现有调用方仍能拿到一个非空 toolset）
        return classNamePart;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private WorkspaceGuardAuditDetail parseWorkspaceGuardDetail(String detail) {
        if (!StringUtils.hasText(detail) || !detail.trim().startsWith("{")) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(detail, new TypeReference<>() {
            });
            if (!"springclaw.workspace-guard.v1".equals(safeString(payload.get("schema")))) {
                return null;
            }
            return new WorkspaceGuardAuditDetail(
                    safeString(payload.get("action")),
                    safeString(payload.get("reasonCode")),
                    safeString(payload.get("message")),
                    safeString(payload.get("resolvedPath"))
            );
        } catch (Exception ex) {
            return null;
        }
    }

    private String safeString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record WorkspaceGuardAuditDetail(String action,
                                             String reasonCode,
                                             String message,
                                             String resolvedPath) {
    }
}
