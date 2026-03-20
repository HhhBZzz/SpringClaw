package com.openclaw.tool.pack;

import com.openclaw.common.exception.BusinessException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 联网检索工具包。
 *
 * 设计说明：
 * 1. 提供轻量 web search 与 URL 文本抓取能力，补齐“可查外部信息”短板。
 * 2. 内置基础安全限制，默认禁止访问本机/内网地址，降低 SSRF 风险。
 */
@Component
public class WebSearchToolPack {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final RestClient restClient;
    private final boolean enabled;
    private final String searchUrlTemplate;
    private final boolean useProxyForFetch;
    private final int maxResponseChars;

    public WebSearchToolPack(@Value("${openclaw.tools.web.enabled:true}") boolean enabled,
                             @Value("${openclaw.tools.web.search-url-template:https://r.jina.ai/http://duckduckgo.com/?q={query}}") String searchUrlTemplate,
                             @Value("${openclaw.tools.web.use-proxy-for-fetch:true}") boolean useProxyForFetch,
                             @Value("${openclaw.tools.web.timeout-seconds:8}") int timeoutSeconds,
                             @Value("${openclaw.tools.web.max-response-chars:5000}") int maxResponseChars) {
        this.enabled = enabled;
        this.searchUrlTemplate = StringUtils.hasText(searchUrlTemplate)
                ? searchUrlTemplate.trim()
                : "https://r.jina.ai/http://duckduckgo.com/?q={query}";
        this.useProxyForFetch = useProxyForFetch;
        this.maxResponseChars = Math.max(1000, maxResponseChars);

        int safeTimeoutMs = Math.max(1, timeoutSeconds) * 1000;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(safeTimeoutMs);
        requestFactory.setReadTimeout(safeTimeoutMs);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
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

    @Tool(description = "抓取指定 URL 的文本内容摘要，用于读取官方文档或网页")
    public String fetchUrlText(String url) {
        ensureEnabled();
        String normalized = normalizeUrl(url);
        String fetchUrl = useProxyForFetch
                ? "https://r.jina.ai/http://" + normalized.replaceFirst("^https?://", "")
                : normalized;
        String body = doGet(fetchUrl);
        return "WEB_FETCH url=" + normalized + "\n" + compact(body);
    }

    private String doGet(String url) {
        try {
            String body = restClient.get()
                    .uri(url)
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

    private void ensureEnabled() {
        if (!enabled) {
            throw new BusinessException(40081, "联网检索未开启（openclaw.tools.web.enabled=false）");
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

    private String normalizeUrl(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BusinessException(40085, "URL 不能为空");
        }
        String url = raw.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception ex) {
            throw new BusinessException(40086, "URL 非法: " + raw);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!ALLOWED_SCHEMES.contains(scheme)) {
            throw new BusinessException(40087, "仅支持 http/https URL");
        }
        if (!StringUtils.hasText(host)) {
            throw new BusinessException(40088, "URL 缺少 host");
        }
        if (isLocalOrPrivateHost(host)) {
            throw new BusinessException(40089, "禁止访问本机或内网地址: " + host);
        }
        return uri.toString();
    }

    private boolean isLocalOrPrivateHost(String host) {
        if ("localhost".equals(host) || "127.0.0.1".equals(host) || "::1".equals(host)) {
            return true;
        }
        if (host.endsWith(".local")) {
            return true;
        }
        if (!host.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
            return false;
        }
        String[] parts = host.split("\\.");
        int a = parsePart(parts, 0);
        int b = parsePart(parts, 1);
        if (a == 10 || a == 127 || (a == 192 && b == 168) || (a == 169 && b == 254)) {
            return true;
        }
        return a == 172 && b >= 16 && b <= 31;
    }

    private int parsePart(String[] parts, int index) {
        try {
            return Integer.parseInt(parts[index]);
        } catch (Exception ex) {
            return -1;
        }
    }

    private String compact(String raw) {
        String text = raw == null ? "" : raw.replace("\r", "").trim();
        if (text.length() <= maxResponseChars) {
            return text;
        }
        return text.substring(0, maxResponseChars) + "\n...<TRUNCATED>";
    }
}
