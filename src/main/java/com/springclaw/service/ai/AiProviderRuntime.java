package com.springclaw.service.ai;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

final class AiProviderRuntime {

    private final String providerId;
    private final String baseUrl;
    private final String defaultModel;
    private final List<String> availableModels;
    private final boolean enabled;
    private final String unavailableReason;
    private final double temperature;
    private final OpenAiApi openAiApi;
    private final ToolCallingManager toolCallingManager;
    private final RetryTemplate retryTemplate;
    private final ObservationRegistry observationRegistry;
    private final ConcurrentMap<String, ChatClient> chatClientCache = new ConcurrentHashMap<>();
    private final AtomicReference<String> activeModel;

    private AiProviderRuntime(String providerId,
                              String baseUrl,
                              String defaultModel,
                              List<String> availableModels,
                              boolean enabled,
                              String unavailableReason,
                              double temperature,
                              OpenAiApi openAiApi,
                              ToolCallingManager toolCallingManager,
                              RetryTemplate retryTemplate,
                              ObservationRegistry observationRegistry) {
        this.providerId = providerId;
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
        this.availableModels = List.copyOf(availableModels == null ? List.of() : availableModels);
        this.enabled = enabled;
        this.unavailableReason = unavailableReason;
        this.temperature = temperature;
        this.openAiApi = openAiApi;
        this.toolCallingManager = toolCallingManager;
        this.retryTemplate = retryTemplate;
        this.observationRegistry = observationRegistry;
        this.activeModel = new AtomicReference<>(defaultModel);
    }

    static AiProviderRuntime unavailable(String providerId,
                                         String baseUrl,
                                         String defaultModel,
                                         List<String> availableModels,
                                         boolean enabled,
                                         String unavailableReason) {
        return new AiProviderRuntime(
                providerId,
                baseUrl,
                defaultModel,
                availableModels,
                enabled,
                unavailableReason,
                0.2D,
                null,
                null,
                null,
                null
        );
    }

    static AiProviderRuntime available(String providerId,
                                       String baseUrl,
                                       String defaultModel,
                                       List<String> availableModels,
                                       double temperature,
                                       OpenAiApi openAiApi,
                                       ToolCallingManager toolCallingManager,
                                       RetryTemplate retryTemplate,
                                       ObservationRegistry observationRegistry) {
        return new AiProviderRuntime(
                providerId,
                baseUrl,
                defaultModel,
                availableModels,
                true,
                "",
                temperature,
                openAiApi,
                toolCallingManager,
                retryTemplate,
                observationRegistry
        );
    }

    String providerId() {
        return providerId;
    }

    String baseUrl() {
        return baseUrl;
    }

    String defaultModel() {
        return defaultModel;
    }

    List<String> availableModels() {
        return availableModels;
    }

    boolean enabled() {
        return enabled;
    }

    boolean available() {
        return enabled && openAiApi != null && !StringUtils.hasText(unavailableReason);
    }

    String availableReason() {
        return available() ? "" : unavailableReason;
    }

    String currentModel() {
        return activeModel.get();
    }

    void setActiveModel(String modelId) {
        String resolved = resolveModel(modelId);
        if (StringUtils.hasText(resolved)) {
            activeModel.set(resolved);
        }
    }

    void restoreActiveModel(String preferredModel) {
        String resolved = resolveModel(preferredModel);
        activeModel.set(StringUtils.hasText(resolved) ? resolved : defaultModel);
    }

    boolean matchesModelExactly(String hint) {
        if (!StringUtils.hasText(hint)) {
            return false;
        }
        String normalizedHint = normalizeModelToken(hint);
        return availableModels.stream().anyMatch(model ->
                model.equalsIgnoreCase(hint.trim()) || normalizeModelToken(model).equals(normalizedHint)
        );
    }

    boolean matchesModelFuzzy(String hint) {
        if (!StringUtils.hasText(hint)) {
            return false;
        }
        String normalizedHint = normalizeModelToken(hint);
        return availableModels.stream().anyMatch(model -> matchesModelToken(normalizedHint, model));
    }

    String resolveModel(String hint) {
        if (!StringUtils.hasText(hint)) {
            return currentModel();
        }
        String trimmed = hint.trim();
        String normalizedHint = normalizeModelToken(trimmed);
        if (!StringUtils.hasText(normalizedHint)) {
            return currentModel();
        }

        List<String> exactMatches = availableModels.stream()
                .filter(model -> model.equalsIgnoreCase(trimmed) || normalizeModelToken(model).equals(normalizedHint))
                .toList();
        if (exactMatches.size() == 1) {
            return exactMatches.get(0);
        }
        if (exactMatches.size() > 1) {
            return "";
        }

        List<String> fuzzyMatches = availableModels.stream()
                .filter(model -> matchesModelToken(normalizedHint, model))
                .toList();
        if (fuzzyMatches.size() == 1) {
            return fuzzyMatches.get(0);
        }
        return "";
    }

    List<String> failoverModels(String failedModel) {
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        String resolvedFailed = resolveModel(failedModel);
        String current = currentModel();

        if (StringUtils.hasText(defaultModel)
                && !sameModel(defaultModel, resolvedFailed)
                && !sameModel(defaultModel, current)) {
            ordered.add(defaultModel);
        }

        for (String model : availableModels) {
            if (!StringUtils.hasText(model)) {
                continue;
            }
            if (sameModel(model, resolvedFailed) || sameModel(model, current)) {
                continue;
            }
            ordered.add(model);
        }
        return List.copyOf(ordered);
    }

    ChatClient activeChatClient() {
        return chatClientFor(currentModel());
    }

    private ChatClient chatClientFor(String model) {
        if (!available()) {
            return null;
        }
        String resolvedModel = StringUtils.hasText(resolveModel(model)) ? resolveModel(model) : model;
        if (!StringUtils.hasText(resolvedModel)) {
            resolvedModel = defaultModel;
        }
        String finalModel = resolvedModel;
        return chatClientCache.computeIfAbsent(finalModel, this::buildChatClient);
    }

    private ChatClient buildChatClient(String model) {
        OpenAiChatOptions chatOptions = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();
        return ChatClient.builder(
                OpenAiChatModel.builder()
                        .openAiApi(openAiApi)
                        .defaultOptions(chatOptions)
                        .toolCallingManager(toolCallingManager)
                        .retryTemplate(retryTemplate)
                        .observationRegistry(observationRegistry)
                        .build()
        ).build();
    }

    private boolean matchesModelToken(String normalizedHint, String model) {
        if (!StringUtils.hasText(normalizedHint) || !StringUtils.hasText(model)) {
            return false;
        }
        String normalizedModel = normalizeModelToken(model);
        return normalizedModel.contains(normalizedHint) || normalizedHint.contains(normalizedModel);
    }

    private String normalizeModelToken(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("-", "")
                .replace("_", "")
                .replace(".", "");
    }

    private boolean sameModel(String left, String right) {
        return StringUtils.hasText(left)
                && StringUtils.hasText(right)
                && left.trim().equalsIgnoreCase(right.trim());
    }
}
