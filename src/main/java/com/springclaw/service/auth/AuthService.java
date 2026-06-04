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
     * 撤销当前认证 token。
     * 空 token 或纯空白 token 为 no-op；重复撤销、未命中本地/Redis token 也按成功处理，不抛业务异常。
     */
    void revokeToken(String token);

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
