package com.springclaw.controller;

import com.springclaw.common.response.ApiResponse;
import com.springclaw.dto.auth.AuthLoginRequest;
import com.springclaw.dto.auth.AuthProfileResponse;
import com.springclaw.dto.auth.AuthRegisterRequest;
import com.springclaw.dto.auth.AuthTokenResponse;
import com.springclaw.mapper.UserAccountMapper;
import com.springclaw.service.auth.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（简化版）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserAccountMapper userAccountMapper;
    private final boolean authEnabled;
    private final boolean dbEnabled;
    private final boolean bootstrapFirstUserAdmin;

    public AuthController(AuthService authService,
                          UserAccountMapper userAccountMapper,
                          @Value("${springclaw.auth.enabled:true}") boolean authEnabled,
                          @Value("${springclaw.persistence.db-enabled:false}") boolean dbEnabled,
                          @Value("${springclaw.auth.bootstrap-first-user-admin:true}") boolean bootstrapFirstUserAdmin) {
        this.authService = authService;
        this.userAccountMapper = userAccountMapper;
        this.authEnabled = authEnabled;
        this.dbEnabled = dbEnabled;
        this.bootstrapFirstUserAdmin = bootstrapFirstUserAdmin;
    }

    @PostMapping("/register")
    public ApiResponse<AuthTokenResponse> register(@Valid @RequestBody AuthRegisterRequest request) {
        AuthService.LoginSession session = authService.register(request.username(), request.password());
        return ApiResponse.success(toTokenResponse(session));
    }

    @PostMapping("/login")
    public ApiResponse<AuthTokenResponse> login(@Valid @RequestBody AuthLoginRequest request) {
        AuthService.LoginSession session = authService.login(request.username(), request.password());
        return ApiResponse.success(toTokenResponse(session));
    }

    @GetMapping("/me")
    public ApiResponse<AuthProfileResponse> me(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestParam(value = "token", required = false) String token) {
        String resolvedToken = resolveToken(authorization, token);
        AuthService.UserIdentity identity = authService.authenticateToken(resolvedToken);
        return ApiResponse.success(new AuthProfileResponse(identity.username(), identity.roleCode(), identity.expireAt()));
    }

    @GetMapping("/bootstrap-status")
    public ApiResponse<BootstrapStatusResponse> bootstrapStatus() {
        long userCount = -1L;
        if (dbEnabled) {
            try {
                userCount = userAccountMapper.selectCount(null);
            } catch (Exception ignored) {
                userCount = -1L;
            }
        }
        boolean hasUsers = userCount > 0;
        return ApiResponse.success(new BootstrapStatusResponse(
                authEnabled,
                dbEnabled,
                bootstrapFirstUserAdmin,
                userCount,
                hasUsers,
                !hasUsers && bootstrapFirstUserAdmin
        ));
    }

    private AuthTokenResponse toTokenResponse(AuthService.LoginSession session) {
        return new AuthTokenResponse(
                session.token(),
                session.username(),
                session.roleCode(),
                session.expireAt()
        );
    }

    private String resolveToken(String authorization, String queryToken) {
        if (StringUtils.hasText(queryToken)) {
            return queryToken.trim();
        }
        if (!StringUtils.hasText(authorization)) {
            return "";
        }
        String text = authorization.trim();
        if (text.toLowerCase().startsWith("bearer ")) {
            return text.substring(7).trim();
        }
        return text;
    }

    public record BootstrapStatusResponse(boolean authEnabled,
                                          boolean dbEnabled,
                                          boolean bootstrapFirstUserAdmin,
                                          long userCount,
                                          boolean hasUsers,
                                          boolean firstRegistrationWillBeAdmin) {
    }
}
