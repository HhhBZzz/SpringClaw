package com.springclaw.service.ai;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.config.ai.SpringClawAiProperties;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;

/**
 * OpenAI-compatible 模型提供方管理。
 */
@Service
public class AiProviderService {

    private static final Logger log = LoggerFactory.getLogger(AiProviderService.class);

    private final Map<String, AiProviderRuntime> providers;
    private final AtomicReference<String> activeProviderId;
    private final AiProviderStateService aiProviderStateService;
    private final AiProviderRuntimeFactory runtimeFactory;
    private final Function<String, String> envLookup;

    @Autowired
    public AiProviderService(SpringClawAiProperties aiProperties,
                             AiProviderStateService aiProviderStateService,
                             ToolCallingManager toolCallingManager,
                             RetryTemplate retryTemplate,
                             ObservationRegistry observationRegistry,
                             ObjectProvider<RestClient.Builder> restClientBuilderProvider,
                             ObjectProvider<WebClient.Builder> webClientBuilderProvider) {
        this(
                aiProperties,
                aiProviderStateService,
                toolCallingManager,
                retryTemplate,
                observationRegistry,
                restClientBuilderProvider,
                webClientBuilderProvider,
                System.getenv()::get
        );
    }

    AiProviderService(SpringClawAiProperties aiProperties,
                      AiProviderStateService aiProviderStateService,
                      ToolCallingManager toolCallingManager,
                      RetryTemplate retryTemplate,
                      ObservationRegistry observationRegistry,
                      ObjectProvider<RestClient.Builder> restClientBuilderProvider,
                      ObjectProvider<WebClient.Builder> webClientBuilderProvider,
                      Function<String, String> envLookup) {
        this.aiProviderStateService = aiProviderStateService;
        this.envLookup = envLookup;
        this.runtimeFactory = new AiProviderRuntimeFactory(
                Math.max(2, aiProperties.getRequestTimeoutSeconds()),
                toolCallingManager,
                retryTemplate,
                observationRegistry,
                restClientBuilderProvider,
                webClientBuilderProvider
        );
        this.providers = new LinkedHashMap<>();

        registerProvider("primary", aiProperties.getProviders().getPrimary());
        registerProvider("qwen", aiProperties.getProviders().getQwen());
        registerProvider("coding-plan", aiProperties.getProviders().getCodingPlan());
        registerProvider("deepseek", aiProperties.getProviders().getDeepSeek());

        Map<String, Boolean> repairedStartupModels = new LinkedHashMap<>();
        for (AiProviderRuntime runtime : providers.values()) {
            StartupModelChoice startupModel = resolveStartupModel(runtime);
            runtime.restoreActiveModel(startupModel.model());
            repairedStartupModels.put(runtime.providerId(), startupModel.repaired());
        }

        this.activeProviderId = new AtomicReference<>(resolveInitialProviderId(
                resolveStartupProvider(aiProperties.getActiveProvider())
        ));
        AiProviderRuntime active = this.providers.get(this.activeProviderId.get());
        if (active != null && Boolean.TRUE.equals(repairedStartupModels.get(active.providerId()))) {
            aiProviderStateService.persistActiveState(active.providerId(), active.currentModel(), "startup-sanitize");
        }
        log.info("当前活动模型提供方: provider={}, model={}, available={}",
                active.providerId(), active.currentModel(), active.available());
    }

    private void registerProvider(String providerId, SpringClawAiProperties.Provider provider) {
        registerProvider(
                providerId,
                provider.isEnabled(),
                provider.getApiKey(),
                provider.getBaseUrl(),
                provider.getModel(),
                provider.getModels(),
                provider.getTemperature()
        );
    }

    private void registerProvider(String providerId,
                                  boolean enabled,
                                  String apiKey,
                                  String baseUrl,
                                  String model,
                                  List<String> models,
                                  double temperature) {
        providers.put(providerId, runtimeFactory.buildRuntime(
                providerId,
                enabled,
                apiKey,
                baseUrl,
                model,
                models,
                temperature
        ));
    }

    public ActiveChatClient activeClient() {
        AiProviderRuntime runtime = providers.get(activeProviderId.get());
        if (runtime == null) {
            runtime = providers.values().stream().findFirst().orElseThrow(() ->
                    new BusinessException(50031, "未找到任何模型提供方配置"));
        }
        return new ActiveChatClient(
                runtime.providerId(),
                runtime.currentModel(),
                runtime.baseUrl(),
                runtime.activeChatClient(),
                runtime.available(),
                runtime.availableReason()
        );
    }

    public synchronized ProviderView switchActiveProvider(String providerId) {
        return switchActiveProvider(providerId, "runtime");
    }

