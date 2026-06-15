package com.springclaw.service.usage;

import com.springclaw.service.usage.impl.LlmUsageRecordServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmUsageRecordServiceTest {

    @Test
    void shouldAggregateUsageWhenDbDisabled() {
        LlmUsageRecordServiceImpl service = new LlmUsageRecordServiceImpl(false);

        service.recordChatResponse(
                new LlmUsageRecordService.ChatResponseContext(
                        "req-1",
                        "s-1",
                        "api",
                        "u-1",
                        "coding-plan",
                        "qwen3.5-plus",
                        "simplified-answer"
                ),
                buildResponse("qwen3.5-plus", 120, 45, 165)
        );
        service.recordChatResponse(
                new LlmUsageRecordService.ChatResponseContext(
                        "req-2",
                        "s-2",
                        "api",
                        "u-2",
                        "deepseek",
                        "deepseek-chat",
                        "final-answer"
                ),
                buildResponse("deepseek-chat", 90, 30, 120)
        );

        Map<String, Object> summary = service.summary(20);
        assertThat(summary.get("totalCalls")).isEqualTo(2L);
        assertThat(summary.get("usageKnownCount")).isEqualTo(2L);
        assertThat(summary.get("totalPromptTokens")).isEqualTo(210L);
        assertThat(summary.get("totalCompletionTokens")).isEqualTo(75L);
        assertThat(summary.get("totalTokens")).isEqualTo(285L);
        assertThat(summary.get("topProvider")).isEqualTo("coding-plan");

        assertThat(service.listRecent(10)).hasSize(2);
        assertThat(service.listRecent(10).get(0).getProviderId()).isEqualTo("deepseek");
    }

    @Test
    void shouldRecordUnknownUsageWhenProviderDoesNotReturnUsage() {
        LlmUsageRecordServiceImpl service = new LlmUsageRecordServiceImpl(false);

        service.recordChatResponse(
                new LlmUsageRecordService.ChatResponseContext(
                        "req-3",
                        "s-3",
                        "api",
                        "u-3",
                        "coding-plan",
                        "qwen3.5-plus",
                        "stream-final-answer"
                ),
                new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))),
                        ChatResponseMetadata.builder().model("qwen3.5-plus").build())
        );

        Map<String, Object> summary = service.summary(20);
        assertThat(summary.get("totalCalls")).isEqualTo(1L);
        assertThat(summary.get("usageKnownCount")).isEqualTo(0L);
        assertThat(summary.get("usageUnknownCount")).isEqualTo(1L);
        assertThat(summary.get("totalTokens")).isEqualTo(0L);
        assertThat(summary.get("promptCacheHealth")).isEqualTo("UNKNOWN");
        assertThat(summary.get("promptCacheInsight")).asString().contains("Provider 暂未返回");
        assertThat(summary.get("promptCacheRecommendation")).asString().contains("provider 是否返回");
        assertThat(service.listRecent(10).get(0).getUsageKnown()).isEqualTo(0);
    }

    @Test
    void shouldAggregatePromptCacheUsageWhenNativeProviderUsageExists() {
        LlmUsageRecordServiceImpl service = new LlmUsageRecordServiceImpl(false);

        service.recordChatResponse(
                new LlmUsageRecordService.ChatResponseContext(
                        "req-cache",
                        "s-cache",
                        "api",
                        "u-cache",
                        "deepseek",
                        "deepseek-v4-pro",
                        "agent-runtime-summary"
                ),
                buildResponse("deepseek-v4-pro", new NativeUsage(200, 40, Map.of(
                        "prompt_cache_hit_tokens", 160,
                        "prompt_cache_miss_tokens", 40
                )))
        );

        Map<String, Object> summary = service.summary(20);
        assertThat(summary.get("totalPromptCacheHitTokens")).isEqualTo(160L);
        assertThat(summary.get("totalPromptCacheMissTokens")).isEqualTo(40L);
        assertThat(summary.get("promptCacheKnownCount")).isEqualTo(1L);
        assertThat(summary.get("promptCacheHitRate")).isEqualTo(0.8d);

        var recent = service.listRecent(10).get(0);
        assertThat(recent.getPromptCacheHitTokens()).isEqualTo(160);
        assertThat(recent.getPromptCacheMissTokens()).isEqualTo(40);
        assertThat(recent.getRawUsageJson()).contains("prompt_cache_hit_tokens");
    }

    @Test
    void shouldMirrorPromptCacheUsageIntoMicrometerMetrics() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        LlmUsageMetricsService metricsService = new LlmUsageMetricsService(meterRegistry, true);
        LlmUsageRecordServiceImpl service = new LlmUsageRecordServiceImpl(false, metricsService);

        service.recordChatResponse(
                new LlmUsageRecordService.ChatResponseContext(
                        "req-cache-metrics",
                        "s-cache-metrics",
                        "api",
                        "u-cache",
                        "deepseek",
                        "deepseek-v4-pro",
                        "agent-runtime-summary"
                ),
                buildResponse("deepseek-v4-pro", new NativeUsage(200, 40, Map.of(
                        "prompt_cache_hit_tokens", 160,
                        "prompt_cache_miss_tokens", 40
                )))
        );

        assertThat(meterRegistry.counter("springclaw.ai.usage.responses", "usage", "known").count())
                .isEqualTo(1.0d);
        assertThat(meterRegistry.get("springclaw.ai.usage.tokens").tag("kind", "prompt_cache_hit").summary().totalAmount())
                .isEqualTo(160.0d);
        assertThat(meterRegistry.get("springclaw.ai.usage.tokens").tag("kind", "prompt_cache_miss").summary().totalAmount())
                .isEqualTo(40.0d);
        assertThat(meterRegistry.counter("springclaw.ai.prompt_cache.records", "status", "known").count())
                .isEqualTo(1.0d);
    }

    @Test
    void shouldExplainLowPromptCacheHitRate() {
        LlmUsageRecordServiceImpl service = new LlmUsageRecordServiceImpl(false);

        service.recordChatResponse(
                new LlmUsageRecordService.ChatResponseContext(
                        "req-cache-low",
                        "s-cache-low",
                        "api",
                        "u-cache",
                        "coding-plan",
                        "qwen3.5-plus",
                        "agent-runtime-summary"
                ),
                buildResponse("qwen3.5-plus", new NativeUsage(200, 40, Map.of(
                        "prompt_cache_hit_tokens", 20,
                        "prompt_cache_miss_tokens", 180
                )))
        );

        Map<String, Object> summary = service.summary(20);
        assertThat(summary.get("promptCacheHitRate")).isEqualTo(0.1d);
        assertThat(summary.get("promptCacheHealth")).isEqualTo("LOW");
        assertThat(summary.get("promptCacheInsight")).asString().contains("命中率偏低");
        assertThat(summary.get("promptCacheRecommendation")).asString().contains("稳定 system prompt");
    }

    private ChatResponse buildResponse(String model, int promptTokens, int completionTokens, int totalTokens) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok"))),
                ChatResponseMetadata.builder()
                        .model(model)
                        .usage(new DefaultUsage(promptTokens, completionTokens, totalTokens))
                        .build()
        );
    }

    private ChatResponse buildResponse(String model, org.springframework.ai.chat.metadata.Usage usage) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage("ok"))),
                ChatResponseMetadata.builder()
                        .model(model)
                        .usage(usage)
                        .build()
        );
    }

    private record NativeUsage(Integer promptTokens, Integer completionTokens, Object nativeUsage)
            implements org.springframework.ai.chat.metadata.Usage {

        @Override
        public Integer getPromptTokens() {
            return promptTokens;
        }

        @Override
        public Integer getCompletionTokens() {
            return completionTokens;
        }

        @Override
        public Object getNativeUsage() {
            return nativeUsage;
        }
    }
}
