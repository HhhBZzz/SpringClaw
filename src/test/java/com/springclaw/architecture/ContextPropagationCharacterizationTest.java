package com.springclaw.architecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.domain.entity.AgentSession;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentDecisionRequest;
import com.springclaw.service.agent.AgentDecisionRouter;
import com.springclaw.service.agent.AgentDecisionService;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatContextFactory;
import com.springclaw.service.chat.impl.ChatRoutingPolicyService;
import com.springclaw.service.chat.impl.ChatRoutingStateService;
import com.springclaw.service.chat.impl.ConversationAdvisorSupport;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import com.springclaw.service.chat.impl.ModelTransportGuardService;
import com.springclaw.service.chat.impl.SemanticMemoryAdvisor;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextAssembler;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.MemoryBankService;
import com.springclaw.service.memory.MemoryService;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.session.AgentSessionService;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.usage.LlmUsageRecordService;
import com.springclaw.tool.runtime.CapabilityRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization test — pins the production context assembly, injection, and
 * advisor paths used by the current runtime.
 *
 * <p>{@link ContextAssembler} places event and semantic recall into the observe
 * prompt. {@link ChatContextFactory} currently exposes that prompt through
 * {@code ContextInjection} while leaving policy and pending-proposal prompts
 * empty. {@link ConversationAdvisorSupport} conditionally adds Spring AI chat
 * memory before semantic memory. The pre-engine model router in
 * {@link AgentDecisionService} calls the ChatClient directly and bypasses that
 * advisor support.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
class ContextPropagationCharacterizationTest {

    private static final String MEMORY_BANK_MARKER = "MEMORY-BANK-CHARACTERIZATION-7F3A";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("ContextAssembler production call renders question, Memory Bank, event, and semantic sections")
    void contextAssemblerBuildsObservedFourSectionPrompt() throws IOException {
        AssembledContext context = assembleContext();

        assertThat(context.observePrompt())
                .contains("# 当前问题", "为什么登录失败")
                .contains("# 项目记忆（Memory Bank）", MEMORY_BANK_MARKER)
                .contains("# 短期会话上下文（事件流）", context.eventContext(), "上一轮问题")
                .contains("# 长期语义记忆（同会话优先）", context.semanticContext(),
                        "previous error: NullPointerException");
        assertThat(context.sourceSummary().memoryBankUsed()).isTrue();
        assertThat(context.sourceSummary().memoryBankChars())
                .isGreaterThanOrEqualTo(MEMORY_BANK_MARKER.length());
    }

    @Test
    @DisplayName("ChatContextFactory production call carries only assembled observePrompt in ContextInjection")
    void chatContextFactoryBuildsCurrentInjectionShape() throws IOException {
        AiProviderService aiProviderService = mock(AiProviderService.class);
        SoulPromptService soulPromptService = mock(SoulPromptService.class);
        AgentSessionService agentSessionService = mock(AgentSessionService.class);
        AuthService authService = mock(AuthService.class);
        SkillService skillService = mock(SkillService.class);
        SkillRegistryService skillRegistryService = mock(SkillRegistryService.class);
        ContextAssembler contextAssembler = mock(ContextAssembler.class);
        ChatRoutingStateService routingStateService = mock(ChatRoutingStateService.class);
        ChatRoutingPolicyService routingPolicyService = mock(ChatRoutingPolicyService.class);
        AgentDecisionService agentDecisionService = mock(AgentDecisionService.class);
        AgentSession session = new AgentSession();
        session.setId(1L);
        session.setSessionKey("session-A");
        session.setChannel("api");
        session.setUserId("alice");
        AssembledContext assembled = assembleContext();
        AgentDecision decision = AgentDecision.general("普通聊天");
        Set<String> allowedToolPacks = Set.of("workspace");

        when(agentSessionService.getOrCreate("session-A", "api", "alice")).thenReturn(session);
        when(authService.resolveRoleByUserId("alice")).thenReturn("USER");
        when(skillService.resolveAllowedToolPacks("api", "alice")).thenReturn(allowedToolPacks);
        when(agentDecisionService.decide(any(AgentDecisionRequest.class))).thenReturn(decision);
        when(routingStateService.resolveDefaultMode("simplified")).thenReturn("simplified");
        when(routingStateService.resolveAutoUpgrade(true)).thenReturn(true);
        when(routingPolicyService.decide(
                "为什么登录失败",
                "USER",
                "simplified",
                true,
                allowedToolPacks,
                "agent"
        )).thenReturn(new ChatRoutingPolicyService.RoutingDecision(
                "为什么登录失败",
                "simplified",
                false,
                false,
                "使用当前默认链路。",
                "agent",
                "general"
        ));
        when(skillRegistryService.matchAgentVisibleDefinitions(
                "为什么登录失败", allowedToolPacks, 2)).thenReturn(List.of());
        when(soulPromptService.buildSystemPrompt("api", "alice", List.of())).thenReturn("system");
        when(contextAssembler.assemble(
                "session-A", "api", "alice", "为什么登录失败")).thenReturn(assembled);
        when(aiProviderService.activeClient()).thenReturn(activeClient(mock(ChatClient.class)));
        ChatContextFactory factory = new ChatContextFactory(
                aiProviderService,
                soulPromptService,
                agentSessionService,
                authService,
                skillService,
                skillRegistryService,
                contextAssembler,
                routingStateService,
                routingPolicyService,
                agentDecisionService,
                "simplified",
                true
        );

        String acceptedRunId = "11111111111111111111111111111111";
        ChatContext context = factory.build(
                new ChatRequest("session-A", "alice", "为什么登录失败", "api", "agent"),
                true,
                acceptedRunId
        );

        assertThat(context.requestId()).isEqualTo(acceptedRunId);
        assertThat(context.contextInjection().observePrompt()).isEqualTo(assembled.observePrompt());
        assertThat(context.contextInjection().policyPrompt()).isEmpty();
        assertThat(context.contextInjection().pendingProposalPrompt()).isEmpty();
        assertThat(context.contextInjection().renderForPrompt())
                .isEqualTo(assembled.observePrompt() + "\n\n");
        assertThat(context.contextInjection().metadata())
                .containsEntry("contextSummary", assembled.sourceSummary());
    }

