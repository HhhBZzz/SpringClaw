package com.springclaw.service.ai;

import io.micrometer.observation.ObservationRegistry;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
            // 给 WebClient 配超时：connect + responseTimeout + ReadTimeoutHandler/WriteTimeoutHandler。
            // 关键在 ReadTimeoutHandler —— responseTimeout 只覆盖"到首字节"的等待，
            // SSE 流一旦开始、上游中途 stall（已发 header 后不再吐 token）时 responseTimeout 不触发；
            // ReadTimeoutHandler 按每次读空闲计时，能抓"流式中途卡住"，stall 时抛 ReadTimeoutException，
            // 被 ModelTransportGuardService.isTransportFailure 识别为传输失败 → 触发同模型重试 / failover。
            int timeoutSeconds = Math.max(1, requestTimeoutSeconds);
            int timeoutMs = timeoutSeconds * 1000;
            HttpClient httpClient = HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                    .responseTimeout(Duration.ofSeconds(timeoutSeconds))
                    .doOnConnected(c -> c
                            .addHandlerLast(new ReadTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS))
                            .addHandlerLast(new WriteTimeoutHandler(timeoutSeconds, TimeUnit.SECONDS)));
            apiBuilder.webClientBuilder(
                    webClientBuilder.clone().clientConnector(new ReactorClientHttpConnector(httpClient)));
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
        // 火山引擎 Coding Plan: https://ark.cn-beijing.volces.com/api/coding/v3
        // 标准 OpenAI 兼容: https://api.xxx.com/v1
        // 任何以版本前缀（/v1, /v3, /v4 等）结尾的 URL 都只需要追加 /chat/completions
        if (sanitized.endsWith("/v1") || sanitized.endsWith("/v3") || sanitized.endsWith("/v4")
                || sanitized.matches(".*/v\\d+(/)?$")) {
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
