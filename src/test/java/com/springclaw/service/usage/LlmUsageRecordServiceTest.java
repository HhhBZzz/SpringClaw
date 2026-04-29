package com.springclaw.service.usage;

import com.springclaw.service.usage.impl.LlmUsageRecordServiceImpl;
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
        assertThat(service.listRecent(10).get(0).getUsageKnown()).isEqualTo(0);
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
}
