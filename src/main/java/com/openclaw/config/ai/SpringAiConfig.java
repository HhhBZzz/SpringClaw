package com.openclaw.config.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 配置。
 *
 * 设计说明：
 * 1. 只负责注册 OpenClaw 自定义 AI 配置属性。
 * 2. 具体模型客户端由 AiProviderService 按 provider 动态创建。
 */
@Configuration
@EnableConfigurationProperties({
        OpenClawAiProperties.class,
        OpenClawEmbeddingProperties.class
})
public class SpringAiConfig {
}
