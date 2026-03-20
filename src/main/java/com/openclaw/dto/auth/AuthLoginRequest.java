package com.openclaw.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 */
public record AuthLoginRequest(
        @NotBlank(message = "username 不能为空") String username,
        @NotBlank(message = "password 不能为空") String password
) {
}

