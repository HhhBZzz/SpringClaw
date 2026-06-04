package com.springclaw.service.usage;

/**
 * Provider prompt-cache usage normalized across OpenAI-compatible APIs.
 */
public record TokenCacheUsage(Integer hitTokens, Integer missTokens, String rawUsageJson) {

    public static TokenCacheUsage unknown(String rawUsageJson) {
        return new TokenCacheUsage(null, null, rawUsageJson);
    }

    public boolean known() {
        return hitTokens != null || missTokens != null;
    }
}
