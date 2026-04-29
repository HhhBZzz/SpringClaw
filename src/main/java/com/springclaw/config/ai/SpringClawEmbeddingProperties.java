package com.springclaw.config.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 独立 embedding 配置。
 *
 * 设计说明：
 * 1. 与聊天 provider 配置解耦，避免复用聊天 key 导致权限/路由混乱。
 * 2. 当前仅负责文本向量模型；后续如果接入多模态向量，再单独扩展。
 */
@ConfigurationProperties(prefix = "springclaw.embedding")
public class SpringClawEmbeddingProperties {

    private boolean enabled = false;
    private String apiKey = "";
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String embeddingsPath = "/embeddings";
    private String model = "text-embedding-v4";
    private Integer dimensions = 1024;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getEmbeddingsPath() {
        return embeddingsPath;
    }

    public void setEmbeddingsPath(String embeddingsPath) {
        this.embeddingsPath = embeddingsPath;
    }

    public Integer getDimensions() {
        return dimensions;
    }

    public void setDimensions(Integer dimensions) {
        this.dimensions = dimensions;
    }
}
