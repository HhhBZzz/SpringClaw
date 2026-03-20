package com.openclaw.config.web;

import com.openclaw.web.auth.RoleAuthorizationInterceptor;
import com.openclaw.web.auth.TokenAuthenticationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 基础配置。
 *
 * 设计说明：
 * 1. 本阶段开放 CORS 便于前后端联调。
 * 2. 正式生产会在网关层或安全策略中进一步收敛来源域名。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TokenAuthenticationInterceptor tokenAuthenticationInterceptor;
    private final RoleAuthorizationInterceptor roleAuthorizationInterceptor;

    public WebMvcConfig(TokenAuthenticationInterceptor tokenAuthenticationInterceptor,
                        RoleAuthorizationInterceptor roleAuthorizationInterceptor) {
        this.tokenAuthenticationInterceptor = tokenAuthenticationInterceptor;
        this.roleAuthorizationInterceptor = roleAuthorizationInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedOrigins("*");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenAuthenticationInterceptor)
                .addPathPatterns("/api/chat/**", "/api/admin/**", "/api/cache/**", "/api/memory/**", "/api/rabbitmq/**");
        registry.addInterceptor(roleAuthorizationInterceptor)
                .addPathPatterns("/api/admin/**", "/api/cache/**", "/api/memory/**", "/api/rabbitmq/**");
    }
}
