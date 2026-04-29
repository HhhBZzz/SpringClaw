package com.springclaw.service.auth;

import java.util.List;

/**
 * 认证与身份服务。
 */
public interface AuthService {

    LoginSession register(String username, String rawPassword);

    LoginSession login(String username, String rawPassword);

    UserIdentity authenticateToken(String token);

    /**
     * 根据业务 userId 解析角色。
     * 约定：默认将 userId 视作 username。
     */
    String resolveRoleByUserId(String userId);

    TokenRuntimeStats tokenRuntimeStats();

    List<ActiveSession> listActiveSessions(int limit);

    record LoginSession(String token, String username, String roleCode, long expireAt) {
    }

    record UserIdentity(String username, String roleCode, long expireAt) {
    }

    record TokenRuntimeStats(long activeTokenCount,
                             long activeUserCount,
                             long redisTokenCount,
                             long localTokenCount,
                             long expiringSoonCount,
                             boolean redisBacked,
                             long tokenTtlSeconds) {
    }

    record ActiveSession(String username,
                         String roleCode,
                         long expireAt,
                         String tokenPreview,
                         String storage) {
    }
}
