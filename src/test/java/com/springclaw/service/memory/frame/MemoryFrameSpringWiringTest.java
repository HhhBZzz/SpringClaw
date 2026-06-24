package com.springclaw.service.memory.frame;

import com.springclaw.config.MemoryFrameConfig;
import com.springclaw.runtime.memory.port.MemoryRecordStore;
import com.springclaw.runtime.memory.port.ProjectMemorySource;
import com.springclaw.runtime.memory.store.InMemoryMemoryRecordStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryFrameSpringWiringTest {

    @Test
    void memoryFrameCoordinatorIsActiveByDefault() {
        new ApplicationContextRunner()
                .withUserConfiguration(MemoryFrameConfig.class, TestConfig.class)
                .run(context -> assertThat(context)
                        .hasSingleBean(MemoryCoordinator.class));
    }

    @Test
    void memoryFrameCoordinatorCanBeDisabledForRollback() {
        new ApplicationContextRunner()
                .withUserConfiguration(MemoryFrameConfig.class)
                .withPropertyValues("springclaw.memory.frame.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(MemoryCoordinator.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        MemoryRecordStore memoryRecordStore() {
            return new InMemoryMemoryRecordStore();
        }

        @Bean
        ProjectMemorySource projectMemorySource() {
            return ignored -> java.util.List.of();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
