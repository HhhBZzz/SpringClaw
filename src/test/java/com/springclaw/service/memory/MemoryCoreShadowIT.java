package com.springclaw.service.memory;

import com.springclaw.config.MemoryCoreStoreConfig;
import com.springclaw.config.MemoryShortTermShadowConfig;
import com.springclaw.runtime.memory.port.MemoryIndexOutboxStore;
import com.springclaw.runtime.memory.port.MemoryRecordStore;
import com.springclaw.runtime.memory.port.ShortTermMemoryStore;
import com.springclaw.runtime.memory.store.InMemoryMemoryIndexOutboxStore;
import com.springclaw.runtime.memory.store.InMemoryMemoryRecordStore;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.index.MemoryIndexRebuildService;
import com.springclaw.service.memory.index.MemoryIndexReconciler;
import com.springclaw.service.memory.index.MemoryIndexWorker;
import com.springclaw.service.memory.store.RedisShortTermMemoryStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Memory R2：验证 Redis short-term memory 默认成为 canonical hot context，
 * 同时保留显式关闭开关。
 */
class MemoryCoreShadowIT {

    private final ApplicationContextRunner shortTermContext =
            new ApplicationContextRunner()
                    .withUserConfiguration(ShortTermShadowConfig.class)
                    .withPropertyValues("springclaw.redisson.enabled=true")
                    .withBean(RedissonClient.class, () -> Mockito.mock(RedissonClient.class))
                    .withBean(MessageEventService.class, () -> Mockito.mock(MessageEventService.class));

    private final ApplicationContextRunner shortTermContextWithoutRedis =
            new ApplicationContextRunner()
                    .withUserConfiguration(ShortTermShadowWithoutRedisConfig.class)
                    .withPropertyValues("springclaw.redisson.enabled=false");

    private final ApplicationContextRunner localStoreContext =
            new ApplicationContextRunner()
                    .withUserConfiguration(LocalStoreConfig.class);

    @Test
    void shortTermBeansAreActiveByDefaultWhenRedisExists() {
        shortTermContext.run(context -> {
            assertThat(context).hasSingleBean(ShortTermMemoryStore.class);
            assertThat(context).hasSingleBean(RedisShortTermMemoryStore.class);
            assertThat(context).hasSingleBean(ShortTermMemoryRecoveryService.class);
        });
    }

    @Test
    void shortTermBeansCanBeExplicitlyDisabled() {
        shortTermContext
                .withPropertyValues(
                        "springclaw.memory.core.short-term-shadow-enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ShortTermMemoryStore.class);
                    assertThat(context).doesNotHaveBean(RedisShortTermMemoryStore.class);
                    assertThat(context).doesNotHaveBean(ShortTermMemoryRecoveryService.class);
                });
    }

    @Test
    void shortTermBeansStayInactiveWhenRedisClientIsMissing() {
        shortTermContextWithoutRedis.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ShortTermMemoryStore.class);
            assertThat(context).doesNotHaveBean(RedisShortTermMemoryStore.class);
            assertThat(context).doesNotHaveBean(ShortTermMemoryRecoveryService.class);
        });
    }

    @Test
    void databaseDisabledModeUsesBoundedLocalStores() {
        localStoreContext
                .withPropertyValues("springclaw.memory.core.enabled=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(MemoryRecordStore.class);
                    assertThat(context).hasSingleBean(MemoryIndexOutboxStore.class);
                    assertThat(context.getBean(MemoryRecordStore.class))
                            .isInstanceOf(InMemoryMemoryRecordStore.class);
                    assertThat(context.getBean(MemoryIndexOutboxStore.class))
                            .isInstanceOf(InMemoryMemoryIndexOutboxStore.class);
                });
    }

    @Test
    void vectorIndexWorkersAreInactiveByDefault() {
        localStoreContext.run(context -> {
            assertThat(context).doesNotHaveBean(MemoryIndexWorker.class);
            assertThat(context).doesNotHaveBean(MemoryIndexReconciler.class);
            assertThat(context).doesNotHaveBean(MemoryIndexRebuildService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            MemoryShortTermShadowConfig.class
    })
    static class ShortTermShadowConfig {
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            MemoryShortTermShadowConfig.class
    })
    static class ShortTermShadowWithoutRedisConfig {

        @Bean
        MessageEventService messageEventService() {
            return Mockito.mock(MessageEventService.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(MemoryCoreStoreConfig.class)
    static class LocalStoreConfig {
    }
}
