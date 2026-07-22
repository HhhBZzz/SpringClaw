package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.runtime.bridge.AcceptedRunContext;
import com.springclaw.runtime.bridge.AcceptedRunContextResolver;
import com.springclaw.runtime.bridge.CanonicalContextSnapshotResolver;
import com.springclaw.runtime.bridge.CanonicalRunContextException;
import com.springclaw.runtime.bridge.LegacyContextView;
import com.springclaw.runtime.bridge.LegacyContextViewAdapter;
import com.springclaw.runtime.bridge.RunStateContextSnapshotRequestFactory;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.ContextSnapshotFactory;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentDecisionRequest;
import com.springclaw.service.agent.AgentDecisionService;
import com.springclaw.service.agent.AgentParadigm;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextAssembler;
import com.springclaw.service.context.ContextInjection;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.session.AgentSessionService;
import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
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
    private final ObjectProvider<AcceptedRunContextResolver> acceptedRunContextResolverProvider;
    private final ObjectProvider<RunStateContextSnapshotRequestFactory>
            runStateContextSnapshotRequestFactoryProvider;
    private final ObjectProvider<CanonicalContextSnapshotResolver>
            canonicalContextSnapshotResolverProvider;
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
                              ObjectProvider<AcceptedRunContextResolver> acceptedRunContextResolverProvider,
                              ObjectProvider<RunStateContextSnapshotRequestFactory> runStateContextSnapshotRequestFactoryProvider,
                              ObjectProvider<CanonicalContextSnapshotResolver> canonicalContextSnapshotResolverProvider,
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
        this.acceptedRunContextResolverProvider = acceptedRunContextResolverProvider;
        this.runStateContextSnapshotRequestFactoryProvider =
                runStateContextSnapshotRequestFactoryProvider;
        this.canonicalContextSnapshotResolverProvider = canonicalContextSnapshotResolverProvider;
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
        return contextSnapshotFactoryEnabled
                ? buildCanonical(request, persistSession, acceptedRunId)
                : buildLegacy(request, persistSession, acceptedRunId);
    }

    private ChatContext buildCanonical(
            ChatRequest request,
            boolean persistSession,
            String acceptedRunId
    ) {
        AcceptedRunContext accepted = requireBean(
                acceptedRunContextResolverProvider,
                "AcceptedRunContextResolver"
        ).resolve(acceptedRunId, request);
        AgentSession session = persistSession
                ? agentSessionService.getOrCreate(
                        accepted.sessionKey(),
                        accepted.channel(),
                        accepted.userId()
                )
                : buildEphemeralSession(
                        accepted.sessionKey(),
                        accepted.channel(),
                        accepted.userId()
                );
        ContextSnapshotFactory snapshotFactory = requireBean(
                contextSnapshotFactoryProvider,
                "ContextSnapshotFactory"
        );
        RunStateContextSnapshotRequestFactory requestFactory = requireBean(
                runStateContextSnapshotRequestFactoryProvider,
                "RunStateContextSnapshotRequestFactory"
        );
        CanonicalContextSnapshotResolver snapshotResolver = requireBean(
                canonicalContextSnapshotResolverProvider,
                "CanonicalContextSnapshotResolver"
        );
        LegacyContextViewAdapter viewAdapter = requireBean(
                legacyContextViewAdapterProvider,
                "LegacyContextViewAdapter"
        );
        AiProviderService.ActiveChatClient activeClient = aiProviderService.activeClient();
        AtomicReference<CanonicalPlanning> candidatePlanning = new AtomicReference<>();

        ContextSnapshot snapshot = snapshotResolver.resolve(accepted, () -> {
            CanonicalPlanning planning = createCanonicalPlanning(
                    accepted,
                    accepted.originalMessage(),
                    true,
                    request.paradigm()
            );
            candidatePlanning.set(planning);
            return snapshotFactory.create(requestFactory.create(
                    accepted.runState(),
                    planning.routingDecision().effectiveQuestion(),
                    planning.systemPrompt(),
                    planning.decision().selectedCapabilities(),
                    providerSnapshot(activeClient)
            ));
        });
        requireMatchingProvider(snapshot, activeClient);
        CanonicalPlanning planning = candidatePlanning.get();
        if (planning == null) {
            planning = createCanonicalPlanning(
                    accepted,
                    snapshot.effectiveMessage(),
                    false,
                    request.paradigm()
            );
        }
        LegacyContextView view = viewAdapter.adapt(snapshot);
        return new ChatContext(
                session,
                accepted.channel(),
                accepted.userId(),
                accepted.roleCode(),
                accepted.originalMessage(),
                snapshot.effectiveMessage(),
                accepted.runId(),
                snapshot.systemPrompt(),
                view.assembled(),
                activeClient,
                planning.routingDecision().executionMode(),
                planning.routingDecision().reason(),
                planning.routingDecision().responseMode(),
                planning.decision().intent(),
                planning.decision(),
                view.injection(),
                snapshot,
                request.paradigm()
        );
    }

    private ChatContext buildLegacy(
            ChatRequest request,
            boolean persistSession,
            String acceptedRunId
    ) {
        String channel = StringUtils.hasText(request.channel()) ? request.channel() : "api";
        AgentSession session = persistSession
                ? agentSessionService.getOrCreate(request.sessionKey(), channel, request.userId())
                : buildEphemeralSession(request.sessionKey(), channel, request.userId());
        String roleCode = authService.resolveRoleByUserId(request.userId());
        CanonicalPlanning planning = createLegacyPlanning(
                session.getSessionKey(),
                channel,
                request.userId(),
                roleCode,
                acceptedRunId,
                request.message(),
                request.responseMode(),
                request.paradigm()
        );
        AssembledContext assembled = contextAssembler.assemble(
                session.getSessionKey(),
                channel,
                request.userId(),
                planning.routingDecision().effectiveQuestion()
        );
        ContextInjection injection = new ContextInjection(
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
        return new ChatContext(
                session,
                channel,
                request.userId(),
                roleCode,
                request.message(),
                planning.routingDecision().effectiveQuestion(),
                acceptedRunId,
                planning.systemPrompt(),
                assembled,
                aiProviderService.activeClient(),
                planning.routingDecision().executionMode(),
                planning.routingDecision().reason(),
                planning.routingDecision().responseMode(),
                planning.decision().intent(),
                planning.decision(),
                injection,
                null,
                request.paradigm()
        );
    }

    private CanonicalPlanning createCanonicalPlanning(
            AcceptedRunContext accepted,
            String routingQuestion,
            boolean buildPrompt,
            AgentParadigm paradigm
    ) {
        return createPlanning(
                accepted.sessionKey(),
                accepted.channel(),
                accepted.userId(),
                accepted.roleCode(),
                accepted.runId(),
                routingQuestion,
                accepted.responseMode(),
                buildPrompt,
                paradigm
        );
    }

    private CanonicalPlanning createLegacyPlanning(
            String sessionKey,
            String channel,
            String userId,
            String roleCode,
            String requestId,
            String message,
            String responseMode,
            AgentParadigm paradigm
    ) {
        return createPlanning(
                sessionKey,
                channel,
                userId,
                roleCode,
                requestId,
                resolveRoutingQuestion(
                        sessionKey,
                        channel,
                        userId,
                        requestId,
                        message,
                        responseMode
                ),
                responseMode,
                true,
                paradigm
        );
    }

    private CanonicalPlanning createPlanning(
            String sessionKey,
            String channel,
            String userId,
            String roleCode,
            String requestId,
            String routingQuestion,
            String responseMode,
            boolean buildPrompt,
            AgentParadigm paradigm
    ) {
        var allowedToolPacks = skillService.resolveAllowedToolPacks(channel, userId);
        AgentDecision decision = agentDecisionService.decide(new AgentDecisionRequest(
                sessionKey,
                channel,
                userId,
                roleCode,
                requestId,
                routingQuestion,
                responseMode,
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
                responseMode,
                paradigm
        );
        if (routingDecision == null) {
            routingDecision = new ChatRoutingPolicyService.RoutingDecision(
                    routingQuestion,
                    effectiveDefaultMode,
                    false,
                    false,
                    "路由策略未返回结果，回退到当前默认链路。"
            );
        }
        String systemPrompt = "";
        if (buildPrompt) {
            List<SkillDefinition> matchedSkills = skillRegistryService.matchAgentVisibleDefinitions(
                    routingDecision.effectiveQuestion(),
                    allowedToolPacks,
                    2
            );
            systemPrompt = soulPromptService.buildSystemPrompt(channel, userId, matchedSkills);
        }
        return new CanonicalPlanning(decision, routingDecision, systemPrompt);
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

    private static void requireMatchingProvider(
            ContextSnapshot snapshot,
            AiProviderService.ActiveChatClient activeClient
    ) {
        Map<String, String> frozen = snapshot.providerSnapshot();
        requireProviderField(
                "providerId",
                frozen.get("providerId"),
                activeClient == null ? null : activeClient.providerId()
        );
        requireProviderField(
                "model",
                frozen.get("model"),
                activeClient == null ? null : activeClient.model()
        );
    }

    private static void requireProviderField(
            String field,
            String frozenValue,
            String activeValue
    ) {
        if (StringUtils.hasText(frozenValue)
                && !frozenValue.trim().equals(
                activeValue == null ? "" : activeValue.trim()
        )) {
            throw new CanonicalRunContextException(
                    CanonicalRunContextException.Code.CANONICAL_PROVIDER_MISMATCH,
                    "canonical provider mismatch: " + field
            );
        }
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

    private static <T> T requireBean(ObjectProvider<T> provider, String beanName) {
        T bean = provider.getIfAvailable();
        if (bean == null) {
            throw new IllegalStateException(
                    "canonical ContextSnapshotFactory path is enabled but required bean is missing: "
                            + beanName
            );
        }
        return bean;
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

    private record CanonicalPlanning(
            AgentDecision decision,
            ChatRoutingPolicyService.RoutingDecision routingDecision,
            String systemPrompt
    ) {
    }
}
