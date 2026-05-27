package com.springclaw.service.chat.impl;

import com.springclaw.service.ai.AiProviderService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DeepSeekChatCompatibilityTest {

    @Test
    void shouldDisableNativeToolCallingForDeepSeekV4() {
        AiProviderService.ActiveChatClient client = new AiProviderService.ActiveChatClient(
                "deepseek",
                "deepseek-v4-pro",
                "https://api.deepseek.com",
                null,
                true,
                ""
        );

        Assertions.assertFalse(DeepSeekChatCompatibility.supportsNativeToolCalling(client));
    }

    @Test
    void shouldAllowNativeToolCallingForOtherModels() {
        AiProviderService.ActiveChatClient client = new AiProviderService.ActiveChatClient(
                "qwen",
                "qwen-plus",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                null,
                true,
                ""
        );

        Assertions.assertTrue(DeepSeekChatCompatibility.supportsNativeToolCalling(client));
    }
}
