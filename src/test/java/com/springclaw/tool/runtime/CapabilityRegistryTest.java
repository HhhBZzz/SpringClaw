package com.springclaw.tool.runtime;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRegistryTest {

    @Test
    void shouldDiscoverDescriptorOnAopProxiedToolPack() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AopConfig.class, ToolMethodAspect.class, ProxiedToolPack.class);
            context.refresh();

            CapabilityRegistry registry = new CapabilityRegistry(context);

            CapabilityRegistry.CapabilityEntry entry = registry.findById("proxy-weather");
            assertThat(entry).isNotNull();
            assertThat(entry.toolset()).isEqualTo("web");
            assertThat(entry.description()).isEqualTo("代理后的天气能力");
            assertThat(entry.toolPackBean().getClass().getName()).contains("SpringCGLIB");
        }
    }

    @Test
    void shouldExposeMethodLevelToolCatalog() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AopConfig.class, ToolMethodAspect.class, ProxiedToolPack.class);
            context.refresh();

            CapabilityRegistry registry = new CapabilityRegistry(context);

            assertThat(registry.listToolViews())
                    .singleElement()
                    .satisfies(tool -> {
                        assertThat(tool.get("name")).isEqualTo("proxy_weather");
                        assertThat(tool.get("methodName")).isEqualTo("weather");
                        assertThat(tool.get("runtimeToolName")).isEqualTo("ProxiedToolPack.weather");
                        assertThat(tool.get("packId")).isEqualTo("proxy-weather");
                        assertThat(tool.get("toolset")).isEqualTo("web");
                        assertThat(tool.get("riskLevel")).isEqualTo("read");
                        assertThat(tool.get("requiresConfirmation")).isEqualTo(false);
                        assertThat(tool.get("description")).isEqualTo("查询代理天气");
                    });
        }
    }

    @Configuration
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class AopConfig {
    }

    @Aspect
    static class ToolMethodAspect {
        @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
        public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
            return joinPoint.proceed();
        }
    }

    @Component
    @ToolPackDescriptor(
            id = "proxy-weather",
            toolset = "web",
            triggerKeywords = {"天气"},
            fallbackCandidate = true,
            riskLevel = "read",
            description = "代理后的天气能力"
    )
    static class ProxiedToolPack {
        @Tool(name = "proxy_weather", description = "查询代理天气")
        public String weather() {
            return "ok";
        }
    }
}
