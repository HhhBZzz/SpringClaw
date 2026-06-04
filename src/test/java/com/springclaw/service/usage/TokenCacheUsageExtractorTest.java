package com.springclaw.service.usage;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TokenCacheUsageExtractorTest {

    @Test
    void extractsDeepSeekPromptCacheHitAndMissTokens() {
        TokenCacheUsage usage = TokenCacheUsageExtractor.extract(180, Map.of(
                "usage", Map.of(
                        "prompt_tokens", 180,
                        "prompt_cache_hit_tokens", 120,
                        "prompt_cache_miss_tokens", 60
                )
        ));

        assertThat(usage.hitTokens()).isEqualTo(120);
        assertThat(usage.missTokens()).isEqualTo(60);
        assertThat(usage.known()).isTrue();
    }

    @Test
    void extractsOpenAiCachedTokensAndDerivesMissTokens() {
        TokenCacheUsage usage = TokenCacheUsageExtractor.extract(200, Map.of(
                "usage", Map.of(
                        "prompt_tokens_details", Map.of("cached_tokens", 150)
                )
        ));

        assertThat(usage.hitTokens()).isEqualTo(150);
        assertThat(usage.missTokens()).isEqualTo(50);
        assertThat(usage.known()).isTrue();
    }

    @Test
    void extractsAnthropicCacheReadAndCreationTokens() {
        TokenCacheUsage usage = TokenCacheUsageExtractor.extract(320, Map.of(
                "usage", Map.of(
                        "cache_read_input_tokens", 250,
                        "cache_creation_input_tokens", 70
                )
        ));

        assertThat(usage.hitTokens()).isEqualTo(250);
        assertThat(usage.missTokens()).isEqualTo(70);
        assertThat(usage.known()).isTrue();
    }
}
