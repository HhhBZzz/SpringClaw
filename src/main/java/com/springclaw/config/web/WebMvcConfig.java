package com.springclaw.config.web;

import com.springclaw.web.auth.RoleAuthorizationInterceptor;
import com.springclaw.web.auth.TokenAuthenticationInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * Web 基础配置。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TokenAuthenticationInterceptor tokenAuthenticationInterceptor;
    private final RoleAuthorizationInterceptor roleAuthorizationInterceptor;
    private final String corsAllowedOrigins;
    private final String corsAllowedOriginPatterns;

    public WebMvcConfig(TokenAuthenticationInterceptor tokenAuthenticationInterceptor,
                        RoleAuthorizationInterceptor roleAuthorizationInterceptor,
                        @Value("${springclaw.web.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:4173,http://127.0.0.1:4173,http://localhost:3000,http://127.0.0.1:3000}") String corsAllowedOrigins,
                        @Value("${springclaw.web.cors.allowed-origin-patterns:}") String corsAllowedOriginPatterns) {
        this.tokenAuthenticationInterceptor = tokenAuthenticationInterceptor;
        this.roleAuthorizationInterceptor = roleAuthorizationInterceptor;
        this.corsAllowedOrigins = corsAllowedOrigins;
        this.corsAllowedOriginPatterns = corsAllowedOriginPatterns;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        CorsRegistration registration = registry.addMapping("/**")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization")
                .maxAge(3600);
        String[] allowedOrigins = splitCsv(corsAllowedOrigins);
        if (allowedOrigins.length > 0) {
            registration.allowedOrigins(allowedOrigins)
                    .allowCredentials(true);
        } else {
            // Origin patterns 支持通配(如 https://springclaw*.vercel.app 覆盖所有 Vercel 预览/生产域名,
            // 避免每次部署追精确域名)。前端 fetch 用 credentials:'include',
            // patterns 也必须 allowCredentials(true),否则浏览器拒读带 credentials 的响应。
            registration.allowedOriginPatterns(splitCsv(corsAllowedOriginPatterns))
                    .allowCredentials(true);
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenAuthenticationInterceptor)
                .addPathPatterns("/api/chat/**", "/api/admin/**", "/api/cache/**", "/api/memory/**", "/api/rabbitmq/**", "/api/tasks/**", "/api/runtime-console/**", "/api/tool-proposals/**");
        registry.addInterceptor(roleAuthorizationInterceptor)
                .addPathPatterns("/api/admin/**", "/api/cache/**", "/api/memory/**", "/api/rabbitmq/**", "/api/runtime-console/**");
    }

    private String[] splitCsv(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toArray(String[]::new);
    }
}
