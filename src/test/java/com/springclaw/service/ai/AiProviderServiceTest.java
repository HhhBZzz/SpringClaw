package com.springclaw.service.ai;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.config.ai.SpringClawAiProperties;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiProviderServiceTest {

    @Test
    void shouldDefaultToDeepSeekFriendlyRequestTimeout() {
        SpringClawAiProperties properties = new SpringClawAiProperties();

        assertThat(properties.getRequestTimeoutSeconds()).isGreaterThanOrEqualTo(60);
    }

    @Test
    void shouldSwitchToQwenWhenConfigured() {
        SpringClawAiProperties properties = new SpringClawAiProperties();
        properties.getProviders().getPrimary().setApiKey("primary-test-key");
        properties.getProviders().getPrimary().setBaseUrl("https://api.example.com");
        properties.getProviders().getPrimary().setModel("claude-opus-4-6");
        properties.getProviders().getQwen().setApiKey("qwen-test-key");
        properties.setActiveProvider("primary");

        AiProviderService service = newService(properties);

        assertThat(service.activeClient().providerId()).isEqualTo("primary");

        service.switchActiveProvider("qwen");

        assertThat(service.activeClient().providerId()).isEqualTo("qwen");
        assertThat(service.activeClient().model()).isEqualTo("qwen3.5-plus");
    }

    @Test
    void shouldRejectUnavailableProvider() {
        SpringClawAiProperties properties = new SpringClawAiProperties();
        properties.getProviders().getPrimary().setApiKey("primary-test-key");
        properties.getProviders().getPrimary().setBaseUrl("https://api.example.com");
        properties.getProviders().getPrimary().setModel("claude-opus-4-6");
        properties.getProviders().getQwen().setApiKey("");

        AiProviderService service = newService(properties);

        assertThatThrownBy(() -> service.switchActiveProvider("qwen"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模型提供方当前不可用");
    }

    @Test
    void shouldSwitchCodingPlanModelAndExposeAvailableModels() {
        SpringClawAiProperties properties = new SpringClawAiProperties();
        properties.getProviders().getPrimary().setApiKey("primary-test-key");
        properties.getProviders().getPrimary().setBaseUrl("https://api.example.com");
        properties.getProviders().getPrimary().setModel("claude-opus-4-6");
        properties.getProviders().getCodingPlan().setApiKey("coding-plan-test-key");
        properties.getProviders().getCodingPlan().setBaseUrl("https://coding.dashscope.aliyuncs.com/v1");
        properties.getProviders().getCodingPlan().setModel("qwen3.5-plus");
        properties.getProviders().getCodingPlan().setModels(List.of("qwen3.5-plus", "qwen3-coder-plus", "qwen3-coder-next"));
        properties.setActiveProvider("primary");

        AiProviderService service = newService(properties);

        service.switchActiveModel("coding-plan", "coder plus");

        assertThat(service.activeClient().providerId()).isEqualTo("coding-plan");
        assertThat(service.activeClient().model()).isEqualTo("qwen3-coder-plus");
        @SuppressWarnings("unchecked")
        List<AiProviderService.ProviderView> providers = (List<AiProviderService.ProviderView>) service.summary().get("providers");
        assertThat(providers).anySatisfy(view -> {
            assertThat(view.providerId()).isEqualTo("coding-plan");
            assertThat(view.availableModels()).contains("qwen3-coder-plus", "qwen3-coder-next");
            assertThat(view.model()).isEqualTo("qwen3-coder-plus");
        });
    }

    @Test
    void shouldRejectDeepSeekThinkingModelsOnSpringAiChatPath() {
        SpringClawAiProperties properties = new SpringClawAiProperties();
        properties.getProviders().getPrimary().setApiKey("primary-test-key");
        properties.getProviders().getPrimary().setBaseUrl("https://api.example.com");
        properties.getProviders().getPrimary().setModel("claude-opus-4-6");
        properties.getProviders().getDeepSeek().setApiKey("deepseek-test-key");
        properties.getProviders().getDeepSeek().setBaseUrl("https://api.deepseek.com");
        properties.getProviders().getDeepSeek().setModel("deepseek-v4-pro");
        properties.getProviders().getDeepSeek().setModels(List.of("deepseek-v4-pro"));
        properties.setActiveProvider("primary");

        AiProviderService service = newService(properties);

        assertThatThrownBy(() -> service.switchActiveModel("deepseek", "reasoner"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂不支持");

        service.switchActiveModel("deepseek", "deepseek-v4-pro");

        assertThat(service.activeClient().providerId()).isEqualTo("deepseek");
        assertThat(service.activeClient().model()).isEqualTo("deepseek-v4-pro");
        @SuppressWarnings("unchecked")
        List<AiProviderService.ProviderView> providers = (List<AiProviderService.ProviderView>) service.summary().get("providers");
        assertThat(providers).anySatisfy(view -> {
            assertThat(view.providerId()).isEqualTo("deepseek");
            assertThat(view.availableModels()).containsExactly("deepseek-v4-pro");
            assertThat(view.model()).isEqualTo("deepseek-v4-pro");
        });
    }

    @Test
    void shouldIgnorePersistedDeepSeekThinkingModelOnStartup() {
        SpringClawAiProperties properties = new SpringClawAiProperties();
        properties.getProviders().getDeepSeek().setApiKey("deepseek-test-key");
        properties.getProviders().getDeepSeek().setBaseUrl("https://api.deepseek.com");
        properties.getProviders().getDeepSeek().setModel("deepseek-v4-pro");
        properties.getProviders().getDeepSeek().setModels(List.of("deepseek-v4-pro"));
        properties.setActiveProvider("deepseek");

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("restClientBuilder", RestClient.builder());
        beanFactory.addBean("webClientBuilder", WebClient.builder());
        ObjectProvider<RestClient.Builder> restProvider = beanFactory.getBeanProvider(RestClient.Builder.class);
        ObjectProvider<WebClient.Builder> webProvider = beanFactory.getBeanProvider(WebClient.Builder.class);
        AiProviderStateService stateService = mock(AiProviderStateService.class);
        when(stateService.resolvePreferredProvider("deepseek")).thenReturn("deepseek");
        when(stateService.resolvePreferredModel("deepseek", "deepseek-v4-pro")).thenReturn("deepseek-reasoner");
        when(stateService.resolvePreferredModel("primary", "qwen3.5-plus")).thenAnswer(invocation -> invocation.getArgument(1));
        when(stateService.resolvePreferredModel("qwen", "qwen3.5-plus")).thenAnswer(invocation -> invocation.getArgument(1));
        when(stateService.resolvePreferredModel("coding-plan", "qwen3.5-plus")).thenAnswer(invocation -> invocation.getArgument(1));

        AiProviderService service = new AiProviderService(
                properties,
                stateService,
                mock(ToolCallingManager.class),
                RetryTemplate.builder().maxAttempts(1).build(),
                ObservationRegistry.NOOP,
                restProvider,
                webProvider,
                key -> ""
        );

        assertThat(service.activeClient().providerId()).isEqualTo("deepseek");
        assertThat(service.activeClient().model()).isEqualTo("deepseek-v4-pro");
        verify(stateService).persistActiveState("deepseek", "deepseek-v4-pro", "startup-sanitize");
    }

    @Test
    void shouldRepairPersistedDeprecatedDeepSeekAliasOnStartup() {
        SpringClawAiProperties properties = new SpringClawAiProperties();
        properties.getProviders().getDeepSeek().setApiKey("deepseek-test-key");
        properties.getProviders().getDeepSeek().setBaseUrl("https://api.deepseek.com");
        properties.getProviders().getDeepSeek().setModel("deepseek-v4-pro");
        properties.getProviders().getDeepSeek().setModels(List.of("deepseek-v4-pro"));
        properties.setActiveProvider("deepseek");

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("restClientBuilder", RestClient.builder());
        beanFactory.addBean("webClientBuilder", WebClient.builder());
        ObjectProvider<RestClient.Builder> restProvider = beanFactory.getBeanProvider(RestClient.Builder.class);
        ObjectProvider<WebClient.Builder> webProvider = beanFactory.getBeanProvider(WebClient.Builder.class);
        AiProviderStateService stateService = mock(AiProviderStateService.class);
        when(stateService.resolvePreferredProvider("deepseek")).thenReturn("deepseek");
        when(stateService.resolvePreferredModel("deepseek", "deepseek-v4-pro")).thenReturn("deepseek-chat");
        when(stateService.resolvePreferredModel("primary", "qwen3.5-plus")).thenAnswer(invocation -> invocation.getArgument(1));
        when(stateService.resolvePreferredModel("qwen", "qwen3.5-plus")).thenAnswer(invocation -> invocation.getArgument(1));
        when(stateService.resolvePreferredModel("coding-plan", "qwen3.5-plus")).thenAnswer(invocation -> invocation.getArgument(1));

        AiProviderService service = new AiProviderService(
                properties,
                stateService,
                mock(ToolCallingManager.class),
                RetryTemplate.builder().maxAttempts(1).build(),
                ObservationRegistry.NOOP,
                restProvider,
                webProvider,
                key -> ""
        );

        assertThat(service.activeClient().providerId()).isEqualTo("deepseek");
        assertThat(service.activeClient().model()).isEqualTo("deepseek-v4-pro");
        verify(stateService).persistActiveState("deepseek", "deepseek-v4-pro", "startup-sanitize");
    }

    @Test
    void shouldPreferExplicitEnvironmentProviderAndModelOverPersistedState() {
        SpringClawAiProperties properties = new SpringClawAiProperties();
        properties.getProviders().getPrimary().setApiKey("primary-test-key");
        properties.getProviders().getPrimary().setBaseUrl("https://api.example.com");
        properties.getProviders().getPrimary().setModel("claude-opus-4-6");
        properties.getProviders().getCodingPlan().setApiKey("coding-plan-test-key");
        properties.getProviders().getCodingPlan().setBaseUrl("https://coding.dashscope.aliyuncs.com/v1");
        properties.getProviders().getCodingPlan().setModel("qwen3.5-plus");
        properties.getProviders().getCodingPlan().setModels(List.of("qwen3.5-plus", "qwen3-coder-plus", "qwen3-coder-next"));
        properties.getProviders().getDeepSeek().setApiKey("deepseek-test-key");
        properties.getProviders().getDeepSeek().setBaseUrl("https://api.deepseek.com");
        properties.getProviders().getDeepSeek().setModel("deepseek-v4-pro");
        properties.getProviders().getDeepSeek().setModels(List.of("deepseek-v4-pro"));
        properties.setActiveProvider("primary");

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("restClientBuilder", RestClient.builder());
        beanFactory.addBean("webClientBuilder", WebClient.builder());
        ObjectProvider<RestClient.Builder> restProvider = beanFactory.getBeanProvider(RestClient.Builder.class);
        ObjectProvider<WebClient.Builder> webProvider = beanFactory.getBeanProvider(WebClient.Builder.class);
        RetryTemplate retryTemplate = RetryTemplate.builder().maxAttempts(1).build();
        AiProviderStateService stateService = mock(AiProviderStateService.class);
        when(stateService.resolvePreferredProvider("primary")).thenReturn("qwen");
        when(stateService.resolvePreferredModel("coding-plan", "qwen3.5-plus")).thenReturn("qwen3-coder-plus");
        when(stateService.resolvePreferredModel("primary", "claude-opus-4-6")).thenReturn("claude-opus-4-6");
        when(stateService.resolvePreferredModel("qwen", "qwen3.5-plus")).thenReturn("qwen3.5-plus");
        when(stateService.switchMode()).thenReturn("runtime-memory");

        AiProviderService service = new AiProviderService(
                properties,
                stateService,
                mock(ToolCallingManager.class),
                retryTemplate,
                ObservationRegistry.NOOP,
                restProvider,
                webProvider,
                key -> switch (key) {
                    case "SPRINGCLAW_AI_ACTIVE_PROVIDER" -> "coding-plan";
                    case "SPRINGCLAW_CODING_PLAN_MODEL" -> "qwen3.5-plus";
                    default -> "";
                }
        );

        assertThat(service.activeClient().providerId()).isEqualTo("coding-plan");
        assertThat(service.activeClient().model()).isEqualTo("qwen3.5-plus");
    }

    @Test
    void shouldExposeFailoverModelsInStableOrder() {
        SpringClawAiProperties properties = new SpringClawAiProperties();
        properties.getProviders().getCodingPlan().setApiKey("coding-plan-test-key");
        properties.getProviders().getCodingPlan().setBaseUrl("https://coding.dashscope.aliyuncs.com/v1");
        properties.getProviders().getCodingPlan().setModel("qwen3.5-plus");
        properties.getProviders().getCodingPlan().setModels(List.of("qwen3.5-plus", "qwen3-coder-plus", "qwen3-coder-next"));
        properties.setActiveProvider("coding-plan");

        AiProviderService service = newService(properties);
        service.switchActiveModel("coding-plan", "qwen3-coder-plus");

        assertThat(service.listFailoverModels("coding-plan", "qwen3-coder-plus"))
                .containsExactly("qwen3.5-plus", "qwen3-coder-next");
    }

    @Test
    void shouldUseNonDuplicatedPathsForBaseUrlsEndingWithV1() {
        assertThat(AiProviderService.sanitizeBaseUrl("https://coding.dashscope.aliyuncs.com/v1/"))
                .isEqualTo("https://coding.dashscope.aliyuncs.com/v1");

        AiProviderService.RequestPaths codingPlanPaths =
                AiProviderService.resolveApiPaths("https://coding.dashscope.aliyuncs.com/v1");
        assertThat(codingPlanPaths.completionsPath()).isEqualTo("/chat/completions");
        assertThat(codingPlanPaths.embeddingsPath()).isEqualTo("/embeddings");

        AiProviderService.RequestPaths primaryPaths =
                AiProviderService.resolveApiPaths("https://api.zhongzhuan.win");
        assertThat(primaryPaths.completionsPath()).isEqualTo("/v1/chat/completions");
        assertThat(primaryPaths.embeddingsPath()).isEqualTo("/v1/embeddings");
    }

    private AiProviderService newService(SpringClawAiProperties properties) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("restClientBuilder", RestClient.builder());
        beanFactory.addBean("webClientBuilder", WebClient.builder());
        ObjectProvider<RestClient.Builder> restProvider = beanFactory.getBeanProvider(RestClient.Builder.class);
        ObjectProvider<WebClient.Builder> webProvider = beanFactory.getBeanProvider(WebClient.Builder.class);
        RetryTemplate retryTemplate = RetryTemplate.builder().maxAttempts(1).build();
        AiProviderStateService stateService = mock(AiProviderStateService.class);
        when(stateService.resolvePreferredProvider("primary")).thenReturn("primary");
        when(stateService.resolvePreferredModel(anyString(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(stateService.switchMode()).thenReturn("runtime-memory");

        return new AiProviderService(
                properties,
                stateService,
                mock(ToolCallingManager.class),
                retryTemplate,
                ObservationRegistry.NOOP,
                restProvider,
                webProvider,
                key -> ""
        );
    }
}
