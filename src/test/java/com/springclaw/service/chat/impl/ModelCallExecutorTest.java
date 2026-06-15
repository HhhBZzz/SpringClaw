package com.springclaw.service.chat.impl;

import com.springclaw.config.ai.SpringClawAiProperties;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.ai.AiProviderStateService;
import com.springclaw.service.usage.LlmUsageRecordService;
import com.springclaw.service.usage.ModelCallMetricsService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModelCallExecutorTest {

    @Test
    void shouldFailOverWithinSameProviderWhenCurrentModelTimesOut() throws Exception {
        AiProviderService providerService = newProviderService();
        providerService.switchActiveModel("coding-plan", "qwen3-coder-plus");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        ModelCallExecutor executor = new ModelCallExecutor(
                providerService,
                new ModelTransportGuardService(new ChatResponsePolicyService(""), 30),
                mock(LlmUsageRecordService.class),
                new ModelCallMetricsService(meterRegistry, true),
                2,
                0
        );

        ModelCallExecutor.ModelCallResult<String> result = executor.execute(
                providerService.activeClient(),
                "test-failover",
                true,
                client -> {
                    if ("qwen3-coder-plus".equals(client.model())) {
                        throw new SocketTimeoutException("Read timed out");
                    }
                    return "ok:" + client.model();
                }
        );

        assertThat(result.failedOver()).isTrue();
        assertThat(result.value()).isEqualTo("ok:qwen3.5-plus");
        assertThat(result.client().model()).isEqualTo("qwen3.5-plus");
        assertThat(providerService.activeClient().model()).isEqualTo("qwen3.5-plus");
        assertThat(meterRegistry.counter(
                "springclaw.ai.model.calls",
                "outcome", "success",
                "failover", "used",
                "retry", "none"
        ).count()).isEqualTo(1.0d);
        assertThat(meterRegistry.counter("springclaw.ai.model.failovers").count()).isEqualTo(1.0d);
    }

    @Test
    void shouldSkipCoolingModelAndUseHealthyAlternative() throws Exception {
        AiProviderService providerService = newProviderService();
        providerService.switchActiveModel("coding-plan", "qwen3-coder-plus");
        ModelTransportGuardService guardService = new ModelTransportGuardService(new ChatResponsePolicyService(""), 30);
        guardService.markModelFailure("coding-plan", "qwen3-coder-plus", new SocketTimeoutException("Read timed out"));

        ModelCallExecutor executor = new ModelCallExecutor(
                providerService,
                guardService,
                mock(LlmUsageRecordService.class),
                1
        );

        ModelCallExecutor.ModelCallResult<String> result = executor.execute(
                providerService.activeClient(),
                "test-cooling-skip",
                true,
                client -> "ok:" + client.model()
        );

        assertThat(result.value()).isEqualTo("ok:qwen3.5-plus");
        assertThat(result.client().model()).isEqualTo("qwen3.5-plus");
        assertThat(result.failedOver()).isTrue();
    }

    @Test
    void shouldRetrySameModelOnceBeforeFailingOver() throws Exception {
        AiProviderService providerService = newProviderService();
        ModelTransportGuardService guardService = new ModelTransportGuardService(new ChatResponsePolicyService(""), 30);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        ModelCallExecutor executor = new ModelCallExecutor(
                providerService,
                guardService,
                mock(LlmUsageRecordService.class),
                new ModelCallMetricsService(meterRegistry, true),
                0,
                1
        );
        AtomicInteger attempts = new AtomicInteger();

        ModelCallExecutor.ModelCallResult<String> result = executor.execute(
                providerService.activeClient(),
                "test-same-model-retry",
                true,
                client -> {
                    if (attempts.incrementAndGet() == 1) {
                        throw new RuntimeException("Premature EOF");
                    }
                    return "ok:" + client.model();
                }
        );

        assertThat(result.value()).isEqualTo("ok:qwen3.5-plus");
        assertThat(result.failedOver()).isFalse();
        assertThat(attempts).hasValue(2);
        assertThat(guardService.isModelCallEnabled(providerService.activeClient())).isTrue();
        assertThat(meterRegistry.counter(
                "springclaw.ai.model.calls",
                "outcome", "success",
                "failover", "none",
                "retry", "used"
        ).count()).isEqualTo(1.0d);
        assertThat(meterRegistry.counter("springclaw.ai.model.retries").count()).isEqualTo(1.0d);
    }

    @Test
    void shouldFailOverToAnotherProviderWhenCurrentProviderHasNoHealthyModel() throws Exception {
        AiProviderService providerService = newProviderService();
        providerService.switchActiveProvider("qwen");

        ModelCallExecutor executor = new ModelCallExecutor(
                providerService,
                new ModelTransportGuardService(new ChatResponsePolicyService(""), 30),
                mock(LlmUsageRecordService.class),
                1
        );

        ModelCallExecutor.ModelCallResult<String> result = executor.execute(
                providerService.activeClient(),
                "test-provider-failover",
                true,
                client -> {
                    if ("qwen".equals(client.providerId())) {
                        throw new SocketTimeoutException("Read timed out");
                    }
                    return "ok:" + client.providerId() + ":" + client.model();
                }
        );

        assertThat(result.failedOver()).isTrue();
        assertThat(result.value()).startsWith("ok:coding-plan:");
        assertThat(result.client().providerId()).isEqualTo("coding-plan");
    }

    private AiProviderService newProviderService() {
        SpringClawAiProperties properties = new SpringClawAiProperties();
        properties.getProviders().getCodingPlan().setApiKey("coding-plan-test-key");
        properties.getProviders().getCodingPlan().setBaseUrl("https://coding.dashscope.aliyuncs.com/v1");
        properties.getProviders().getCodingPlan().setModel("qwen3.5-plus");
        properties.getProviders().getCodingPlan().setModels(List.of("qwen3.5-plus", "qwen3-coder-plus", "qwen3-coder-next"));
        properties.getProviders().getQwen().setApiKey("qwen-test-key");
        properties.getProviders().getQwen().setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        properties.getProviders().getQwen().setModel("qwen3.5-plus");
        properties.getProviders().getQwen().setModels(List.of("qwen3.5-plus"));
        properties.setActiveProvider("coding-plan");

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("restClientBuilder", RestClient.builder());
        beanFactory.addBean("webClientBuilder", WebClient.builder());
        ObjectProvider<RestClient.Builder> restProvider = beanFactory.getBeanProvider(RestClient.Builder.class);
        ObjectProvider<WebClient.Builder> webProvider = beanFactory.getBeanProvider(WebClient.Builder.class);
        RetryTemplate retryTemplate = RetryTemplate.builder().maxAttempts(1).build();
        AiProviderStateService stateService = mock(AiProviderStateService.class);
        when(stateService.resolvePreferredProvider("coding-plan")).thenReturn("coding-plan");
        when(stateService.resolvePreferredModel(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(stateService.switchMode()).thenReturn("runtime-memory");

        return new AiProviderService(
                properties,
                stateService,
                mock(ToolCallingManager.class),
                retryTemplate,
                ObservationRegistry.NOOP,
                restProvider,
                webProvider
        );
    }
}
