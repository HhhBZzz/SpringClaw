package com.springclaw.web.auth;

/**
 * 当前 HTTP 请求的认证用户上下文。
 */
public record RequestUserContext(
        String username,
        String roleCode,
        long expireAt
) {
    public boolean hasAnyRole(String... roles) {
        if (roles == null || roles.length == 0) {
            return true;
        }
        for (String role : roles) {
            if (role != null && roleCode != null && roleCode.equalsIgnoreCase(role.trim())) {
                return true;
            }
        }
        return false;
    }
}
