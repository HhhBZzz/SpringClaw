package com.springclaw.service.chat.impl;

import com.springclaw.service.ai.AiProviderService;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * DeepSeek V4 的 reasoning 字段和 Spring AI 原生 tool-calling 往返存在兼容风险。
 */
final class DeepSeekChatCompatibility {

    private DeepSeekChatCompatibility() {
    }

    static boolean supportsNativeToolCalling(AiProviderService.ActiveChatClient client) {
        if (client == null) {
            return false;
        }
        return !("deepseek".equalsIgnoreCase(client.providerId()) && isDeepSeekV4(client.model()));
    }

    private static boolean isDeepSeekV4(String model) {
        if (!StringUtils.hasText(model)) {
            return false;
        }
        String normalized = model.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "");
        return normalized.contains("deepseekv4");
    }
}
