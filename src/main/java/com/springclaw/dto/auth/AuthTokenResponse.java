package com.springclaw.dto.auth;

/**
 * 登录/注册返回。
 */
public record AuthTokenResponse(
        String token,
        String username,
        String roleCode,
        long expireAt
) {
}

