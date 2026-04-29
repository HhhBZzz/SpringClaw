package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextAssembler;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.session.AgentSessionService;
import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

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
    private final String configuredAgentMode;
    private final boolean routingAutoUpgradeEnabled;

    public ChatContextFactory(AiProviderService aiProviderService,
                              SoulPromptService soulPromptService,
                              AgentSessionService agentSessionService,
                              AuthService authService,
                              SkillService skillService,
                              SkillRegistryService skillRegistryService,
                              ContextAssembler contextAssembler,
                              ChatRoutingStateService chatRoutingStateService,
                              ChatRoutingPolicyService chatRoutingPolicyService,
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
        this.configuredAgentMode = configuredAgentMode;
        this.routingAutoUpgradeEnabled = routingAutoUpgradeEnabled;
    }

    public ChatContext build(ChatRequest request, boolean persistSession) {
        String channel = StringUtils.hasText(request.channel()) ? request.channel() : "api";
        AgentSession session = persistSession
                ? agentSessionService.getOrCreate(request.sessionKey(), channel, request.userId())
                : buildEphemeralSession(request.sessionKey(), channel, request.userId());
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String roleCode = authService.resolveRoleByUserId(request.userId());
        var allowedToolPacks = skillService.resolveAllowedToolPacks(channel, request.userId());
        String effectiveDefaultMode = chatRoutingStateService.resolveDefaultMode(configuredAgentMode);
        boolean effectiveAutoUpgrade = chatRoutingStateService.resolveAutoUpgrade(routingAutoUpgradeEnabled);
        ChatRoutingPolicyService.RoutingDecision routingDecision = chatRoutingPolicyService.decide(
                request.message(),
                roleCode,
                effectiveDefaultMode,
                effectiveAutoUpgrade,
                allowedToolPacks
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
        AssembledContext assembled = contextAssembler.assemble(
                session.getSessionKey(),
                channel,
                request.userId(),
                routingDecision.effectiveQuestion()
        );
        AiProviderService.ActiveChatClient activeClient = aiProviderService.activeClient();
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
                routingDecision.reason()
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
}