    public synchronized ProviderView switchActiveProvider(String providerId, String source) {
        String normalized = normalizeProviderId(providerId);
        AiProviderRuntime runtime = requireAvailableProvider(normalized);
        sanitizeRuntimeModel(runtime);
        activeProviderId.set(normalized);
        aiProviderStateService.persistActiveState(normalized, runtime.currentModel(), source);
        return toView(runtime, true);
    }

    public synchronized ProviderView switchActiveModel(String providerId, String modelHint) {
        return switchActiveModel(providerId, modelHint, "runtime");
    }

    public synchronized ProviderView switchActiveModel(String providerId, String modelHint, String source) {
        String normalizedProvider = normalizeProviderId(StringUtils.hasText(providerId) ? providerId : activeProviderId.get());
        AiProviderRuntime runtime = requireAvailableProvider(normalizedProvider);
        String resolvedModel = runtime.resolveModel(modelHint);
        if (!StringUtils.hasText(resolvedModel)) {
            if (isChatUnsupportedModelHint(normalizedProvider, modelHint)) {
                throw new BusinessException(40043, unsupportedChatModelMessage(normalizedProvider, modelHint));
            }
            throw new BusinessException(40042, "未识别模型或模型未启用: " + safe(modelHint));
        }
        ensureChatCompatibleModel(normalizedProvider, resolvedModel);
        runtime.setActiveModel(resolvedModel);
        activeProviderId.set(normalizedProvider);
        aiProviderStateService.persistActiveState(normalizedProvider, resolvedModel, source);
        return toView(runtime, true);
    }

    public String resolveModelId(String providerId, String modelHint) {
        AiProviderRuntime runtime = providers.get(normalizeProviderId(providerId));
        if (runtime == null) {
            return "";
        }
        String resolved = runtime.resolveModel(modelHint);
        if (isUnsupportedChatModel(runtime.providerId(), resolved)) {
            return "";
        }
        return StringUtils.hasText(resolved) ? resolved : "";
    }

    public synchronized ActiveChatClient activateModel(String providerId, String modelHint, String source) {
        String normalizedProvider = normalizeProviderId(StringUtils.hasText(providerId) ? providerId : activeProviderId.get());
        AiProviderRuntime runtime = requireAvailableProvider(normalizedProvider);
        String resolvedModel = runtime.resolveModel(modelHint);
        if (!StringUtils.hasText(resolvedModel)) {
            if (isChatUnsupportedModelHint(normalizedProvider, modelHint)) {
                throw new BusinessException(40043, unsupportedChatModelMessage(normalizedProvider, modelHint));
            }
            throw new BusinessException(40042, "未识别模型或模型未启用: " + safe(modelHint));
        }
        ensureChatCompatibleModel(normalizedProvider, resolvedModel);
        runtime.setActiveModel(resolvedModel);
        activeProviderId.set(normalizedProvider);
        aiProviderStateService.persistActiveState(normalizedProvider, resolvedModel, source);
        return activeClient();
    }

    public List<String> listFailoverModels(String providerId, String failedModel) {
        AiProviderRuntime runtime = providers.get(normalizeProviderId(providerId));
        if (runtime == null || !runtime.available()) {
            return List.of();
        }
        return runtime.failoverModels(failedModel).stream()
                .filter(model -> !isUnsupportedChatModel(runtime.providerId(), model))
                .toList();
    }

    public String findProviderByModelHint(String modelHint, String preferredProviderId) {
        String preferred = normalizeProviderId(preferredProviderId);
        String current = activeProviderId.get();

        List<AiProviderRuntime> exactMatches = providers.values().stream()
                .filter(AiProviderRuntime::available)
                .filter(runtime -> runtime.matchesModelExactly(modelHint))
                .filter(runtime -> isSupportedResolvedModel(runtime, modelHint))
                .toList();
        String exact = chooseProvider(exactMatches, preferred, current);
        if (StringUtils.hasText(exact)) {
            return exact;
        }

        List<AiProviderRuntime> fuzzyMatches = providers.values().stream()
                .filter(AiProviderRuntime::available)
                .filter(runtime -> runtime.matchesModelFuzzy(modelHint))
                .filter(runtime -> isSupportedResolvedModel(runtime, modelHint))
                .toList();
        return chooseProvider(fuzzyMatches, preferred, current);
    }

    public boolean hasKnownModelHint(String modelHint) {
        return StringUtils.hasText(findProviderByModelHint(modelHint, null));
    }

