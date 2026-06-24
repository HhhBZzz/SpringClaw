package com.springclaw.config;

import com.springclaw.runtime.memory.port.MemoryRecordStore;
import com.springclaw.runtime.memory.port.ProjectMemorySource;
import com.springclaw.runtime.memory.port.ShortTermMemoryStore;
import com.springclaw.service.memory.frame.MemoryCoordinator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Disabled-by-default Phase 3A2 MemoryFrame wiring.
 *
 * <p>This configuration only creates shadow retrieval infrastructure. It does
 * not attach MemoryFrame retrieval to prompt assembly, chat advisors, routing,
 * or engine execution.
 */
@Configuration(proxyBeanMethods = false)
public class MemoryFrameConfig {

    @Bean
    @ConditionalOnProperty(
            prefix = "springclaw.memory.frame",
            name = "enabled",
            havingValue = "true"
    )
    public MemoryCoordinator memoryCoordinator(
            MemoryRecordStore recordStore,
            ObjectProvider<ShortTermMemoryStore> shortTermStoreProvider,
            ProjectMemorySource projectMemorySource,
            ObjectProvider<Clock> clockProvider,
            @Value("${springclaw.memory.frame.max-chars:6000}") int maxChars,
            @Value("${springclaw.memory.frame.trace-max-warnings:20}") int traceMaxWarnings
    ) {
        return new MemoryCoordinator(
                recordStore,
                shortTermStoreProvider,
                projectMemorySource,
                clockProvider.getIfAvailable(Clock::systemUTC),
                maxChars,
                traceMaxWarnings
        );
    }
}
