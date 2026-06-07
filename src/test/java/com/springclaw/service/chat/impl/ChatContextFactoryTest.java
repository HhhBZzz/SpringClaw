package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentDecisionRequest;
import com.springclaw.service.agent.AgentDecisionService;
import com.springclaw.service.agent.pipeline.ContextResolutionPolicy;
import com.springclaw.service.agent.pipeline.ContextResolveStage;
import com.springclaw.service.agent.pipeline.ControlBypassStage;
import com.springclaw.service.agent.pipeline.DefaultContextResolutionPolicy;
import com.springclaw.service.agent.pipeline.DefaultTurnPipeline;
import com.springclaw.service.agent.pipeline.DeterministicUtteranceClassifier;
import com.springclaw.service.agent.pipeline.InputNormalizeStage;
import com.springclaw.service.agent.pipeline.PassThroughContextualResolver;
import com.springclaw.service.agent.pipeline.UtteranceClassifyStage;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextAssembler;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.session.AgentSessionService;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatContextFactoryTest {

    @Test
    void shouldKeepFollowUpQuestionUnchangedWhenTurnPipelineIsNotProvided() {
        AiProviderService aiProviderService = mock(AiProviderService.class);
        SoulPromptService soulPromptService = mock(SoulPromptService.class);
        AgentSessionService agentSessionService = mock(AgentSessionService.class);
        AuthService authService = mock(AuthService.class);
        SkillService skillService = mock(SkillService.class);
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        ContextAssembler contextAssembler = mock(ContextAssembler.class);
        ChatRoutingStateService chatRoutingStateService = mock(ChatRoutingStateService.class);
        ChatRoutingPolicyService chatRoutingPolicyService = mock(ChatRoutingPolicyService.class);
        AgentDecisionService agentDecisionService = mock(AgentDecisionService.class);
        AgentSession session = new AgentSession();
        session.setId(1L);
        session.setSessionKey("s1");
        Set<String> allowedToolPacks = Set.of("web");
        AgentDecision decision = new AgentDecision(
                "general",
                "basic_model",
                List.of(),
                "read",
                false,
                "短追问保持原文，不在路由前继承业务槽位。"
        );
        AssembledContext assembled = new AssembledContext(
                "s1",
                "api",
                "u1",
                "北京呢",
                "events",
                "memory",
                "observe"
        );
        when(agentSessionService.getOrCreate("s1", "api", "u1")).thenReturn(session);
        when(authService.resolveRoleByUserId("u1")).thenReturn("USER");
        when(skillService.resolveAllowedToolPacks("api", "u1")).thenReturn(allowedToolPacks);
        when(agentDecisionService.decide(org.mockito.ArgumentMatchers.any(AgentDecisionRequest.class))).thenReturn(decision);
        when(chatRoutingStateService.resolveDefaultMode("simplified")).thenReturn("simplified");
        when(chatRoutingStateService.resolveAutoUpgrade(true)).thenReturn(true);
        when(chatRoutingPolicyService.decide(
                eq("北京呢"),
                eq("USER"),
                eq("simplified"),
                eq(true),
                eq(allowedToolPacks),
                eq("agent")
        )).thenReturn(new ChatRoutingPolicyService.RoutingDecision(
                "北京呢",
                "simplified",
                false,
                false,
                "短追问保持原文路由。",
                "agent",
                "general"
        ));
        when(skillRegistryService.matchAgentVisibleDefinitions("北京呢", allowedToolPacks, 2)).thenReturn(List.of());
        when(soulPromptService.buildSystemPrompt("api", "u1", List.of())).thenReturn("system");
        when(contextAssembler.assemble("s1", "api", "u1", "北京呢")).thenReturn(assembled);
        when(aiProviderService.activeClient()).thenReturn(new AiProviderService.ActiveChatClient(
                "coding-plan",
                "o3",
                "http://localhost",
                mock(ChatClient.class),
                true,
                ""
        ));
        ChatContextFactory factory = new ChatContextFactory(
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
                "simplified",
                true
        );

        ChatContext context = factory.build(new ChatRequest("s1", "u1", "北京呢", "api", "agent"), true);

        ArgumentCaptor<AgentDecisionRequest> decisionRequestCaptor = ArgumentCaptor.forClass(AgentDecisionRequest.class);
        verify(agentDecisionService).decide(decisionRequestCaptor.capture());
        assertThat(decisionRequestCaptor.getValue().question()).isEqualTo("北京呢");
        verify(contextAssembler).assemble("s1", "api", "u1", "北京呢");
        assertThat(context.userMessage()).isEqualTo("北京呢");
        assertThat(context.effectiveUserMessage()).isEqualTo("北京呢");
        assertThat(context.decision()).isEqualTo(decision);
    }

    @Test
    void shouldBypassFollowUpResolverForControlUtteranceThroughTurnPipeline() {
        AiProviderService aiProviderService = mock(AiProviderService.class);
        SoulPromptService soulPromptService = mock(SoulPromptService.class);
        AgentSessionService agentSessionService = mock(AgentSessionService.class);
        AuthService authService = mock(AuthService.class);
        SkillService skillService = mock(SkillService.class);
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        ContextAssembler contextAssembler = mock(ContextAssembler.class);
        ChatRoutingStateService chatRoutingStateService = mock(ChatRoutingStateService.class);
        ChatRoutingPolicyService chatRoutingPolicyService = mock(ChatRoutingPolicyService.class);
        AgentDecisionService agentDecisionService = mock(AgentDecisionService.class);
        ContextResolutionPolicy policy = new DefaultContextResolutionPolicy(0.72);
        DefaultTurnPipeline turnPipeline = new DefaultTurnPipeline(List.of(
                new InputNormalizeStage(),
                new UtteranceClassifyStage(new DeterministicUtteranceClassifier()),
                new ControlBypassStage(policy),
                new ContextResolveStage(new PassThroughContextualResolver(), policy)
        ));
        AgentSession session = new AgentSession();
        session.setId(1L);
        session.setSessionKey("s1");
        Set<String> allowedToolPacks = Set.of("system");
        AgentDecision decision = new AgentDecision(
                "control",
                "basic",
                List.of(),
                "read",
                false,
                "控制类输入不继承上一轮业务上下文。"
        );
        AssembledContext assembled = new AssembledContext(
                "s1",
                "api",
                "u1",
                "今天日期是什么",
                "events",
                "memory",
                "observe"
        );
        when(agentSessionService.getOrCreate("s1", "api", "u1")).thenReturn(session);
        when(authService.resolveRoleByUserId("u1")).thenReturn("USER");
        when(skillService.resolveAllowedToolPacks("api", "u1")).thenReturn(allowedToolPacks);
        when(agentDecisionService.decide(org.mockito.ArgumentMatchers.any(AgentDecisionRequest.class))).thenReturn(decision);
        when(chatRoutingStateService.resolveDefaultMode("simplified")).thenReturn("simplified");
        when(chatRoutingStateService.resolveAutoUpgrade(true)).thenReturn(true);
        when(chatRoutingPolicyService.decide(
                eq("今天日期是什么"),
                eq("USER"),
                eq("simplified"),
                eq(true),
                eq(allowedToolPacks),
                eq("agent")
        )).thenReturn(new ChatRoutingPolicyService.RoutingDecision(
                "今天日期是什么",
                "basic",
                false,
                false,
                "控制类输入保持原文路由。",
                "agent",
                "control"
        ));
        when(skillRegistryService.matchAgentVisibleDefinitions("今天日期是什么", allowedToolPacks, 2)).thenReturn(List.of());
        when(soulPromptService.buildSystemPrompt("api", "u1", List.of())).thenReturn("system");
        when(contextAssembler.assemble("s1", "api", "u1", "今天日期是什么")).thenReturn(assembled);
        when(aiProviderService.activeClient()).thenReturn(new AiProviderService.ActiveChatClient(
                "coding-plan",
                "o3",
                "http://localhost",
                mock(ChatClient.class),
                true,
                ""
        ));
        ChatContextFactory factory = new ChatContextFactory(
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
                turnPipeline,
                "simplified",
                true
        );

        ChatContext context = factory.build(new ChatRequest("s1", "u1", "今天日期是什么", "api", "agent"), true);

        ArgumentCaptor<AgentDecisionRequest> decisionRequestCaptor = ArgumentCaptor.forClass(AgentDecisionRequest.class);
        verify(agentDecisionService).decide(decisionRequestCaptor.capture());
        assertThat(decisionRequestCaptor.getValue().question()).isEqualTo("今天日期是什么");
        assertThat(context.userMessage()).isEqualTo("今天日期是什么");
        assertThat(context.effectiveUserMessage()).isEqualTo("今天日期是什么");
    }
}
