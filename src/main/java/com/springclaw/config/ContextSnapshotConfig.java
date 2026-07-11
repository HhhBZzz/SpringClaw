package com.springclaw.config;

import com.springclaw.runtime.bridge.CanonicalContextReadyProjector;
import com.springclaw.runtime.bridge.CanonicalContextSnapshotResolver;
import com.springclaw.runtime.bridge.AcceptedRunContextResolver;
import com.springclaw.runtime.bridge.LegacyContextViewAdapter;
import com.springclaw.runtime.bridge.RunStateContextSnapshotRequestFactory;
import com.springclaw.runtime.contract.ContextSnapshotFactory;
import com.springclaw.runtime.lifecycle.RunCoordinator;
import com.springclaw.runtime.lifecycle.RunStateRepository;
import com.springclaw.service.memory.frame.MemoryCoordinator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "springclaw.context.snapshot",
        name = "factory-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ContextSnapshotConfig {

    @Bean
    public ContextSnapshotFactory contextSnapshotFactory(
            MemoryCoordinator memoryCoordinator,
            ObjectProvider<Clock> clockProvider
    ) {
        return new ContextSnapshotFactory(
                memoryCoordinator,
                clockProvider.getIfAvailable(Clock::systemUTC)
        );
    }

    @Bean
    public LegacyContextViewAdapter legacyContextViewAdapter() {
        return new LegacyContextViewAdapter();
    }

    @Bean
    public RunStateContextSnapshotRequestFactory runStateContextSnapshotRequestFactory() {
        return new RunStateContextSnapshotRequestFactory();
    }

    @Bean
    public CanonicalContextReadyProjector canonicalContextReadyProjector(
            RunCoordinator runCoordinator,
            RunStateRepository runStateRepository
    ) {
        return new CanonicalContextReadyProjector(
                runCoordinator,
                runStateRepository
        );
    }

    @Bean
    public AcceptedRunContextResolver acceptedRunContextResolver(
            RunStateRepository runStateRepository
    ) {
        return new AcceptedRunContextResolver(runStateRepository);
    }

    @Bean
    public CanonicalContextSnapshotResolver canonicalContextSnapshotResolver(
            CanonicalContextReadyProjector projector,
            RunStateRepository runStateRepository
    ) {
        return new CanonicalContextSnapshotResolver(projector, runStateRepository);
    }
}
