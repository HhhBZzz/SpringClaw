package com.springclaw.tool.pack;

import com.springclaw.common.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 联网检索工具包。
 *
 * 设计说明：
 * 1. 提供轻量 web search 能力，适合公开网页资料搜索。
 * 2. 网页正文抓取已迁移到 Python skill，避免 Java 主链路继续承担爬虫职责。
 */
@Component
public class WebSearchToolPack {

    private static final Logger log = LoggerFactory.getLogger(WebSearchToolPack.class);
    private static final String BROWSER_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

    private final RestClient restClient;
    private final boolean enabled;
    private final String searchUrlTemplate;
    private final int maxResponseChars;

    @Autowired
    public WebSearchToolPack(@Value("${springclaw.tools.web.enabled:true}") boolean enabled,
                             @Value("${springclaw.tools.web.search-url-template:https://r.jina.ai/http://duckduckgo.com/?q={query}}") String searchUrlTemplate,
                             @Value("${springclaw.tools.web.use-proxy-for-fetch:true}") boolean useProxyForFetch,
                             @Value("${springclaw.tools.web.timeout-seconds:12}") int timeoutSeconds,
                             @Value("${springclaw.tools.web.max-response-chars:5000}") int maxResponseChars) {
        this(enabled, searchUrlTemplate, useProxyForFetch, maxResponseChars, buildRestClient(timeoutSeconds));
    }

    WebSearchToolPack(boolean enabled,
                      String searchUrlTemplate,
                      boolean useProxyForFetch,
                      int maxResponseChars,
                      RestClient restClient) {
        this.enabled = enabled;
        this.searchUrlTemplate = StringUtils.hasText(searchUrlTemplate)
                ? searchUrlTemplate.trim()
                : "https://r.jina.ai/http://duckduckgo.com/?q={query}";
        this.maxResponseChars = Math.max(1000, maxResponseChars);
        this.restClient = restClient;
    }

    @Tool(description = "联网搜索公开网页信息，返回精简后的文本结果")
    public String webSearch(String query) {
        ensureEnabled();
        String key = normalizeQuery(query);
        String encoded = URLEncoder.encode(key, StandardCharsets.UTF_8);
        String url = searchUrlTemplate.replace("{query}", encoded);
        String body = doGet(url);
        return "WEB_SEARCH query=" + key + "\n" + compact(body);
    }

    public String fetchUrlText(String url) {
        log.warn("Java 网页抓取入口已停用，url={}", url);
        throw new BusinessException(40090, "网页正文抓取已迁移到 Python web skill，请改用 web_crawler");
    }

    private String doGet(String url) {
        try {
            String body = restClient.get()
                    .uri(URI.create(url))
                    .header(HttpHeaders.USER_AGENT, BROWSER_USER_AGENT)
                    .header(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.7")
                    .header(HttpHeaders.ACCEPT_LANGUAGE, "zh-CN,zh;q=0.9,en;q=0.8")
                    .retrieve()
                    .body(String.class);
            if (!StringUtils.hasText(body)) {
                return "（网页返回为空）";
            }
            return body;
        } catch (Exception ex) {
            throw new BusinessException(50051, "联网检索失败: " + ex.getMessage());
        }
    }

    private static RestClient buildRestClient(int timeoutSeconds) {
        int safeTimeoutMs = Math.max(1, timeoutSeconds) * 1000;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(safeTimeoutMs);
        requestFactory.setReadTimeout(safeTimeoutMs);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    private void ensureEnabled() {
        if (!enabled) {
            throw new BusinessException(40081, "联网检索未开启（springclaw.tools.web.enabled=false）");
        }
    }

    private String normalizeQuery(String query) {
        if (!StringUtils.hasText(query)) {
            throw new BusinessException(40082, "搜索关键词不能为空");
        }
        String value = query.trim();
        if (value.length() < 2) {
            throw new BusinessException(40083, "搜索关键词至少 2 个字符");
        }
        if (value.length() > 120) {
            throw new BusinessException(40084, "搜索关键词过长");
        }
        return value;
    }

    private String compact(String raw) {
        String text = raw == null ? "" : raw.replace("\r", "").trim();
        if (text.length() <= maxResponseChars) {
            return text;
        }
        return text.substring(0, maxResponseChars) + "\n...<TRUNCATED>";
    }
}