    public boolean isChatUnsupportedModelHint(String providerId, String modelHint) {
        String normalizedProvider = normalizeProviderId(providerId);
        String candidate = resolveModelCandidate(normalizedProvider, modelHint);
        return isUnsupportedChatModel(normalizedProvider, candidate);
    }

    public String unsupportedChatModelMessage(String providerId, String modelHint) {
        String normalizedProvider = normalizeProviderId(providerId);
        String candidate = resolveModelCandidate(normalizedProvider, modelHint);
        if (!StringUtils.hasText(candidate)) {
            candidate = safe(modelHint);
        }
        return "当前主聊天链路暂不支持该 DeepSeek thinking 模型: " + candidate
                + "。请使用 deepseek-v4-pro 的普通聊天模式；显式 thinking/reasoner 需要单独 reasoning 适配器。";
    }

    public Map<String, Object> summary() {
        ActiveChatClient active = activeClient();
        List<ProviderView> providerViews = providers.values().stream()
                .map(runtime -> toView(runtime, runtime.providerId().equals(active.providerId())))
                .toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("activeProvider", active.providerId());
        payload.put("activeModel", active.model());
        payload.put("activeDisplay", active.displayName());
        payload.put("providers", providerViews);
        payload.put("switchMode", aiProviderStateService.switchMode());
        return payload;
    }

    private AiProviderRuntime requireAvailableProvider(String providerId) {
        AiProviderRuntime runtime = providers.get(providerId);
        if (runtime == null) {
            throw new BusinessException(40441, "模型提供方不存在: " + safe(providerId));
        }
        if (!runtime.available()) {
            throw new BusinessException(40041, "模型提供方当前不可用: " + runtime.availableReason());
        }
        return runtime;
    }

    private String resolveStartupProvider(String configuredProvider) {
        String configured = normalizeProviderId(configuredProvider);
        String explicit = safe(envLookup.apply("SPRINGCLAW_AI_ACTIVE_PROVIDER")).trim();
        if (StringUtils.hasText(explicit)) {
            return normalizeProviderId(explicit);
        }
        return aiProviderStateService.resolvePreferredProvider(configured);
    }

    private StartupModelChoice resolveStartupModel(AiProviderRuntime runtime) {
        String configuredDefault = runtime.defaultModel();
        String explicit = safe(envLookup.apply(modelEnvName(runtime.providerId()))).trim();
        if (StringUtils.hasText(explicit)) {
            return toStartupCompatibleModel(runtime, explicit);
        }
        return toStartupCompatibleModel(
                runtime,
                aiProviderStateService.resolvePreferredModel(runtime.providerId(), configuredDefault)
        );
    }

    private String modelEnvName(String providerId) {
        return switch (normalizeProviderId(providerId)) {
            case "primary" -> "SPRINGCLAW_PRIMARY_MODEL";
            case "qwen" -> "SPRINGCLAW_QWEN_MODEL";
            case "coding-plan" -> "SPRINGCLAW_CODING_PLAN_MODEL";
            case "deepseek" -> "SPRINGCLAW_DEEPSEEK_MODEL";
            default -> "";
        };
    }

    private String resolveInitialProviderId(String configuredProvider) {
        String normalized = normalizeProviderId(configuredProvider);
        AiProviderRuntime configured = providers.get(normalized);
        if (configured != null && configured.available()) {
            return normalized;
        }
        return providers.values().stream()
                .filter(AiProviderRuntime::available)
                .map(AiProviderRuntime::providerId)
                .findFirst()
                .orElse(normalized);
    }

    private ProviderView toView(AiProviderRuntime runtime, boolean active) {
        sanitizeRuntimeModel(runtime);
        return new ProviderView(
                runtime.providerId(),
                runtime.currentModel(),
                runtime.defaultModel(),
                runtime.baseUrl(),
                runtime.enabled(),
                runtime.available(),
                active,
                runtime.availableReason(),
                chatCompatibleModels(runtime)
        );
    }

    static String sanitizeBaseUrl(String baseUrl) {
        return AiProviderRuntimeFactory.sanitizeBaseUrl(baseUrl);
    }

    static RequestPaths resolveApiPaths(String baseUrl) {
        AiProviderPaths paths = AiProviderRuntimeFactory.resolveApiPaths(baseUrl);
        return new RequestPaths(paths.completionsPath(), paths.embeddingsPath());
    }

