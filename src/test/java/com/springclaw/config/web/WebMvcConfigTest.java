package com.springclaw.config.web;

import com.springclaw.web.auth.RoleAuthorizationInterceptor;
import com.springclaw.web.auth.TokenAuthenticationInterceptor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.mockito.Mockito.mock;

class WebMvcConfigTest {

    @Test
    void shouldAllowCredentialsOnlyForExplicitCorsOrigins() {
        WebMvcConfig config = new WebMvcConfig(
                mock(TokenAuthenticationInterceptor.class),
                mock(RoleAuthorizationInterceptor.class),
                "http://localhost:5173",
                ""
        );
        ExposedCorsRegistry registry = new ExposedCorsRegistry();

        config.addCorsMappings(registry);

        CorsConfiguration cors = registry.configs().get("/**");
        Assertions.assertEquals(Boolean.TRUE, cors.getAllowCredentials());
        Assertions.assertEquals("http://localhost:5173", cors.getAllowedOrigins().get(0));
    }

    @Test
    void shouldDisableCredentialsWhenOnlyOriginPatternsAreConfigured() {
        WebMvcConfig config = new WebMvcConfig(
                mock(TokenAuthenticationInterceptor.class),
                mock(RoleAuthorizationInterceptor.class),
                "",
                "http://192.168.*:*"
        );
        ExposedCorsRegistry registry = new ExposedCorsRegistry();

        config.addCorsMappings(registry);

        CorsConfiguration cors = registry.configs().get("/**");
        Assertions.assertEquals(Boolean.FALSE, cors.getAllowCredentials());
        Assertions.assertEquals("http://192.168.*:*", cors.getAllowedOriginPatterns().get(0));
    }

    private static final class ExposedCorsRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configs() {
            return getCorsConfigurations();
        }
    }
}
