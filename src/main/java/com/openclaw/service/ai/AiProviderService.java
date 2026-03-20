package com.openclaw.service.ai;

import com.openclaw.common.exception.BusinessException;
import com.openclaw.config.ai.OpenClawAiProperties;
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
    public AiProviderService(OpenClawAiProperties aiProperties,
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

    AiProviderService(OpenClawAiProperties aiProperties,
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

        for (AiProviderRuntime runtime : providers.values()) {
            runtime.restoreActiveModel(resolveStartupModel(runtime));
        }

        this.activeProviderId = new AtomicReference<>(resolveInitialProviderId(
                resolveStartupProvider(aiProperties.getActiveProvider())
        ));
        AiProviderRuntime active = this.providers.get(this.activeProviderId.get());
        log.info("当前活动模型提供方: provider={}, model={}, available={}",
                active.providerId(), active.currentModel(), active.available());
    }

    private void registerProvider(String providerId, OpenClawAiProperties.Provider provider) {
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
            throw new BusinessException(40042, "未识别模型或模型未启用: " + safe(modelHint));
        }
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
        return StringUtils.hasText(resolved) ? resolved : "";
    }

    public synchronized ActiveChatClient activateModel(String providerId, String modelHint, String source) {
        String normalizedProvider = normalizeProviderId(StringUtils.hasText(providerId) ? providerId : activeProviderId.get());
        AiProviderRuntime runtime = requireAvailableProvider(normalizedProvider);
        String resolvedModel = runtime.resolveModel(modelHint);
        if (!StringUtils.hasText(resolvedModel)) {
            throw new BusinessException(40042, "未识别模型或模型未启用: " + safe(modelHint));
        }
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
        return runtime.failoverModels(failedModel);
    }

    public String findProviderByModelHint(String modelHint, String preferredProviderId) {
        String preferred = normalizeProviderId(preferredProviderId);
        String current = activeProviderId.get();

        List<AiProviderRuntime> exactMatches = providers.values().stream()
                .filter(AiProviderRuntime::available)
                .filter(runtime -> runtime.matchesModelExactly(modelHint))
                .toList();
        String exact = chooseProvider(exactMatches, preferred, current);
        if (StringUtils.hasText(exact)) {
            return exact;
        }

        List<AiProviderRuntime> fuzzyMatches = providers.values().stream()
                .filter(AiProviderRuntime::available)
                .filter(runtime -> runtime.matchesModelFuzzy(modelHint))
                .toList();
        return chooseProvider(fuzzyMatches, preferred, current);
    }

    public boolean hasKnownModelHint(String modelHint) {
        return StringUtils.hasText(findProviderByModelHint(modelHint, null));
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
        String explicit = safe(envLookup.apply("OPENCLAW_AI_ACTIVE_PROVIDER")).trim();
        if (StringUtils.hasText(explicit)) {
            return normalizeProviderId(explicit);
        }
        return aiProviderStateService.resolvePreferredProvider(configured);
    }

    private String resolveStartupModel(AiProviderRuntime runtime) {
        String configuredDefault = runtime.defaultModel();
        String explicit = safe(envLookup.apply(modelEnvName(runtime.providerId()))).trim();
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        return aiProviderStateService.resolvePreferredModel(runtime.providerId(), configuredDefault);
    }

    private String modelEnvName(String providerId) {
        return switch (normalizeProviderId(providerId)) {
            case "primary" -> "OPENCLAW_PRIMARY_MODEL";
            case "qwen" -> "OPENCLAW_QWEN_MODEL";
            case "coding-plan" -> "OPENCLAW_CODING_PLAN_MODEL";
            case "deepseek" -> "OPENCLAW_DEEPSEEK_MODEL";
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
        return new ProviderView(
                runtime.providerId(),
                runtime.currentModel(),
                runtime.defaultModel(),
                runtime.baseUrl(),
                runtime.enabled(),
                runtime.available(),
                active,
                runtime.availableReason(),
                runtime.availableModels()
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
}
