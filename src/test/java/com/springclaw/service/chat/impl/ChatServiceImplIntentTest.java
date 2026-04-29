package com.springclaw.service.chat.impl;

import com.springclaw.service.ai.AiProviderService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatServiceImplIntentTest {

    private final ModelControlIntentService intentService = new ModelControlIntentService(mock(AiProviderService.class));

    @Test
    void shouldTreatExplicitModelCommandAsProviderIntent() {
        assertThat(intentService.looksLikeProviderIntentCandidate("切换到千问", ""))
                .isTrue();
    }

    @Test
    void shouldTreatAmbiguousFollowUpAsProviderIntentWhenRecentContextExists() {
        String eventContext = """
                - USER: 当前模型是什么
                - ASSISTANT: 我当前使用的是 coding-plan 的 qwen3-coder-plus。
                - USER: 那切换到阿里那个 coder 吧
                """;

        assertThat(intentService.looksLikeProviderIntentCandidate("换成那个", eventContext))
                .isTrue();
    }

    @Test
    void shouldNotTreatAmbiguousFollowUpAsProviderIntentWithoutRecentContext() {
        assertThat(intentService.looksLikeProviderIntentCandidate("换成那个", ""))
                .isFalse();
    }
}
