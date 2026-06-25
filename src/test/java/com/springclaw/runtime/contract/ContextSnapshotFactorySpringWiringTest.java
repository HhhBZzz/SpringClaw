package com.springclaw.runtime.contract;

import com.springclaw.config.ContextSnapshotConfig;
import com.springclaw.config.MemoryFrameConfig;
import com.springclaw.runtime.bridge.CanonicalContextReadyProjector;
import com.springclaw.runtime.bridge.LegacyContextViewAdapter;
import com.springclaw.runtime.bridge.RunStateContextSnapshotRequestFactory;
import com.springclaw.runtime.lifecycle.InMemoryRunLifecycleStore;
import com.springclaw.runtime.lifecycle.RunCoordinator;
import com.springclaw.runtime.lifecycle.RunLifecycleStore;
import com.springclaw.runtime.lifecycle.RunStateRepository;
import com.springclaw.runtime.memory.port.MemoryRecordStore;
import com.springclaw.runtime.memory.port.ProjectMemorySource;
import com.springclaw.runtime.memory.port.ShortTermMemoryStore;
import com.springclaw.runtime.memory.store.InMemoryMemoryRecordStore;
import com.springclaw.runtime.memory.store.InMemoryShortTermMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class ContextSnapshotFactorySpringWiringTest {

    @Test
    void canonicalSnapshotBeansAreActiveByDefaultWhenMemoryFrameIsEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        ContextSnapshotConfig.class,
                        MemoryFrameConfig.class,
                        TestConfig.class
                )
                .withPropertyValues("springclaw.memory.frame.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(ContextSnapshotFactory.class)
                        .hasSingleBean(LegacyContextViewAdapter.class)
                        .hasSingleBean(RunStateContextSnapshotRequestFactory.class)
                        .hasSingleBean(CanonicalContextReadyProjector.class));
    }

    @Test
    void disablingCanonicalSnapshotModeRemovesSnapshotFactoryBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        ContextSnapshotConfig.class,
                        MemoryFrameConfig.class,
                        TestConfig.class
                )
                .withPropertyValues(
                        "springclaw.context.snapshot.factory-enabled=false",
                        "springclaw.memory.frame.enabled=true"
                )
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ContextSnapshotFactory.class)
                        .doesNotHaveBean(LegacyContextViewAdapter.class)
                        .doesNotHaveBean(RunStateContextSnapshotRequestFactory.class)
                        .doesNotHaveBean(CanonicalContextReadyProjector.class));
    }

    @Test
    void canonicalSnapshotModeFailsClearlyWithoutMemoryCoordinator() {
        new ApplicationContextRunner()
                .withUserConfiguration(ContextSnapshotConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("MemoryCoordinator");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        MemoryRecordStore memoryRecordStore() {
            return new InMemoryMemoryRecordStore();
        }

        @Bean
        ShortTermMemoryStore shortTermMemoryStore() {
            return new InMemoryShortTermMemoryStore();
        }

        @Bean
        ProjectMemorySource projectMemorySource() {
            return ignored -> java.util.List.of();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        RunLifecycleStore runLifecycleStore() {
            return new InMemoryRunLifecycleStore();
        }

        @Bean
        RunCoordinator runCoordinator(RunLifecycleStore store) {
            return new RunCoordinator(store);
        }

        @Bean
        RunStateRepository runStateRepository(RunLifecycleStore store) {
            return store;
        }
    }
}
