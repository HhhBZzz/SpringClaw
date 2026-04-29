package com.springclaw.service.guard.impl;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.guard.ChatGuardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话防护实现：
 * 1) QPS/窗口限流；2) 同会话并发锁。
 *
 * 设计说明：
 * 1. Redis 可用时使用分布式防护，支持多实例部署。
 * 2. Redis 不可用时自动降级为本地防护，保证核心链路不中断。
 */
@Service
public class RedisChatGuardService implements ChatGuardService {

    private static final Logger log = LoggerFactory.getLogger(RedisChatGuardService.class);

    private final StringRedisTemplate redisTemplate;
    private final boolean guardEnabled;
    private final boolean redisEnabled;
    private final int maxRequests;
    private final int windowSeconds;
    private final int lockSeconds;
    private final int redisRetrySeconds;

    private final Map<String, Deque<Long>> localRateWindow = new ConcurrentHashMap<>();
    private final Map<String, String> localLockTokenMap = new ConcurrentHashMap<>();
    private volatile long redisRetryAt = 0L;

    public RedisChatGuardService(@Autowired(required = false) StringRedisTemplate redisTemplate,
                                 @Value("${springclaw.guard.enabled:true}") boolean guardEnabled,
                                 @Value("${springclaw.guard.redis-enabled:true}") boolean redisEnabled,
                                 @Value("${springclaw.guard.rate-limit.max-requests:20}") int maxRequests,
                                 @Value("${springclaw.guard.rate-limit.window-seconds:60}") int windowSeconds,
                                 @Value("${springclaw.guard.lock-seconds:30}") int lockSeconds,
                                 @Value("${springclaw.guard.redis-retry-seconds:30}") int redisRetrySeconds) {
        this.redisTemplate = redisTemplate;
        this.guardEnabled = guardEnabled;
        this.redisEnabled = redisEnabled;
        this.maxRequests = Math.max(1, maxRequests);
        this.windowSeconds = Math.max(1, windowSeconds);
        this.lockSeconds = Math.max(3, lockSeconds);
        this.redisRetrySeconds = Math.max(5, redisRetrySeconds);
    }

    @Override
    public void checkRateLimit(String sessionKey) {
        if (!guardEnabled) {
            return;
        }

        String key = normalizeSessionKey(sessionKey);
        if (canUseRedis()) {
            try {
                String redisKey = "springclaw:guard:rate:" + key;
                Long count = redisTemplate.opsForValue().increment(redisKey);
                if (count != null && count == 1L) {
                    redisTemplate.expire(redisKey, Duration.ofSeconds(windowSeconds));
                }
                if (count != null && count > maxRequests) {
                    throw new BusinessException(42901, "请求过于频繁，请稍后再试");
                }
                return;
            } catch (BusinessException ex) {
                throw ex;
            } catch (Exception ex) {
                markRedisTemporarilyUnavailable("限流", ex);
            }
        }

        long now = System.currentTimeMillis();
        long threshold = now - (windowSeconds * 1000L);
        Deque<Long> deque = localRateWindow.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < threshold) {
                deque.pollFirst();
            }
            if (deque.size() >= maxRequests) {
                throw new BusinessException(42901, "请求过于频繁，请稍后再试");
            }
            deque.addLast(now);
        }
    }

    @Override
    public String acquireSessionLock(String sessionKey) {
        if (!guardEnabled) {
            return "guard-disabled";
        }

        String key = normalizeSessionKey(sessionKey);
        String token = UUID.randomUUID().toString().replace("-", "");

        if (canUseRedis()) {
            try {
                String redisKey = "springclaw:guard:lock:" + key;
                Boolean success = redisTemplate.opsForValue()
                        .setIfAbsent(redisKey, token, Duration.ofSeconds(lockSeconds));
                if (Boolean.TRUE.equals(success)) {
                    return token;
                }
                throw new BusinessException(40901, "当前会话正在处理中，请稍后重试");
            } catch (BusinessException ex) {
                throw ex;
            } catch (Exception ex) {
                markRedisTemporarilyUnavailable("会话锁", ex);
            }
        }

        String localToken = "local-" + token;
        String existing = localLockTokenMap.putIfAbsent(key, localToken);
        if (existing != null) {
            throw new BusinessException(40901, "当前会话正在处理中，请稍后重试");
        }
        return localToken;
    }

    @Override
    public void releaseSessionLock(String sessionKey, String token) {
        if (!guardEnabled || !StringUtils.hasText(token) || "guard-disabled".equals(token)) {
            return;
        }

        String key = normalizeSessionKey(sessionKey);

        if (token.startsWith("local-")) {
            localLockTokenMap.remove(key, token);
            return;
        }

        if (canUseRedis()) {
            try {
                String redisKey = "springclaw:guard:lock:" + key;
                DefaultRedisScript<Long> script = new DefaultRedisScript<>();
                script.setResultType(Long.class);
                script.setScriptText("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end");
                redisTemplate.execute(script, Collections.singletonList(redisKey), token);
                return;
            } catch (Exception ex) {
                markRedisTemporarilyUnavailable("释放会话锁", ex);
            }
        }

        localLockTokenMap.remove(key);
    }

    private String normalizeSessionKey(String sessionKey) {
        return StringUtils.hasText(sessionKey) ? sessionKey.trim() : "anonymous-session";
    }

    private boolean canUseRedis() {
        return redisEnabled
                && redisTemplate != null
                && System.currentTimeMillis() >= redisRetryAt;
    }

    private void markRedisTemporarilyUnavailable(String operation, Exception ex) {
        redisRetryAt = System.currentTimeMillis() + (redisRetrySeconds * 1000L);
        String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        log.warn("Redis {}失败，{}s 内降级本地防护。reason={}", operation, redisRetrySeconds, reason);
    }
}
