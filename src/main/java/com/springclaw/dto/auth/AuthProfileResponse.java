package com.springclaw.dto.auth;

/**
 * 当前登录态信息。
 */
public record AuthProfileResponse(
        String username,
        String roleCode,
        long expireAt
) {
}

