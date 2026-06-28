package com.springclaw.config;

import com.springclaw.runtime.memory.port.ShortTermMemoryStore;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.ShortTermMemoryRecoveryService;
import com.springclaw.service.memory.store.RedisShortTermMemoryStore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default-on Redis short-term memory wiring when Redis integration is enabled.
 *
 * <p>The historical {@code short-term-shadow-enabled} property is retained as
 * an explicit rollback switch: set it to {@code false} to disable Redis
 * short-term hot context while keeping MySQL {@code message_event} as the
 * recovery source.
 */
@Configuration(proxyBeanMethods = false)
public class MemoryShortTermShadowConfig {

    @Bean
    @ConditionalOnMissingBean(ShortTermMemoryStore.class)
    @ConditionalOnProperty(
            prefix = "springclaw.redisson",
            name = "enabled",
            havingValue = "true"
    )
    @ConditionalOnProperty(
            prefix = "springclaw.memory.core",
            name = "short-term-shadow-enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    public ShortTermMemoryStore redisShortTermMemoryStore(
            RedissonClient redissonClient,
            @Value("${springclaw.memory.core.short-term-max-messages:${springclaw.memory.short-term-max-entries:40}}")
            int maxMessages,
            @Value("#{${springclaw.memory.core.short-term-ttl-days:7} * 86400}")
            long ttlSeconds
    ) {
        return new RedisShortTermMemoryStore(redissonClient, maxMessages, ttlSeconds);
    }

    @Bean
    @ConditionalOnMissingBean(ShortTermMemoryRecoveryService.class)
    @ConditionalOnProperty(
            prefix = "springclaw.redisson",
            name = "enabled",
            havingValue = "true"
    )
    @ConditionalOnProperty(
            prefix = "springclaw.memory.core",
            name = "short-term-shadow-enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    public ShortTermMemoryRecoveryService shortTermMemoryRecoveryService(
            RedissonClient redissonClient,
            ShortTermMemoryStore shortTermMemoryStore,
            MessageEventService messageEventService
    ) {
        return new ShortTermMemoryRecoveryService(
                redissonClient,
                shortTermMemoryStore,
                messageEventService
        );
    }
}
