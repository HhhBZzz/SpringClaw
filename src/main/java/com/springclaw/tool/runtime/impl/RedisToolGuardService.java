package com.springclaw.tool.runtime.impl;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.tool.runtime.ToolGuardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具级限流服务。
 */
@Service
public class RedisToolGuardService implements ToolGuardService {

    private static final Logger log = LoggerFactory.getLogger(RedisToolGuardService.class);

    private final StringRedisTemplate redisTemplate;
    private final boolean enabled;
    private final boolean redisEnabled;
    private final int maxCalls;
    private final int windowSeconds;
    private final int redisRetrySeconds;

    private final Map<String, Deque<Long>> localWindows = new ConcurrentHashMap<>();
    private volatile long redisRetryAt = 0L;

    public RedisToolGuardService(@Autowired(required = false) StringRedisTemplate redisTemplate,
                                 @Value("${springclaw.tools.guard.enabled:true}") boolean enabled,
                                 @Value("${springclaw.tools.guard.redis-enabled:true}") boolean redisEnabled,
                                 @Value("${springclaw.tools.guard.max-calls:60}") int maxCalls,
                                 @Value("${springclaw.tools.guard.window-seconds:60}") int windowSeconds,
                                 @Value("${springclaw.tools.guard.redis-retry-seconds:30}") int redisRetrySeconds) {
        this.redisTemplate = redisTemplate;
        this.enabled = enabled;
        this.redisEnabled = redisEnabled;
        this.maxCalls = Math.max(1, maxCalls);
        this.windowSeconds = Math.max(1, windowSeconds);
        this.redisRetrySeconds = Math.max(5, redisRetrySeconds);
    }

    @Override
    public void checkRateLimit(String toolName) {
        if (!enabled) {
            return;
        }

        String key = toolName == null ? "unknown-tool" : toolName;
        if (canUseRedis()) {
            try {
                String redisKey = "springclaw:tool:rate:" + key;
                Long count = redisTemplate.opsForValue().increment(redisKey);
                if (count != null && count == 1L) {
                    redisTemplate.expire(redisKey, Duration.ofSeconds(windowSeconds));
                }
                if (count != null && count > maxCalls) {
                    throw new BusinessException(42911, "工具调用过于频繁: " + key);
                }
                return;
            } catch (BusinessException ex) {
                throw ex;
            } catch (Exception ex) {
                markRedisUnavailable(ex);
            }
        }

        long now = System.currentTimeMillis();
        long threshold = now - windowSeconds * 1000L;
        Deque<Long> deque = localWindows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && deque.peekFirst() < threshold) {
                deque.pollFirst();
            }
            if (deque.size() >= maxCalls) {
                throw new BusinessException(42911, "工具调用过于频繁: " + key);
            }
            deque.addLast(now);
        }
    }

    private boolean canUseRedis() {
        return redisEnabled
                && redisTemplate != null
                && System.currentTimeMillis() >= redisRetryAt;
    }

    private void markRedisUnavailable(Exception ex) {
        redisRetryAt = System.currentTimeMillis() + redisRetrySeconds * 1000L;
        String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        log.warn("Redis 工具限流失败，{}s 内降级本地限流。reason={}", redisRetrySeconds, reason);
    }
}
