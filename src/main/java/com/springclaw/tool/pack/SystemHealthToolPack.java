package com.springclaw.tool.pack;

import com.springclaw.tool.runtime.ToolPackDescriptor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Runtime health tool for backend, database, Redis and broker status.
 */
@Component
@ToolPackDescriptor(
    id = "system-health",
    toolset = "system",
    triggerKeywords = {"检查后端", "后端状态", "运行状态", "健康状态", "数据库", "redis", "rabbitmq", "runtime health", "system health"},
    fallbackCandidate = false,
    riskLevel = "read",
    description = "检查 Spring Boot、数据库、Redis 等组件健康状态"
)
public class SystemHealthToolPack {

    private final HealthEndpoint healthEndpoint;

    public SystemHealthToolPack(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @Tool(description = "检查 SpringClaw 后端运行状态，包括 Spring Boot health、数据库、Redis、RabbitMQ 等组件")
    public String runtimeHealth() {
        Object health = healthEndpoint.health();
        StringBuilder builder = new StringBuilder();
        builder.append("health=").append(statusOf(health));
        Map<?, ?> components = componentsOf(health);
        if (!components.isEmpty()) {
            for (Map.Entry<?, ?> entry : components.entrySet()) {
                builder.append("\n")
                        .append(entry.getKey())
                        .append("=")
                        .append(statusOf(entry.getValue()));
            }
        }
        return builder.toString();
    }

    private String statusOf(Object component) {
        if (component == null) {
            return "UNKNOWN";
        }
        try {
            Method method = component.getClass().getMethod("getStatus");
            Object status = method.invoke(component);
            return status == null ? "UNKNOWN" : String.valueOf(status);
        } catch (Exception ignored) {
            return String.valueOf(component);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> componentsOf(Object component) {
        if (component == null) {
            return Map.of();
        }
        try {
            Method method = component.getClass().getMethod("getComponents");
            Object components = method.invoke(component);
            if (components instanceof Map<?, ?> map) {
                return map;
            }
            return Map.of();
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}
