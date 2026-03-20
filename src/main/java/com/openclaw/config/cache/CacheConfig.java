package com.openclaw.config.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CacheConfig {

    @Bean("weatherCache")
    public Cache<String, String> weatherCache(@Value("${openclaw.cache.weather-ttl-minutes:10}") long ttlMinutes) {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(Math.max(1, ttlMinutes)))
                .maximumSize(256)
                .recordStats()
                .build();
    }

    @Bean("exchangeRateCache")
    public Cache<String, String> exchangeRateCache(@Value("${openclaw.cache.exchange-ttl-minutes:30}") long ttlMinutes) {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(Math.max(1, ttlMinutes)))
                .maximumSize(256)
                .recordStats()
                .build();
    }

    @Bean("newsCache")
    public Cache<String, String> newsCache(@Value("${openclaw.cache.news-ttl-minutes:5}") long ttlMinutes) {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(Math.max(1, ttlMinutes)))
                .maximumSize(256)
                .recordStats()
                .build();
    }
}
