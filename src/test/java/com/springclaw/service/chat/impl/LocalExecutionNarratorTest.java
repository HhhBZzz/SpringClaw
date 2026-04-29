package com.springclaw.service.chat.impl;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalExecutionNarratorTest {

    private final ChatResponsePolicyService policyService = new ChatResponsePolicyService("");
    private final ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
    private final LocalExecutionNarrator narrator = new LocalExecutionNarrator(policyService, modelCallExecutor);

    @Test
    void shouldReturnNarratedAnswerWhenModelCallSucceeds() throws Exception {
        AiProviderService.ActiveChatClient activeClient = mock(AiProviderService.ActiveChatClient.class);
        when(modelCallExecutor.executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenReturn(new ModelCallExecutor.ModelCallResult<>(
                        "这是整理后的自然回答。",
                        activeClient,
                        List.of("qwen3.5-plus"),
                        false
                ));

        String answer = narrator.narrate(
                "system",
                assembled("你现在是什么模型"),
                new LocalSkillFallbackService.LocalSkillResult(
                        "MODEL_PROVIDER_QUERY",
                        "当前模型: qwen3.5-plus",
                        "我当前使用的是 qwen3.5-plus。",
                        false
                ),
                activeClient,
                true
        );

        assertThat(answer).isEqualTo("这是整理后的自然回答。");
    }

    @Test
    void shouldFallBackToDeterministicAnswerWhenNarrationTimesOut() throws Exception {
        AiProviderService.ActiveChatClient activeClient = mock(AiProviderService.ActiveChatClient.class);
        when(modelCallExecutor.executeChat(any(), anyString(), any(), anyBoolean(), any()))
                .thenThrow(new SocketTimeoutException("Read timed out"));

        String answer = narrator.narrate(
                "system",
                assembled("你现在是什么模型"),
                new LocalSkillFallbackService.LocalSkillResult(
                        "MODEL_PROVIDER_QUERY",
                        "当前模型: qwen3.5-plus",
                        "我当前使用的是 qwen3.5-plus。",
                        false
                ),
                activeClient,
                true
        );

        assertThat(answer).isEqualTo("我当前使用的是 qwen3.5-plus。");
    }

    private AssembledContext assembled(String question) {
        return new AssembledContext("s1", "api", "u1", question, "", "", question);
    }
}
