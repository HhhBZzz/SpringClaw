package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.runtime.bridge.CanonicalContextReadyProjector;
import com.springclaw.runtime.bridge.LegacyContextView;
import com.springclaw.runtime.bridge.LegacyContextViewAdapter;
import com.springclaw.runtime.bridge.RunStateContextSnapshotRequestFactory;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.ContextSnapshotFactory;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.lifecycle.RunStateRepository;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentDecisionRequest;
import com.springclaw.service.agent.AgentDecisionService;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextAssembler;
import com.springclaw.service.context.ContextInjection;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.session.AgentSessionService;
import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 聊天上下文工厂，负责构建 ChatContext。
 */
@Component
public class ChatContextFactory {

    private final AiProviderService aiProviderService;
    private final SoulPromptService soulPromptService;
    private final AgentSessionService agentSessionService;
    private final AuthService authService;
    private final SkillService skillService;
    private final SkillRegistryService skillRegistryService;
    private final ContextAssembler contextAssembler;
    private final ChatRoutingStateService chatRoutingStateService;
    private final ChatRoutingPolicyService chatRoutingPolicyService;
    private final AgentDecisionService agentDecisionService;
    private final ObjectProvider<ContextSnapshotFactory> contextSnapshotFactoryProvider;
    private final ObjectProvider<LegacyContextViewAdapter> legacyContextViewAdapterProvider;
    private final ObjectProvider<RunStateRepository> runStateRepositoryProvider;
    private final ObjectProvider<RunStateContextSnapshotRequestFactory> runStateContextSnapshotRequestFactoryProvider;
    private final ObjectProvider<CanonicalContextReadyProjector> canonicalContextReadyProjectorProvider;
    private final boolean contextSnapshotFactoryEnabled;
    private final String configuredAgentMode;
    private final boolean routingAutoUpgradeEnabled;

    @Autowired
    public ChatContextFactory(AiProviderService aiProviderService,
                              SoulPromptService soulPromptService,
                              AgentSessionService agentSessionService,
                              AuthService authService,
                              SkillService skillService,
                              SkillRegistryService skillRegistryService,
                              ContextAssembler contextAssembler,
                              ChatRoutingStateService chatRoutingStateService,
                              ChatRoutingPolicyService chatRoutingPolicyService,
                              AgentDecisionService agentDecisionService,
                              ObjectProvider<ContextSnapshotFactory> contextSnapshotFactoryProvider,
                              ObjectProvider<LegacyContextViewAdapter> legacyContextViewAdapterProvider,
                              ObjectProvider<RunStateRepository> runStateRepositoryProvider,
                              ObjectProvider<RunStateContextSnapshotRequestFactory> runStateContextSnapshotRequestFactoryProvider,
                              ObjectProvider<CanonicalContextReadyProjector> canonicalContextReadyProjectorProvider,
                              @org.springframework.beans.factory.annotation.Value("${springclaw.context.snapshot.factory-enabled:true}") boolean contextSnapshotFactoryEnabled,
                              @org.springframework.beans.factory.annotation.Value("${springclaw.chat.agent-mode:simplified}") String configuredAgentMode,
                              @org.springframework.beans.factory.annotation.Value("${springclaw.chat.routing.auto-upgrade-enabled:true}") boolean routingAutoUpgradeEnabled) {
        this.aiProviderService = aiProviderService;
        this.soulPromptService = soulPromptService;
        this.agentSessionService = agentSessionService;
        this.authService = authService;
        this.skillService = skillService;
        this.skillRegistryService = skillRegistryService;
        this.contextAssembler = contextAssembler;
        this.chatRoutingStateService = chatRoutingStateService;
        this.chatRoutingPolicyService = chatRoutingPolicyService;
        this.agentDecisionService = agentDecisionService;
        this.contextSnapshotFactoryProvider = contextSnapshotFactoryProvider;
        this.legacyContextViewAdapterProvider = legacyContextViewAdapterProvider;
        this.runStateRepositoryProvider = runStateRepositoryProvider;
        this.runStateContextSnapshotRequestFactoryProvider = runStateContextSnapshotRequestFactoryProvider;
        this.canonicalContextReadyProjectorProvider = canonicalContextReadyProjectorProvider;
        this.contextSnapshotFactoryEnabled = contextSnapshotFactoryEnabled;
        this.configuredAgentMode = configuredAgentMode;
        this.routingAutoUpgradeEnabled = routingAutoUpgradeEnabled;
    }

