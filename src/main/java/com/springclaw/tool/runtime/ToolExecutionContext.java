package com.springclaw.tool.runtime;

/**
 * 工具执行上下文。
 *
 * <p>runId / roleCode 在 P0 添加，用于工具调用授权单的创建与二次校验。
 * 旧调用点用 5-arg 构造器即可，runId 和 roleCode 默认为 null。
 */
public record ToolExecutionContext(
        String sessionKey,
        String channel,
        String userId,
        String requestId,
        String phase,
        String runId,
        String roleCode
) {
    public ToolExecutionContext(String sessionKey,
                                String channel,
                                String userId,
                                String requestId,
                                String phase) {
        this(sessionKey, channel, userId, requestId, phase, null, null);
    }
}
