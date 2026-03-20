package com.openclaw.tool.runtime;

/**
 * 工具执行上下文。
 */
public record ToolExecutionContext(
        String sessionKey,
        String channel,
        String userId,
        String requestId,
        String phase
) {
}
