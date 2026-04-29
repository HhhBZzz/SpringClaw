package com.springclaw.tool.pack;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Locale;
import java.util.Map;

/**
 * 汇率查询工具包（免 API Key 版本）。
 */
@Component
@SuppressWarnings("unchecked")
public class ExchangeRateToolPack {

    private final boolean enabled;
    private final String urlTemplate;
    private final RestClient restClient;
    private final Cache<String, String> exchangeRateCache;

    public ExchangeRateToolPack(boolean enabled, String urlTemplate, int timeoutSeconds) {
        this(enabled, urlTemplate, timeoutSeconds, defaultCache());
    }

    @Autowired
    public ExchangeRateToolPack(@Value("${springclaw.tools.exchange.enabled:true}") boolean enabled,
                                @Value("${springclaw.tools.exchange.url-template:https://open.er-api.com/v6/latest/{base}}") String urlTemplate,
                                @Value("${springclaw.tools.exchange.timeout-seconds:8}") int timeoutSeconds,
                                @Qualifier("exchangeRateCache") Cache<String, String> exchangeRateCache) {
        this.enabled = enabled;
        this.urlTemplate = StringUtils.hasText(urlTemplate)
                ? urlTemplate.trim()
                : "https://open.er-api.com/v6/latest/{base}";
        int safeTimeoutMs = Math.max(1, timeoutSeconds) * 1000;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(safeTimeoutMs);
        requestFactory.setReadTimeout(safeTimeoutMs);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.exchangeRateCache = exchangeRateCache;
    }

    private static Cache<String, String> defaultCache() {
        return Caffeine.newBuilder()
                .maximumSize(256)
                .build();
    }

    @Tool(description = "查询汇率（例如 base=USD, target=CNY）")
    public String queryExchangeRate(String baseCurrency, String targetCurrency) {
        if (!enabled) {
            return "汇率工具未开启";
        }
        if (!StringUtils.hasText(baseCurrency) || !StringUtils.hasText(targetCurrency)) {
            return "baseCurrency 和 targetCurrency 不能为空";
        }
        String base = baseCurrency.trim().toUpperCase(Locale.ROOT);
        String target = targetCurrency.trim().toUpperCase(Locale.ROOT);
        String cacheKey = base + ":" + target;
        String cached = exchangeRateCache.getIfPresent(cacheKey);
        if (StringUtils.hasText(cached)) {
            return cached;
        }
        String url = urlTemplate.replace("{base}", base);

        try {
            Map<String, Object> body = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                return "汇率服务返回为空";
            }
            Object ratesObj = body.get("rates");
            if (!(ratesObj instanceof Map<?, ?> ratesMap)) {
                return "汇率服务暂不可用";
            }
            Object rate = ratesMap.get(target);
            if (rate == null) {
                return "未找到币种: " + target;
            }
            String time = String.valueOf(body.getOrDefault("time_last_update_utc", "unknown"));
            String answer = "汇率: 1 " + base + " = " + rate + " " + target + "\n更新时间: " + time;
            exchangeRateCache.put(cacheKey, answer);
            return answer;
        } catch (Exception ex) {
            return "汇率查询失败: " + ex.getMessage();
        }
    }
}