    @Test
    @DisplayName("ConversationAdvisorSupport flag OFF applies semantic advisor only")
    void advisorChainDefaultIncludesSemanticOnly() {
        MessageChatMemoryAdvisor messageAdvisor = messageAdvisor();
        SemanticMemoryAdvisor semanticAdvisor = mock(SemanticMemoryAdvisor.class);

        AdvisorApplication application = applyAdvisors(
                new ConversationAdvisorSupport(messageAdvisor, semanticAdvisor, false)
        );

        assertThat(application.advisors()).containsExactly(semanticAdvisor);
        assertThat(application.params())
                .containsEntry("chat_memory_conversation_id", "session-A")
                .containsEntry(SemanticMemoryAdvisor.CONTEXT_USER_ID, "alice");
    }

    @Test
    @DisplayName("ConversationAdvisorSupport flag ON applies chat memory before semantic memory")
    void advisorChainWithFlagOnIncludesBothInProductionOrder() {
        MessageChatMemoryAdvisor messageAdvisor = messageAdvisor();
        SemanticMemoryAdvisor semanticAdvisor = mock(SemanticMemoryAdvisor.class);

        AdvisorApplication application = applyAdvisors(
                new ConversationAdvisorSupport(messageAdvisor, semanticAdvisor, true)
        );

        assertThat(application.advisors()).containsExactly(messageAdvisor, semanticAdvisor);
        assertThat(application.params())
                .containsEntry("chat_memory_conversation_id", "session-A")
                .containsEntry(SemanticMemoryAdvisor.CONTEXT_USER_ID, "alice");
    }

