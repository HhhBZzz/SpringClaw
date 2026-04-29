package com.springclaw.tool.runtime.impl;

import com.springclaw.service.event.MessageEventService;
import com.springclaw.tool.runtime.ToolAuditService;
import com.springclaw.tool.runtime.ToolExecutionContext;
import org.springframework.stereotype.Service;

/**
 * 基于事件流的工具审计实现。
 */
@Service
public class MessageEventToolAuditService implements ToolAuditService {

    private final MessageEventService messageEventService;

    public MessageEventToolAuditService(MessageEventService messageEventService) {
        this.messageEventService = messageEventService;
    }

    @Override
    public void recordInvoke(String toolName, String status, String detail, ToolExecutionContext context) {
        String sessionKey = context == null || context.sessionKey() == null ? "tool-session" : context.sessionKey();
        String channel = context == null || context.channel() == null ? "tool" : context.channel();
        String userId = context == null || context.userId() == null ? "tool-user" : context.userId();
        String requestId = context == null || context.requestId() == null ? "" : context.requestId();
        String phase = context == null || context.phase() == null ? "ACT" : context.phase();

        String content = "tool=" + toolName + ", status=" + status + ", phase=" + phase + ", detail=" + detail;
        messageEventService.recordSingle(sessionKey, channel, userId, "SYSTEM", "TOOL", content, requestId);
    }
}
