package com.openclaw.service.ai;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class AiProviderRuntimeFactory {

    private final int requestTimeoutSeconds;
    private final ToolCallingManager toolCallingManager;
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;
    private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;
    private final ObjectProvider<WebClient.Builder> webClientBuilderProvider;

    AiProviderRuntimeFactory(int requestTimeoutSeconds,
                             ToolCallingManager toolCallingManager,
                             RetryTemplate retryTemplate,
                             ObservationRegistry observationRegistry,
                             ObjectProvider<RestClient.Builder> restClientBuilderProvider,
                             ObjectProvider<WebClient.Builder> webClientBuilderProvider) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.toolCallingManager = toolCallingManager;
        this.retryTemplate = retryTemplate;
        this.observationRegistry = observationRegistry;
        this.restClientBuilderProvider = restClientBuilderProvider;
        this.webClientBuilderProvider = webClientBuilderProvider;
    }

    AiProviderRuntime buildRuntime(String providerId,
                                   boolean enabled,
                                   String apiKey,
                                   String baseUrl,
                                   String model,
                                   List<String> models,
                                   double temperature) {
        String sanitizedBaseUrl = sanitizeBaseUrl(baseUrl);
        List<String> availableModels = sanitizeModels(model, models);
        String defaultModel = availableModels.isEmpty() ? safe(model).trim() : availableModels.get(0);
        String unavailableReason = validateProvider(enabled, apiKey, sanitizedBaseUrl, defaultModel);
        if (unavailableReason != null) {
            return AiProviderRuntime.unavailable(
                    providerId,
                    sanitizedBaseUrl,
                    defaultModel,
                    availableModels,
                    enabled,
                    unavailableReason
            );
        }

        AiProviderPaths requestPaths = resolveApiPaths(sanitizedBaseUrl);
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(sanitizedBaseUrl)
                .completionsPath(requestPaths.completionsPath())
                .embeddingsPath(requestPaths.embeddingsPath())
                .apiKey(apiKey);

        RestClient.Builder restClientBuilder = restClientBuilderProvider.getIfAvailable();
        if (restClientBuilder != null) {
            SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(requestTimeoutSeconds * 1000);
            requestFactory.setReadTimeout(requestTimeoutSeconds * 1000);
            apiBuilder.restClientBuilder(restClientBuilder.clone().requestFactory(requestFactory));
        }

        WebClient.Builder webClientBuilder = webClientBuilderProvider.getIfAvailable();
        if (webClientBuilder != null) {
            apiBuilder.webClientBuilder(webClientBuilder.clone());
        }

        return AiProviderRuntime.available(
                providerId,
                sanitizedBaseUrl,
                defaultModel,
                availableModels,
                temperature,
                apiBuilder.build(),
                toolCallingManager,
                retryTemplate,
                observationRegistry
        );
    }

    static String sanitizeBaseUrl(String baseUrl) {
        String sanitized = safe(baseUrl).trim();
        while (sanitized.endsWith("/")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        return sanitized;
    }

    static AiProviderPaths resolveApiPaths(String baseUrl) {
        String sanitized = sanitizeBaseUrl(baseUrl).toLowerCase(Locale.ROOT);
        if (sanitized.endsWith("/v1")) {
            return new AiProviderPaths("/chat/completions", "/embeddings");
        }
        return new AiProviderPaths("/v1/chat/completions", "/v1/embeddings");
    }

    private List<String> sanitizeModels(String defaultModel, List<String> configuredModels) {
        Set<String> ordered = new LinkedHashSet<>();
        if (StringUtils.hasText(defaultModel)) {
            ordered.add(defaultModel.trim());
        }
        if (configuredModels != null) {
            configuredModels.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(ordered::add);
        }
        return List.copyOf(ordered);
    }

    private String validateProvider(boolean enabled, String apiKey, String baseUrl, String model) {
        if (!enabled) {
            return "已禁用";
        }
        if (!StringUtils.hasText(apiKey) || "test-key".equals(apiKey)) {
            return "未配置有效 API Key";
        }
        if (!StringUtils.hasText(baseUrl)) {
            return "未配置 base-url";
        }
        if (!StringUtils.hasText(model)) {
            return "未配置模型名";
        }
        return null;
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }
}
