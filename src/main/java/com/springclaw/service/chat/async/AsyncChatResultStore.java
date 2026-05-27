package com.springclaw.service.chat.async;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

@Service
public class AsyncChatResultStore {

    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final long ttlHours;
    private final Cache<String, AsyncChatResultPayload> localStore;

    public AsyncChatResultStore(ObjectProvider<RedissonClient> redissonClientProvider,
                                ObjectMapper objectMapper,
                                @Value("${springclaw.rabbitmq.async-result-key-prefix:springclaw:chat:async:}") String keyPrefix,
                                @Value("${springclaw.rabbitmq.async-result-ttl-hours:24}") long ttlHours) {
        this.redissonClientProvider = redissonClientProvider;
        this.objectMapper = objectMapper;
        this.keyPrefix = StringUtils.hasText(keyPrefix) ? keyPrefix.trim() : "springclaw:chat:async:";
        this.ttlHours = Math.max(1, ttlHours);
        this.localStore = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(this.ttlHours, TimeUnit.HOURS)
                .build();
    }

    public AsyncChatResultPayload markQueued(AsyncChatRequestMessage message) {
        AsyncChatResultPayload payload = new AsyncChatResultPayload(
                message.requestId(),
                "QUEUED",
                message.sessionKey(),
                message.channel(),
                "",
                "",
                message.createdAt(),
                null,
                ""
        );
        save(payload);
        return payload;
    }

    public AsyncChatResultPayload markCompleted(AsyncChatRequestMessage message, String answer, String model) {
        AsyncChatResultPayload payload = new AsyncChatResultPayload(
                message.requestId(),
                "COMPLETED",
                message.sessionKey(),
                message.channel(),
                answer == null ? "" : answer,
                model == null ? "" : model,
                message.createdAt(),
                System.currentTimeMillis(),
                ""
        );
        save(payload);
        return payload;
    }

    public AsyncChatResultPayload markFailed(AsyncChatRequestMessage message, String errorMessage) {
        AsyncChatResultPayload payload = new AsyncChatResultPayload(
                message.requestId(),
                "FAILED",
                message.sessionKey(),
                message.channel(),
                "",
                "",
                message.createdAt(),
                System.currentTimeMillis(),
                errorMessage == null ? "未知错误" : errorMessage
        );
        save(payload);
        return payload;
    }

    public AsyncChatResultPayload find(String requestId) {
        String normalizedRequestId = normalizeRequestId(requestId);
        AsyncChatResultPayload cached = readFromRedis(normalizedRequestId);
        if (cached != null) {
            return cached;
        }
        return localStore.getIfPresent(normalizedRequestId);
    }

    private void save(AsyncChatResultPayload payload) {
        String requestId = normalizeRequestId(payload.requestId());
        if (!StringUtils.hasText(requestId)) {
            return;
        }
        AsyncChatResultPayload normalizedPayload = new AsyncChatResultPayload(
                requestId,
                payload.status(),
                payload.sessionKey(),
                payload.channel(),
                payload.answer(),
                payload.model(),
                payload.createdAt(),
                payload.completedAt(),
                payload.errorMessage()
        );
        localStore.put(requestId, normalizedPayload);
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
        if (redissonClient == null) {
            return;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(keyPrefix + requestId);
            bucket.set(objectMapper.writeValueAsString(normalizedPayload), ttlHours, TimeUnit.HOURS);
        } catch (Exception ignore) {
            // Redis/Redisson 不可用时保留本地兜底。
        }
    }

    private AsyncChatResultPayload readFromRedis(String requestId) {
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
        String normalizedRequestId = normalizeRequestId(requestId);
        if (redissonClient == null || !StringUtils.hasText(normalizedRequestId)) {
            return null;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(keyPrefix + normalizedRequestId);
            String json = bucket.get();
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, AsyncChatResultPayload.class);
        } catch (Exception ignore) {
            return null;
        }
    }

    private String normalizeRequestId(String requestId) {
        return requestId == null ? "" : requestId.trim();
    }
}
