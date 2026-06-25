package com.springclaw.config;

import com.springclaw.runtime.memory.port.MemoryIndexOutboxStore;
import com.springclaw.runtime.memory.port.MemoryRecordStore;
import com.springclaw.runtime.memory.store.InMemoryMemoryIndexOutboxStore;
import com.springclaw.runtime.memory.store.InMemoryMemoryRecordStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 3A1 memory core store wiring.
 *
 * <p>Canonical memory DB persistence is disabled by default in Phase 3A1. When
 * disabled, the runtime still starts with bounded process-local stores so shadow
 * code paths can be exercised without writing shared MySQL/vector state.
 */
@Configuration(proxyBeanMethods = false)
public class MemoryCoreStoreConfig {

    @Bean
    @ConditionalOnMissingBean(MemoryRecordStore.class)
    @ConditionalOnProperty(
            prefix = "springclaw.memory.core",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    public MemoryRecordStore inMemoryMemoryRecordStore() {
        return new InMemoryMemoryRecordStore();
    }

    @Bean
    @ConditionalOnMissingBean(MemoryIndexOutboxStore.class)
    @ConditionalOnProperty(
            prefix = "springclaw.memory.core",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    public MemoryIndexOutboxStore inMemoryMemoryIndexOutboxStore() {
        return new InMemoryMemoryIndexOutboxStore();
    }
}
