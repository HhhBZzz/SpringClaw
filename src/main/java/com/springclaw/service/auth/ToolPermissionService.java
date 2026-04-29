package com.springclaw.service.auth;

/**
 * 工具权限服务。
 */
public interface ToolPermissionService {

    /**
     * 校验当前用户是否允许调用指定工具。
     */
    void checkPermission(String userId, String toolName);
}