    @Test
    @DisplayName("AgentDecisionService model router calls ChatClient directly without ConversationAdvisorSupport")
    void agentDecisionModelRouterBypassesConversationAdvisorSupport() {
        AgentDecisionRouter ruleRouter = mock(AgentDecisionRouter.class);
        AiProviderService aiProviderService = mock(AiProviderService.class);
        ModelTransportGuardService transportGuard = mock(ModelTransportGuardService.class);
        LlmUsageRecordService usageRecordService = mock(LlmUsageRecordService.class);
        CapabilityRegistry capabilityRegistry = mock(CapabilityRegistry.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec responseSpec = mock(ChatClient.CallResponseSpec.class);
        AiProviderService.ActiveChatClient activeClient = activeClient(chatClient);
        ModelCallExecutor modelCallExecutor = new ModelCallExecutor(
                aiProviderService,
                transportGuard,
                usageRecordService,
                0,
                0
        );
        AgentDecisionService service = new AgentDecisionService(
                ruleRouter,
                aiProviderService,
                modelCallExecutor,
                transportGuard,
                capabilityRegistry,
                new ObjectMapper(),
                true
        );
        AgentDecision clarification = AgentDecision.clarify("需要模型分类");
        when(ruleRouter.routeByRules(any(AgentDecisionRequest.class))).thenReturn(clarification);
        when(aiProviderService.activeClient()).thenReturn(activeClient);
        when(transportGuard.isModelCallEnabled(activeClient)).thenReturn(true);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.chatResponse()).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("""
                        {"intent":"general","executionPath":"basic_model","selectedCapabilities":[],
                        "riskLevel":"read","requiresConfirmation":false,"reason":"普通问答"}
                        """))
        )));

        AgentDecision decision = service.decide(new AgentDecisionRequest(
                "session-A",
                "api",
                "alice",
                "USER",
                "request-A",
                "帮我处理",
                "agent",
                Set.of()
        ));

        assertThat(decision.intent()).isEqualTo("general");
        var ordered = inOrder(chatClient, requestSpec, responseSpec);
        ordered.verify(chatClient).prompt();
        ordered.verify(requestSpec).system(anyString());
        ordered.verify(requestSpec).user(anyString());
        ordered.verify(requestSpec).call();
        ordered.verify(responseSpec).chatResponse();
        verify(requestSpec, never()).advisors(any(Consumer.class));
        verify(requestSpec, never()).advisors(any(Advisor[].class));
        verify(requestSpec, never()).advisors(any(List.class));
    }

    private AdvisorApplication applyAdvisors(ConversationAdvisorSupport support) {
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        ArgumentCaptor<Map<String, Object>> paramsCaptor = mapCaptor();
        ArgumentCaptor<List<Advisor>> advisorsCaptor = advisorListCaptor();
        when(advisorSpec.params(paramsCaptor.capture())).thenReturn(advisorSpec);
        when(advisorSpec.advisors(advisorsCaptor.capture())).thenReturn(advisorSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenAnswer(invocation -> {
            Consumer<ChatClient.AdvisorSpec> customizer = invocation.getArgument(0);
            customizer.accept(advisorSpec);
            return requestSpec;
        });

        ChatClient.ChatClientRequestSpec returned = support.apply(
                requestSpec,
                "session-A",
                "alice"
        );

        assertThat(returned).isSameAs(requestSpec);
        return new AdvisorApplication(
                advisorsCaptor.getValue(),
                paramsCaptor.getValue()
        );
    }

    private ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }

    private ArgumentCaptor<List<Advisor>> advisorListCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    private AiProviderService.ActiveChatClient activeClient(ChatClient chatClient) {
        return new AiProviderService.ActiveChatClient(
                "test-provider",
                "test-model",
                "http://localhost",
                chatClient,
                true,
                ""
        );
    }

    private MessageChatMemoryAdvisor messageAdvisor() {
        return MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                        .maxMessages(20)
                        .build()
        ).build();
    }

    private AssembledContext assembleContext() throws IOException {
        MessageEventService messageEventService = mock(MessageEventService.class);
        MemoryService memoryService = mock(MemoryService.class);
        when(messageEventService.listRecent("session-A", 16)).thenReturn(List.of(
                event("USER", "CHAT", "上一轮问题")
        ));
        when(memoryService.recallBySession("session-A", "为什么登录失败", 8)).thenReturn(List.of(
                new Document(
                        "memory-1",
                        "previous error: NullPointerException",
                        Map.of(
                                "channel", "api",
                                "sessionKey", "session-A",
                                "userId", "alice",
                                "role", "USER"
                        )
                )
        ));
        when(memoryService.recallByUser("alice", "为什么登录失败", 4)).thenReturn(List.of());
        Path memoryBankRoot = tempDir.resolve("docs").resolve("memory-bank");
        Files.createDirectories(memoryBankRoot);
        Files.writeString(
                memoryBankRoot.resolve("current-state.md"),
                "# Current State\n\n" + MEMORY_BANK_MARKER
        );
        ContextAssembler assembler = new ContextAssembler(
                messageEventService,
                memoryService,
                new MemoryBankService(true, memoryBankRoot.toString(), 800),
                8,
                8,
                400
        );
        return assembler.assemble(
                "session-A",
                "api",
                "alice",
                "为什么登录失败"
        );
    }

    private MessageEvent event(String role, String eventType, String content) {
        MessageEvent event = new MessageEvent();
        event.setRole(role);
        event.setEventType(eventType);
        event.setContent(content);
        event.setChannel("api");
        event.setSessionKey("session-A");
        event.setUserId("alice");
        return event;
    }

    private record AdvisorApplication(List<Advisor> advisors, Map<String, Object> params) {
    }
}
