package com.springclaw.controller.ops;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.web.auth.RequireRole;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/cache")
@RequireRole({"ADMIN"})
public class CacheController {

    private final Cache<String, String> weatherCache;
    private final Cache<String, String> exchangeRateCache;
    private final Cache<String, String> newsCache;

    public CacheController(@Qualifier("weatherCache") Cache<String, String> weatherCache,
                           @Qualifier("exchangeRateCache") Cache<String, String> exchangeRateCache,
                           @Qualifier("newsCache") Cache<String, String> newsCache) {
        this.weatherCache = weatherCache;
        this.exchangeRateCache = exchangeRateCache;
        this.newsCache = newsCache;
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("weather", describe(weatherCache));
        payload.put("exchange", describe(exchangeRateCache));
        payload.put("news", describe(newsCache));
        return ApiResponse.success(payload);
    }

    private Map<String, Object> describe(Cache<String, String> cache) {
        CacheStats stats = cache.stats();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("estimatedSize", cache.estimatedSize());
        payload.put("hitCount", stats.hitCount());
        payload.put("missCount", stats.missCount());
        payload.put("hitRate", stats.hitRate());
        payload.put("evictionCount", stats.evictionCount());
        return payload;
    }
}
