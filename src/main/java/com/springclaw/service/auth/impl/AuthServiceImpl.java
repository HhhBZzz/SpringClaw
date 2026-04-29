package com.springclaw.service.auth.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.domain.entity.UserAccount;
import com.springclaw.mapper.UserAccountMapper;
import com.springclaw.service.auth.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 简化认证服务实现（校招可讲清楚版本）。
 *
 * 设计说明：
 * 1. 支持 MySQL/Redis 正常路径，也支持内存降级，保证本地联调可用。
 * 2. 不引入复杂鉴权框架，保留可演进接口，后续可平滑接入 Spring Security。
 */
@Service
public class AuthServiceImpl extends ServiceImpl<UserAccountMapper, UserAccount> implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);
    private static final long DB_RETRY_INTERVAL_MS = 30_000L;
    private static final int MAX_LOCAL_TOKENS = 5000;

    private final StringRedisTemplate redisTemplate;
    private final boolean authEnabled;
    private final boolean dbEnabled;
    private final boolean redisEnabled;
    private final String defaultRole;
    private final String anonymousRole;
    private final boolean bootstrapFirstUserAdmin;
    private final long tokenTtlSeconds;
    private final String passwordPepper;
    private final String tokenPrefix;

    private final ConcurrentMap<String, LocalUser> localUsers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LocalToken> localTokens = new ConcurrentHashMap<>();
    private final Object bootstrapAdminMonitor = new Object();
    private volatile long dbRetryAt = 0L;
    private volatile long redisRetryAt = 0L;

    public AuthServiceImpl(@Autowired(required = false) StringRedisTemplate redisTemplate,
                           @Value("${springclaw.auth.enabled:true}") boolean authEnabled,
                           @Value("${springclaw.persistence.db-enabled:false}") boolean dbEnabled,
                           @Value("${springclaw.auth.redis-enabled:true}") boolean redisEnabled,
                           @Value("${springclaw.auth.default-role:USER}") String defaultRole,
                           @Value("${springclaw.auth.anonymous-role:USER}") String anonymousRole,
                           @Value("${springclaw.auth.bootstrap-first-user-admin:true}") boolean bootstrapFirstUserAdmin,
                           @Value("${springclaw.auth.token-ttl-hours:24}") int tokenTtlHours,
                           @Value("${springclaw.auth.password-pepper:}") String passwordPepper,
                           @Value("${springclaw.auth.token-prefix:springclaw:auth:token:}") String tokenPrefix) {
        this.redisTemplate = redisTemplate;
        this.authEnabled = authEnabled;
        this.dbEnabled = dbEnabled;
        this.redisEnabled = redisEnabled;
        this.defaultRole = normalizeRole(defaultRole, "USER");
        this.anonymousRole = normalizeRole(anonymousRole, this.defaultRole);
        this.bootstrapFirstUserAdmin = bootstrapFirstUserAdmin;
        this.tokenTtlSeconds = Math.max(1, tokenTtlHours) * 3600L;
        this.passwordPepper = passwordPepper == null ? "" : passwordPepper.trim();
        this.tokenPrefix = StringUtils.hasText(tokenPrefix) ? tokenPrefix.trim() : "springclaw:auth:token:";
    }

    @Override
    public LoginSession register(String username, String rawPassword) {
        ensureAuthEnabled();
        String normalizedUsername = normalizeUsername(username);
        validatePassword(rawPassword);

        synchronized (bootstrapAdminMonitor) {
            UserAccount existing = findUserByUsername(normalizedUsername);
            if (existing != null) {
                throw new BusinessException(40921, "用户名已存在");
            }

            String roleCode = shouldBootstrapAdmin() ? "ADMIN" : defaultRole;
            String passwordHash = buildPasswordHash(rawPassword);

            if (canUseDb()) {
                try {
                    UserAccount entity = new UserAccount();
                    entity.setUsername(normalizedUsername);
                    entity.setPasswordHash(passwordHash);
                    entity.setRoleCode(roleCode);
                    entity.setStatus("ACTIVE");
                    save(entity);
                } catch (Exception ex) {
                    markDbTemporarilyUnavailable(ex);
                    localUsers.put(normalizedUsername, new LocalUser(passwordHash, roleCode, "ACTIVE"));
                }
            } else {
                localUsers.put(normalizedUsername, new LocalUser(passwordHash, roleCode, "ACTIVE"));
            }

            return issueLoginSession(normalizedUsername, roleCode);
        }
    }

    @Override
    public LoginSession login(String username, String rawPassword) {
        ensureAuthEnabled();
        String normalizedUsername = normalizeUsername(username);
        validatePassword(rawPassword);

        UserAccount user = findUserByUsername(normalizedUsername);
        if (user == null) {
            throw new BusinessException(40121, "用户名或密码错误");
        }
        if (!"ACTIVE".equalsIgnoreCase(nullToEmpty(user.getStatus()))) {
            throw new BusinessException(40321, "账号已被禁用");
        }
        if (!verifyPassword(rawPassword, user.getPasswordHash())) {
            throw new BusinessException(40121, "用户名或密码错误");
        }

        return issueLoginSession(normalizedUsername, normalizeRole(user.getRoleCode(), defaultRole));
    }

    @Override
    public UserIdentity authenticateToken(String token) {
        ensureAuthEnabled();
        if (!StringUtils.hasText(token)) {
            throw new BusinessException(40101, "token 不能为空");
        }
        String raw = token.trim();

        if (canUseRedis()) {
            try {
                String value = redisTemplate.opsForValue().get(tokenPrefix + raw);
                if (StringUtils.hasText(value)) {
                    String[] parts = value.split("\\|", 3);
                    if (parts.length == 3) {
                        long expireAt = parseLong(parts[2], 0L);
                        if (expireAt > System.currentTimeMillis()) {
                            return buildValidatedIdentity(parts[0], parts[1], expireAt);
                        }
                    }
                }
            } catch (Exception ex) {
                markRedisTemporarilyUnavailable(ex);
            }
        }

        LocalToken localToken = localTokens.get(raw);
        if (localToken == null) {
            throw new BusinessException(40103, "token 无效或已过期");
        }
        if (localToken.expireAt() <= System.currentTimeMillis()) {
            localTokens.remove(raw);
            throw new BusinessException(40104, "token 已过期");
        }
        return buildValidatedIdentity(localToken.username(), localToken.roleCode(), localToken.expireAt());
    }

    @Override
    public String resolveRoleByUserId(String userId) {
        if (!StringUtils.hasText(userId)) {
            return anonymousRole;
        }

        String normalized = normalizeUsername(userId);
        UserAccount account = findUserByUsername(normalized);
        if (account == null) {
            return defaultRole;
        }
        if (!"ACTIVE".equalsIgnoreCase(nullToEmpty(account.getStatus()))) {
            return anonymousRole;
        }
        return normalizeRole(account.getRoleCode(), defaultRole);
    }

    @Override
    public TokenRuntimeStats tokenRuntimeStats() {
        List<ActiveSession> sessions = loadAllActiveSessions();
        long now = System.currentTimeMillis();
        long expiringSoonCount = sessions.stream()
                .filter(session -> session.expireAt() <= now + 3_600_000L)
                .count();
        long redisTokenCount = sessions.stream()
                .filter(session -> "REDIS".equals(session.storage()))
                .count();
        long localTokenCount = sessions.stream()
                .filter(session -> "LOCAL".equals(session.storage()))
                .count();
        Set<String> users = new HashSet<>();
        sessions.forEach(session -> users.add(session.username()));
        return new TokenRuntimeStats(
                sessions.size(),
                users.size(),
                redisTokenCount,
                localTokenCount,
                expiringSoonCount,
                canUseRedis(),
                tokenTtlSeconds
        );
    }

    @Override
    public List<ActiveSession> listActiveSessions(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return loadAllActiveSessions().stream()
                .sorted(Comparator.comparingLong(AuthService.ActiveSession::expireAt).reversed())
                .limit(safeLimit)
                .toList();
    }

    private LoginSession issueLoginSession(String username, String roleCode) {
        String token = UUID.randomUUID().toString().replace("-", "");
        long expireAt = System.currentTimeMillis() + tokenTtlSeconds * 1000L;
        String value = username + "|" + roleCode + "|" + expireAt;

        boolean stored = false;
        if (canUseRedis()) {
            try {
                redisTemplate.opsForValue().set(tokenPrefix + token, value, Duration.ofSeconds(tokenTtlSeconds));
                stored = true;
            } catch (Exception ex) {
                markRedisTemporarilyUnavailable(ex);
            }
        }
        if (!stored) {
            localTokens.put(token, new LocalToken(username, roleCode, expireAt));
            if (localTokens.size() > MAX_LOCAL_TOKENS) {
                pruneExpiredTokens();
            }
        }
        return new LoginSession(token, username, roleCode, expireAt);
    }

    private List<ActiveSession> loadAllActiveSessions() {
        long now = System.currentTimeMillis();
        List<ActiveSession> sessions = new ArrayList<>();

        if (canUseRedis()) {
            try {
                Set<String> keys = redisTemplate.keys(tokenPrefix + "*");
                if (keys != null) {
                    for (String key : keys) {
                        String value = redisTemplate.opsForValue().get(key);
                        ActiveSession session = parseSessionValue(key, value, now, "REDIS");
                        if (session != null) {
                            sessions.add(session);
                        }
                    }
                }
            } catch (Exception ex) {
                markRedisTemporarilyUnavailable(ex);
            }
        }

        for (var entry : localTokens.entrySet()) {
            LocalToken token = entry.getValue();
            if (token.expireAt() <= now) {
                continue;
            }
            UserAccount account = findUserByUsername(token.username());
            if (account == null || !"ACTIVE".equalsIgnoreCase(nullToEmpty(account.getStatus()))) {
                continue;
            }
            sessions.add(new ActiveSession(
                    normalizeUsername(account.getUsername()),
                    normalizeRole(account.getRoleCode(), token.roleCode()),
                    token.expireAt(),
                    previewToken(entry.getKey()),
                    "LOCAL"
            ));
        }
        return sessions;
    }

    private ActiveSession parseSessionValue(String key, String value, long now, String storage) {
        if (!StringUtils.hasText(key) || !StringUtils.hasText(value)) {
            return null;
        }
        String[] parts = value.split("\\|", 3);
        if (parts.length != 3) {
            return null;
        }
        long expireAt = parseLong(parts[2], 0L);
        if (expireAt <= now) {
            return null;
        }
        String rawToken = key.startsWith(tokenPrefix) ? key.substring(tokenPrefix.length()) : key;
        UserAccount account = findUserByUsername(parts[0]);
        if (account == null || !"ACTIVE".equalsIgnoreCase(nullToEmpty(account.getStatus()))) {
            return null;
        }
        return new ActiveSession(
                normalizeUsername(account.getUsername()),
                normalizeRole(account.getRoleCode(), normalizeRole(parts[1], defaultRole)),
                expireAt,
                previewToken(rawToken),
                storage
        );
    }

    private UserIdentity buildValidatedIdentity(String username, String fallbackRole, long expireAt) {
        UserAccount account = findUserByUsername(username);
        if (account == null) {
            throw new BusinessException(40103, "token 无效或已过期");
        }
        if (!"ACTIVE".equalsIgnoreCase(nullToEmpty(account.getStatus()))) {
            throw new BusinessException(40321, "账号已被禁用");
        }
        return new UserIdentity(
                normalizeUsername(account.getUsername()),
                normalizeRole(account.getRoleCode(), normalizeRole(fallbackRole, defaultRole)),
                expireAt
        );
    }

    private String previewToken(String token) {
        if (!StringUtils.hasText(token)) {
            return "";
        }
        String normalized = token.trim();
        if (normalized.length() <= 10) {
            return normalized;
        }
        return normalized.substring(0, 6) + "..." + normalized.substring(normalized.length() - 4);
    }

    private UserAccount findUserByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }

        if (canUseDb()) {
            try {
                UserAccount account = lambdaQuery()
                        .eq(UserAccount::getUsername, username)
                        .one();
                if (account != null) {
                    return account;
                }
            } catch (Exception ex) {
                markDbTemporarilyUnavailable(ex);
            }
        }

        LocalUser localUser = localUsers.get(username);
        if (localUser == null) {
            return null;
        }
        UserAccount account = new UserAccount();
        account.setUsername(username);
        account.setPasswordHash(localUser.passwordHash());
        account.setRoleCode(localUser.roleCode());
        account.setStatus(localUser.status());
        return account;
    }

    private boolean shouldBootstrapAdmin() {
        if (!bootstrapFirstUserAdmin) {
            return false;
        }
        if (canUseDb()) {
            try {
                return lambdaQuery().count() == 0;
            } catch (Exception ex) {
                markDbTemporarilyUnavailable(ex);
            }
        }
        return localUsers.isEmpty();
    }

    private String normalizeUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new BusinessException(40091, "username 不能为空");
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[a-z0-9._-]{3,64}$")) {
            throw new BusinessException(40092, "username 仅支持 3-64 位小写字母数字._-");
        }
        return normalized;
    }

    private void validatePassword(String password) {
        if (!StringUtils.hasText(password) || password.length() < 6 || password.length() > 64) {
            throw new BusinessException(40093, "password 长度需在 6-64 之间");
        }
    }

    private String buildPasswordHash(String rawPassword) {
        String saltHex = randomSaltHex();
        String digestHex = digestHex(saltHex + "|" + rawPassword + "|" + passwordPepper);
        return "v1$" + saltHex + "$" + digestHex;
    }

    private boolean verifyPassword(String rawPassword, String storedHash) {
        if (!StringUtils.hasText(storedHash)) {
            return false;
        }
        String[] parts = storedHash.split("\\$");
        if (parts.length != 3 || !"v1".equals(parts[0])) {
            return false;
        }
        String saltHex = parts[1];
        String expected = parts[2];
        String actual = digestHex(saltHex + "|" + rawPassword + "|" + passwordPepper);
        return expected.equalsIgnoreCase(actual);
    }

    private String randomSaltHex() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String digestHex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception ex) {
            throw new BusinessException(50061, "密码摘要计算失败: " + ex.getMessage());
        }
    }

    private void pruneExpiredTokens() {
        long now = System.currentTimeMillis();
        localTokens.entrySet().removeIf(entry -> entry.getValue().expireAt() <= now);
    }

    private String normalizeRole(String roleCode, String defaultValue) {
        if (!StringUtils.hasText(roleCode)) {
            return defaultValue;
        }
        return roleCode.trim().toUpperCase(Locale.ROOT);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private long parseLong(String value, long defaultValue) {
        try {
            return Long.parseLong(value);
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private void ensureAuthEnabled() {
        if (!authEnabled) {
            throw new BusinessException(40094, "认证功能未开启");
        }
    }

    private boolean canUseDb() {
        return dbEnabled && System.currentTimeMillis() >= dbRetryAt;
    }

    private void markDbTemporarilyUnavailable(Exception ex) {
        dbRetryAt = System.currentTimeMillis() + DB_RETRY_INTERVAL_MS;
        String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        log.warn("认证 DB 不可用，{}ms 内走本地降级。reason={}", DB_RETRY_INTERVAL_MS, reason);
    }

    private boolean canUseRedis() {
        return redisEnabled
                && redisTemplate != null
                && System.currentTimeMillis() >= redisRetryAt;
    }

    private void markRedisTemporarilyUnavailable(Exception ex) {
        redisRetryAt = System.currentTimeMillis() + 30_000L;
        String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        log.warn("认证 Redis 不可用，30s 内走本地 token。reason={}", reason);
    }

    private record LocalUser(String passwordHash, String roleCode, String status) {
    }

    private record LocalToken(String username, String roleCode, long expireAt) {
    }
}
