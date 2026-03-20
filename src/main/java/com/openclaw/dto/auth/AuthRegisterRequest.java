package com.openclaw.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 */
public record AuthRegisterRequest(
        @NotBlank(message = "username 不能为空")
        @Size(min = 3, max = 64, message = "username 长度需在 3-64 之间")
        String username,
        @NotBlank(message = "password 不能为空")
        @Size(min = 6, max = 64, message = "password 长度需在 6-64 之间")
        String password
) {
}
