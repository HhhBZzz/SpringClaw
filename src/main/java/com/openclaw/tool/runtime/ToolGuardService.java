package com.openclaw.tool.runtime;

/**
 * 工具调用防护服务。
 */
public interface ToolGuardService {

    void checkRateLimit(String toolName);
}
