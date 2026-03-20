package com.openclaw.tool.pack;

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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 新闻检索工具包（免 API Key 版本）。
 */
@Component
@SuppressWarnings("unchecked")
public class NewsToolPack {

    private final boolean enabled;
    private final String searchUrlTemplate;
    private final int maxItems;
    private final RestClient restClient;
    private final Cache<String, String> newsCache;

    public NewsToolPack(boolean enabled, String searchUrlTemplate, int maxItems, int timeoutSeconds) {
        this(enabled, searchUrlTemplate, maxItems, timeoutSeconds, defaultCache());
    }

    @Autowired
    public NewsToolPack(@Value("${openclaw.tools.news.enabled:true}") boolean enabled,
                        @Value("${openclaw.tools.news.search-url-template:https://hn.algolia.com/api/v1/search?query={query}&tags=story}") String searchUrlTemplate,
                        @Value("${openclaw.tools.news.max-items:5}") int maxItems,
                        @Value("${openclaw.tools.news.timeout-seconds:8}") int timeoutSeconds,
                        @Qualifier("newsCache") Cache<String, String> newsCache) {
        this.enabled = enabled;
        this.searchUrlTemplate = StringUtils.hasText(searchUrlTemplate)
                ? searchUrlTemplate.trim()
                : "https://hn.algolia.com/api/v1/search?query={query}&tags=story";
        this.maxItems = Math.max(1, Math.min(maxItems, 10));
        int safeTimeoutMs = Math.max(1, timeoutSeconds) * 1000;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(safeTimeoutMs);
        requestFactory.setReadTimeout(safeTimeoutMs);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.newsCache = newsCache;
    }

    private static Cache<String, String> defaultCache() {
        return Caffeine.newBuilder()
                .maximumSize(256)
                .build();
    }

    @Tool(description = "按关键词检索新闻摘要（输入关键字，例如 AI、Spring、OpenAI）")
    public String searchNews(String keyword) {
        if (!enabled) {
            return "新闻工具未开启";
        }
        if (!StringUtils.hasText(keyword)) {
            return "请输入关键词";
        }
        String query = keyword.trim();
        String cacheKey = query.toLowerCase();
        String cached = newsCache.getIfPresent(cacheKey);
        if (StringUtils.hasText(cached)) {
            return cached;
        }
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String url = searchUrlTemplate.replace("{query}", encoded);

        try {
            Map<String, Object> body = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                return "新闻服务返回为空";
            }
            Object hitsObj = body.get("hits");
            if (!(hitsObj instanceof List<?> hitsList) || hitsList.isEmpty()) {
                return "未检索到相关新闻";
            }

            List<String> lines = new ArrayList<>();
            int count = 0;
            for (Object obj : hitsList) {
                if (!(obj instanceof Map<?, ?> hit)) {
                    continue;
                }
                Object titleObj = hit.get("title");
                Object linkObj = hit.get("url");
                Object timeObj = hit.get("created_at");
                String title = titleObj == null ? "" : String.valueOf(titleObj);
                String link = linkObj == null ? "" : String.valueOf(linkObj);
                String time = timeObj == null ? "" : String.valueOf(timeObj);
                if (!StringUtils.hasText(title)) {
                    continue;
                }
                count++;
                lines.add(count + ". " + title + (StringUtils.hasText(link) ? "\n   " + link : "") + (StringUtils.hasText(time) ? "\n   " + time : ""));
                if (count >= maxItems) {
                    break;
                }
            }
            if (lines.isEmpty()) {
                return "未检索到相关新闻";
            }
            String answer = "关键词: " + query + "\n" + String.join("\n", lines);
            newsCache.put(cacheKey, answer);
            return answer;
        } catch (Exception ex) {
            return "新闻检索失败: " + ex.getMessage();
        }
    }
}
