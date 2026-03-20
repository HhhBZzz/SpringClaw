package com.openclaw.tool.runtime;

/**
 * 工具审计服务。
 */
public interface ToolAuditService {

    void recordInvoke(String toolName, String status, String detail, ToolExecutionContext context);
}
