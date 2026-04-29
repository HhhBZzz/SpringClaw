package com.springclaw.config.web;

import com.springclaw.web.auth.RoleAuthorizationInterceptor;
import com.springclaw.web.auth.TokenAuthenticationInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 基础配置。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TokenAuthenticationInterceptor tokenAuthenticationInterceptor;
    private final RoleAuthorizationInterceptor roleAuthorizationInterceptor;
    private final String corsOrigins;

    public WebMvcConfig(TokenAuthenticationInterceptor tokenAuthenticationInterceptor,
                        RoleAuthorizationInterceptor roleAuthorizationInterceptor,
                        @Value("${springclaw.web.cors.allowed-origins:http://localhost:3000}") String corsOrigins) {
        this.tokenAuthenticationInterceptor = tokenAuthenticationInterceptor;
        this.roleAuthorizationInterceptor = roleAuthorizationInterceptor;
        this.corsOrigins = corsOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedOrigins(corsOrigins.split(","));
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tokenAuthenticationInterceptor)
                .addPathPatterns("/api/chat/**", "/api/admin/**", "/api/cache/**", "/api/memory/**", "/api/rabbitmq/**", "/api/tasks/**");
        registry.addInterceptor(roleAuthorizationInterceptor)
                .addPathPatterns("/api/admin/**", "/api/cache/**", "/api/memory/**", "/api/rabbitmq/**");
    }
}
