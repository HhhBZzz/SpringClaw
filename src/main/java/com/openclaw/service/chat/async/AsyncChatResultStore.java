package com.openclaw.service.chat.async;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Service
public class AsyncChatResultStore {

    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;
    private final long ttlHours;
    private final ConcurrentMap<String, AsyncChatResultPayload> localStore = new ConcurrentHashMap<>();

    public AsyncChatResultStore(ObjectProvider<RedissonClient> redissonClientProvider,
                                ObjectMapper objectMapper,
                                @Value("${openclaw.rabbitmq.async-result-key-prefix:openclaw:chat:async:}") String keyPrefix,
                                @Value("${openclaw.rabbitmq.async-result-ttl-hours:24}") long ttlHours) {
        this.redissonClientProvider = redissonClientProvider;
        this.objectMapper = objectMapper;
        this.keyPrefix = StringUtils.hasText(keyPrefix) ? keyPrefix.trim() : "openclaw:chat:async:";
        this.ttlHours = Math.max(1, ttlHours);
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
        AsyncChatResultPayload cached = readFromRedis(requestId);
        if (cached != null) {
            return cached;
        }
        return localStore.get(requestId);
    }

    private void save(AsyncChatResultPayload payload) {
        localStore.put(payload.requestId(), payload);
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
        if (redissonClient == null) {
            return;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(keyPrefix + payload.requestId());
            bucket.set(objectMapper.writeValueAsString(payload), ttlHours, TimeUnit.HOURS);
        } catch (Exception ignore) {
            // Redis/Redisson 不可用时保留本地兜底。
        }
    }

    private AsyncChatResultPayload readFromRedis(String requestId) {
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
        if (redissonClient == null || !StringUtils.hasText(requestId)) {
            return null;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(keyPrefix + requestId.trim());
            String json = bucket.get();
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, AsyncChatResultPayload.class);
        } catch (Exception ignore) {
            return null;
        }
    }
}
