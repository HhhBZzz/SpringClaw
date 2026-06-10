package com.springclaw.config.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * SpringClaw 多模型接入配置。
 */
@ConfigurationProperties(prefix = "springclaw.ai")
public class SpringClawAiProperties {

    private String activeProvider = "primary";
    private int requestTimeoutSeconds = 60;
    private final Providers providers = new Providers();
    private final State state = new State();

    public String getActiveProvider() {
        return activeProvider;
    }

    public void setActiveProvider(String activeProvider) {
        this.activeProvider = activeProvider;
    }

    public Providers getProviders() {
        return providers;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public State getState() {
        return state;
    }

    public static class Providers {

        private final Provider primary = new Provider();
        private final Provider qwen = new Provider();
        private final Provider codingPlan = new Provider();
        private final Provider deepSeek = new Provider();
        private final Provider volcengineCodingPlan = new Provider();

        public Provider getPrimary() {
            return primary;
        }

        public Provider getQwen() {
            return qwen;
        }

        public Provider getCodingPlan() {
            return codingPlan;
        }

        public Provider getDeepSeek() {
            return deepSeek;
        }

        public Provider getVolcengineCodingPlan() {
            return volcengineCodingPlan;
        }
    }

    public static class Provider {

        private boolean enabled = true;
        private String apiKey = "";
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        private String model = "qwen3.5-plus";
        private List<String> models = new ArrayList<>();
        private double temperature = 0.2D;

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

        public List<String> getModels() {
            return models;
        }

        public void setModels(List<String> models) {
            this.models = models;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }
    }

    public static class State {

        private boolean redisEnabled = true;
        private String redisKey = "springclaw:ai:active-provider";
        private String redisModelPrefix = "springclaw:ai:model:";
        private String auditSessionKey = "system:ai:provider";

        public boolean isRedisEnabled() {
            return redisEnabled;
        }

        public void setRedisEnabled(boolean redisEnabled) {
            this.redisEnabled = redisEnabled;
        }

        public String getRedisKey() {
            return redisKey;
        }

        public void setRedisKey(String redisKey) {
            this.redisKey = redisKey;
        }

        public String getRedisModelPrefix() {
            return redisModelPrefix;
        }

        public void setRedisModelPrefix(String redisModelPrefix) {
            this.redisModelPrefix = redisModelPrefix;
        }

        public String getAuditSessionKey() {
            return auditSessionKey;
        }

        public void setAuditSessionKey(String auditSessionKey) {
            this.auditSessionKey = auditSessionKey;
        }
    }
}
