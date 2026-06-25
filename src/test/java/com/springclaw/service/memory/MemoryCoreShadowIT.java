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
 * Phase 3A1 Task 9：验证 memory core shadow wiring 默认关闭，不改变当前
 * active context/input path。
 */
class MemoryCoreShadowIT {

    private final ApplicationContextRunner shortTermContext =
            new ApplicationContextRunner()
                    .withUserConfiguration(ShortTermShadowConfig.class);

    private final ApplicationContextRunner localStoreContext =
            new ApplicationContextRunner()
                    .withUserConfiguration(LocalStoreConfig.class);

    @Test
    void shortTermShadowBeansAreInactiveByDefaultEvenWhenRedisExists() {
        shortTermContext.run(context -> {
            assertThat(context).doesNotHaveBean(ShortTermMemoryStore.class);
            assertThat(context).doesNotHaveBean(RedisShortTermMemoryStore.class);
            assertThat(context).doesNotHaveBean(ShortTermMemoryRecoveryService.class);
        });
    }

    @Test
    void shortTermShadowBeansActivateOnlyWhenExplicitlyEnabled() {
        shortTermContext
                .withPropertyValues(
                        "springclaw.memory.core.short-term-shadow-enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ShortTermMemoryStore.class);
                    assertThat(context).hasSingleBean(RedisShortTermMemoryStore.class);
                    assertThat(context).hasSingleBean(ShortTermMemoryRecoveryService.class);
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

        @Bean
        RedissonClient redissonClient() {
            return Mockito.mock(RedissonClient.class);
        }

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