    private String chooseProvider(List<AiProviderRuntime> matches, String preferredProviderId, String currentProviderId) {
        if (matches == null || matches.isEmpty()) {
            return "";
        }
        if (StringUtils.hasText(preferredProviderId)) {
            for (AiProviderRuntime runtime : matches) {
                if (preferredProviderId.equals(runtime.providerId())) {
                    return runtime.providerId();
                }
            }
        }
        if (StringUtils.hasText(currentProviderId)) {
            for (AiProviderRuntime runtime : matches) {
                if (currentProviderId.equals(runtime.providerId())) {
                    return runtime.providerId();
                }
            }
        }
        if (matches.size() == 1) {
            return matches.get(0).providerId();
        }
        return "";
    }

    private String normalizeProviderId(String providerId) {
        if (!StringUtils.hasText(providerId)) {
            return "primary";
        }
        return providerId.trim().toLowerCase(Locale.ROOT);
    }

    private void sanitizeRuntimeModel(AiProviderRuntime runtime) {
        if (runtime == null || !isUnsupportedChatModel(runtime.providerId(), runtime.currentModel())) {
            return;
        }
        String compatible = firstCompatibleModel(runtime);
        if (StringUtils.hasText(compatible)) {
            runtime.setActiveModel(compatible);
        }
    }

    private StartupModelChoice toStartupCompatibleModel(AiProviderRuntime runtime, String preferredModel) {
        String resolved = runtime.resolveModel(preferredModel);
        String candidate = StringUtils.hasText(resolved) ? resolved : preferredModel;
        if (isUnsupportedChatModel(runtime.providerId(), candidate)) {
            String compatible = firstCompatibleModel(runtime);
            log.warn("模型 {}:{} 当前不适合 Spring AI 主聊天链路，启动时回退到 {}。",
                    runtime.providerId(), candidate, compatible);
            return new StartupModelChoice(StringUtils.hasText(compatible) ? compatible : runtime.defaultModel(), true);
        }
        if (StringUtils.hasText(resolved)) {
            return new StartupModelChoice(resolved, false);
        }
        String compatible = firstCompatibleModel(runtime);
        if (StringUtils.hasText(preferredModel) && StringUtils.hasText(compatible)) {
            log.warn("模型 {}:{} 未在当前可用模型列表中，启动时回退到 {}。",
                    runtime.providerId(), preferredModel, compatible);
            return new StartupModelChoice(compatible, true);
        }
        return new StartupModelChoice(runtime.defaultModel(), false);
    }

    private void ensureChatCompatibleModel(String providerId, String model) {
        if (!isUnsupportedChatModel(providerId, model)) {
            return;
        }
        throw new BusinessException(40043, unsupportedChatModelMessage(providerId, model));
    }

    private boolean isSupportedResolvedModel(AiProviderRuntime runtime, String modelHint) {
        String resolved = runtime.resolveModel(modelHint);
        return StringUtils.hasText(resolved) && !isUnsupportedChatModel(runtime.providerId(), resolved);
    }

    private List<String> chatCompatibleModels(AiProviderRuntime runtime) {
        List<String> models = new ArrayList<>();
        for (String model : runtime.availableModels()) {
            if (StringUtils.hasText(model) && !isUnsupportedChatModel(runtime.providerId(), model)) {
                models.add(model);
            }
        }
        return List.copyOf(models);
    }

    private String firstCompatibleModel(AiProviderRuntime runtime) {
        if (runtime == null) {
            return "";
        }
        for (String model : runtime.availableModels()) {
            if (StringUtils.hasText(model) && !isUnsupportedChatModel(runtime.providerId(), model)) {
                return model;
            }
        }
        return "";
    }

    private String resolveModelCandidate(String providerId, String modelHint) {
        AiProviderRuntime runtime = providers.get(normalizeProviderId(providerId));
        if (runtime == null) {
            return safe(modelHint);
        }
        String resolved = runtime.resolveModel(modelHint);
        return StringUtils.hasText(resolved) ? resolved : safe(modelHint);
    }

    private boolean isUnsupportedChatModel(String providerId, String model) {
        if (!"deepseek".equals(normalizeProviderId(providerId)) || !StringUtils.hasText(model)) {
            return false;
        }
        String normalized = model.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace(".", "")
                .replace(" ", "");
        return normalized.contains("reasoner");
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }

    public record ActiveChatClient(String providerId,
                                   String model,
                                   String baseUrl,
                                   ChatClient chatClient,
                                   boolean available,
                                   String unavailableReason) {

        public String displayName() {
            return providerId + ":" + model;
        }
    }

    public record ProviderView(String providerId,
                               String model,
                               String defaultModel,
                               String baseUrl,
                               boolean enabled,
                               boolean available,
                               boolean active,
                               String unavailableReason,
                               List<String> availableModels) {
    }

    record RequestPaths(String completionsPath, String embeddingsPath) {
    }

    private record StartupModelChoice(String model, boolean repaired) {
    }
}