    public ChatContextFactory(AiProviderService aiProviderService,
                              SoulPromptService soulPromptService,
                              AgentSessionService agentSessionService,
                              AuthService authService,
                              SkillService skillService,
                              SkillRegistryService skillRegistryService,
                              ContextAssembler contextAssembler,
                              ChatRoutingStateService chatRoutingStateService,
                              ChatRoutingPolicyService chatRoutingPolicyService,
                              AgentDecisionService agentDecisionService,
                              @org.springframework.beans.factory.annotation.Value("${springclaw.chat.agent-mode:simplified}") String configuredAgentMode,
                              @org.springframework.beans.factory.annotation.Value("${springclaw.chat.routing.auto-upgrade-enabled:true}") boolean routingAutoUpgradeEnabled) {
        this(
                aiProviderService,
                soulPromptService,
                agentSessionService,
                authService,
                skillService,
                skillRegistryService,
                contextAssembler,
                chatRoutingStateService,
                chatRoutingPolicyService,
                agentDecisionService,
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                emptyProvider(),
                false,
                configuredAgentMode,
                routingAutoUpgradeEnabled
        );
    }

    public ChatContext build(ChatRequest request,
                             boolean persistSession,
                             String acceptedRunId) {
        if (!StringUtils.hasText(acceptedRunId)) {
            throw new IllegalArgumentException("acceptedRunId must not be blank");
        }
        String channel = StringUtils.hasText(request.channel()) ? request.channel() : "api";
        AgentSession session = persistSession
                ? agentSessionService.getOrCreate(request.sessionKey(), channel, request.userId())
                : buildEphemeralSession(request.sessionKey(), channel, request.userId());
        String requestId = acceptedRunId;
        String roleCode = authService.resolveRoleByUserId(request.userId());
        var allowedToolPacks = skillService.resolveAllowedToolPacks(channel, request.userId());
        String routingQuestion = resolveRoutingQuestion(session.getSessionKey(), channel, request.userId(), requestId, request.message(), request.responseMode());
        AgentDecision decision = agentDecisionService.decide(new AgentDecisionRequest(
                session.getSessionKey(),
                channel,
                request.userId(),
                roleCode,
                requestId,
                routingQuestion,
                request.responseMode(),
                allowedToolPacks
        ));
        String effectiveDefaultMode = chatRoutingStateService.resolveDefaultMode(configuredAgentMode);
        boolean effectiveAutoUpgrade = chatRoutingStateService.resolveAutoUpgrade(routingAutoUpgradeEnabled);
        ChatRoutingPolicyService.RoutingDecision routingDecision = chatRoutingPolicyService.decide(
                routingQuestion,
                roleCode,
                effectiveDefaultMode,
                effectiveAutoUpgrade,
                allowedToolPacks,
                request.responseMode()
        );
        if (routingDecision == null) {
            routingDecision = new ChatRoutingPolicyService.RoutingDecision(
                    request.message(),
                    effectiveDefaultMode,
                    false,
                    false,
                    "路由策略未返回结果，回退到当前默认链路。"
            );
        }
        List<SkillDefinition> matchedSkills = skillRegistryService.matchAgentVisibleDefinitions(
                routingDecision.effectiveQuestion(),
                allowedToolPacks,
                2
        );
        String systemPrompt = soulPromptService.buildSystemPrompt(channel, request.userId(), matchedSkills);
        AiProviderService.ActiveChatClient activeClient = aiProviderService.activeClient();
        AssembledContext assembled;
        ContextInjection injection;
        ContextSnapshot contextSnapshot = null;
        if (contextSnapshotFactoryEnabled) {
            ContextSnapshotFactory snapshotFactory =
                    contextSnapshotFactoryProvider.getIfAvailable();
            LegacyContextViewAdapter viewAdapter =
                    legacyContextViewAdapterProvider.getIfAvailable();
            RunStateRepository runStateRepository =
                    runStateRepositoryProvider.getIfAvailable();
            RunStateContextSnapshotRequestFactory requestFactory =
                    runStateContextSnapshotRequestFactoryProvider.getIfAvailable();
            CanonicalContextReadyProjector contextReadyProjector =
                    canonicalContextReadyProjectorProvider.getIfAvailable();
            if (snapshotFactory == null
                    || viewAdapter == null
                    || runStateRepository == null
                    || requestFactory == null
                    || contextReadyProjector == null) {
                throw new IllegalStateException(
                        "canonical ContextSnapshotFactory path is enabled but required beans are missing"
                );
            }
            RunState runState = runStateRepository.requireByRunId(requestId);
            ContextSnapshot snapshot = snapshotFactory.create(requestFactory.create(
                    runState,
                    routingDecision.effectiveQuestion(),
                    systemPrompt,
                    decision.selectedCapabilities(),
                    providerSnapshot(activeClient)
            ));
            contextSnapshot = snapshot;
            contextReadyProjector.project(
                    requestId,
                    snapshot,
                    snapshot.capturedAt()
            );
            LegacyContextView view = viewAdapter.adapt(snapshot);
            assembled = view.assembled();
            injection = view.injection();
        } else {
            assembled = contextAssembler.assemble(
                    session.getSessionKey(),
                    channel,
                    request.userId(),
                    routingDecision.effectiveQuestion()
            );
            injection = new ContextInjection(
                    assembled == null ? "" : assembled.observePrompt(),
                    "",
                    "",
                    Map.of(
                            "contextSummary",
                            assembled == null
                                    ? AssembledContext.ContextSourceSummary.empty()
                                    : assembled.sourceSummary()
                    )
            );
        }
        return new ChatContext(
                session,
                channel,
                request.userId(),
                roleCode,
                request.message(),
                routingDecision.effectiveQuestion(),
                requestId,
                systemPrompt,
                assembled,
                activeClient,
                routingDecision.executionMode(),
                routingDecision.reason(),
                routingDecision.responseMode(),
                decision.intent(),
                decision,
                injection,
                contextSnapshot
        );
    }

    private AgentSession buildEphemeralSession(String sessionKey, String channel, String userId) {
        AgentSession session = new AgentSession();
        session.setId(0L);
        session.setSessionKey(sessionKey);
        session.setChannel(channel);
        session.setUserId(userId);
        session.setStatus("ACTIVE");
        return session;
    }

    private String resolveRoutingQuestion(String sessionKey,
                                          String channel,
                                          String userId,
                                          String requestId,
                                          String message,
                                          String responseMode) {
        return message == null ? "" : message.trim();
    }

    private static Map<String, String> providerSnapshot(
            AiProviderService.ActiveChatClient activeClient
    ) {
        if (activeClient == null) {
            return Map.of();
        }
        return Map.of(
                "providerId", safe(activeClient.providerId()),
                "model", safe(activeClient.model()),
                "baseUrl", safe(activeClient.baseUrl()),
                "available", Boolean.toString(activeClient.available())
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                throw new IllegalStateException("No bean available");
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }

            @Override
            public T getObject() {
                throw new IllegalStateException("No bean available");
            }

            @Override
            public Iterator<T> iterator() {
                return List.<T>of().iterator();
            }

            @Override
            public Stream<T> stream() {
                return Stream.empty();
            }
        };
    }
}
