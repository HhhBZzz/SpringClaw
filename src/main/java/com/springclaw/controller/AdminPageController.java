package com.springclaw.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 后台兼容入口：实际页面统一由 Vue 前端承载。
 */
@Controller
public class AdminPageController {

    private static final String DEFAULT_FRONTEND_BASE_URL = "http://localhost:5173";
    private static final String ADMIN_ROUTE_PATH = "/#/admin";

    private final String frontendBaseUrl;

    public AdminPageController(@Value("${springclaw.frontend.base-url:" + DEFAULT_FRONTEND_BASE_URL + "}") String frontendBaseUrl,
                               @Value("${springclaw.frontend.allowed-hosts:localhost,127.0.0.1,::1}") String allowedHosts) {
        String normalized = trimTrailingSlash(frontendBaseUrl);
        validateFrontendBaseUrl(normalized, parseAllowedHosts(allowedHosts));
        this.frontendBaseUrl = normalized;
    }

    @GetMapping({"/admin", "/admin/"})
    public String adminPage() {
        return "redirect:" + frontendBaseUrl + ADMIN_ROUTE_PATH;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_FRONTEND_BASE_URL;
        }
        return value.trim().replaceAll("/+$", "");
    }

    private static void validateFrontendBaseUrl(String value, Set<String> allowedHosts) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("frontend base-url must use http or https");
            }
            if (uri.getRawUserInfo() != null) {
                throw new IllegalArgumentException("frontend base-url must not include user info");
            }
            if (host == null || !allowedHosts.contains(host.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("frontend base-url host is not allowed: " + host);
            }
            if (uri.getRawFragment() != null || uri.getRawQuery() != null) {
                throw new IllegalArgumentException("frontend base-url must not include query or fragment");
            }
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("invalid frontend base-url", ex);
        }
    }

    private static Set<String> parseAllowedHosts(String value) {
        Set<String> hosts = Arrays.stream((value == null ? "" : value).split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(item -> item.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        return hosts.isEmpty() ? Set.of("localhost", "127.0.0.1", "::1") : hosts;
    }
}
