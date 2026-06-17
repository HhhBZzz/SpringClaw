package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentActionProposal;
import com.springclaw.service.agent.AgentActionProposalService;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.agent.AgentRun;
import com.springclaw.service.agent.AgentRuntimeEngine;
import com.springclaw.service.agent.CapabilityPlan;
import com.springclaw.service.agent.EngineSelector;
import com.springclaw.service.agent.VerificationResult;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.service.usage.LlmUsageRecordService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceImplModeTest {

    @Test
    void shouldDefaultToSimplifiedWhenModeIsUnknown() {
        Fixture fixture = new Fixture();
        fixture.useEngine(fixture.simplifiedOparEngine);
        ChatContext context = fixture.buildChatContext("你好", "simplified", "默认");
        when(fixture.chatContextFactory.build(any(ChatRequest.class), anyBoolean())).thenReturn(context);
        when(fixture.simplifiedOparEngine.execute(any(), any()))
                .thenReturn(new ChatExecutionResult("observe", "SIMPLIFIED", "ACTION", "answer", true));

        ChatServiceImpl service = fixture.build();
        service.chat(new ChatRequest("s1", "u1", "你好", "api"));

        verify(fixture.simplifiedOparEngine).execute(any(), any());
        verify(fixture.oparLoopEngine, never()).execute(any(), any());
    }

    @Test
    void shouldUseOparWhenModeExplicitlyConfigured() {
        Fixture fixture = new Fixture();
        fixture.useEngine(fixture.oparLoopEngine);
        ChatContext context = fixture.buildChatContext("你好", "opar", "默认");
        when(fixture.chatContextFactory.build(any(ChatRequest.class), anyBoolean())).thenReturn(context);
        when(fixture.oparLoopEngine.execute(any(), any()))
                .thenReturn(new ChatExecutionResult("observe", "PLAN", "ACTION", "answer", true));

        ChatServiceImpl service = fixture.build();
        service.chat(new ChatRequest("s1", "u1", "你好", "api"));

        verify(fixture.oparLoopEngine).execute(any(), any());
        verify(fixture.simplifiedOparEngine, never()).execute(any(), any());
    }

    @Test
    void shouldUseOparWhenRoutingPolicyAutoUpgrades() {
        Fixture fixture = new Fixture();
        fixture.useEngine(fixture.oparLoopEngine);
        ChatContext context = fixture.buildChatContext("分析这个启动报错并给修复方案", "opar", "自动升级");
        when(fixture.chatContextFactory.build(any(ChatRequest.class), anyBoolean())).thenReturn(context);
        when(fixture.oparLoopEngine.execute(any(), any()))
                .thenReturn(new ChatExecutionResult("observe", "PLAN", "ACTION", "answer", true));

        ChatServiceImpl service = fixture.build();
        service.chat(new ChatRequest("s1", "u1", "分析这个启动报错并给修复方案", "api"));

        verify(fixture.oparLoopEngine).execute(any(), any());
        verify(fixture.simplifiedOparEngine, never()).execute(any(), any());
    }

    @Test
    void streamShouldReturnEmitterBeforeAgentExecutionFinishes() throws Exception {
        Fixture fixture = new Fixture();
        fixture.useEngine(fixture.simplifiedOparEngine);
        ChatContext context = fixture.buildChatContext("你好", "simplified", "默认");
        when(fixture.chatContextFactory.build(any(ChatRequest.class), anyBoolean())).thenReturn(context);
        when(fixture.simplifiedOparEngine.execute(any(), any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(350);
                    return new ChatExecutionResult("observe", "SIMPLIFIED", "ACTION", "answer", true);
                });

        ChatServiceImpl service = fixture.build();
        long start = System.currentTimeMillis();
        SseEmitter emitter = service.stream(new ChatRequest("s1", "u1", "你好", "api"));
        long elapsed = System.currentTimeMillis() - start;

        emitter.complete();
        Assertions.assertTrue(elapsed < 180, "stream endpoint should return immediately, elapsed=" + elapsed);
    }

    @Test
    void streamShouldEmitLocalFallbackTraceForOparShortcut() {
        Fixture fixture = new Fixture();
        fixture.useEngine(fixture.oparLoopEngine);
        AgentDecision decision = new AgentDecision(
                "workspace_analysis",
                "agent_tools",
                java.util.List.of("workspace-review"),
                "read",
                false,
                "项目分析"
        );
        ChatContext context = fixture.buildChatContext(
                "分析当前项目结构",
                "opar",
                "用户显式选择深度模式，使用 OPAR 链路。",
                "deep",
                "workspace_analysis",
                decision
        );
        when(fixture.chatContextFactory.build(any(ChatRequest.class), anyBoolean())).thenReturn(context);
        when(fixture.oparLoopEngine.execute(any(), any())).thenReturn(new ChatExecutionResult(
                "observe",
                "命中已决策能力的本地执行路线。\n执行路线: BUILTIN_SKILL:CODE_ANALYSIS",
                "已由受控本地技能完成执行。\n真实执行结果:\nskill=code-analysis\n项目结构概览",
                "项目结构概览",
                false
        ));

        SseEmitter emitter = fixture.build().stream(new ChatRequest("s1", "u1", "分析当前项目结构", "api"));

        verify(fixture.sseEventBridge, timeout(1000)).sendTrace(
                eq(emitter),
                eq(context),
                eq("本地短路执行"),
                eq("local_fallback"),
                eq("success"),
                contains("执行路线"),
                eq(0L)
        );
        emitter.complete();
    }

    @Test
    void shouldNotSupportModelLedStreamingByDefault() {
        Fixture fixture = new Fixture();
        ChatContext context = fixture.buildChatContext("帮我分析当前项目结构", "simplified", "默认", "agent");

        Assertions.assertFalse(fixture.modelLedStreamEngine(false).supports(context));
    }

    @Test
    void shouldSupportBasicStreamingForGeneralQuestionsByDefault() {
        Fixture fixture = new Fixture();
        ChatContext context = fixture.buildChatContext("你好", "simplified", "默认", "agent", "general");

        Assertions.assertTrue(fixture.basicStreamEngine(true).supports(context));
    }

    @Test
    void shouldNotSupportModelLedStreamingForFastMode() {
        Fixture fixture = new Fixture();
        ChatContext context = fixture.buildChatContext("帮我分析当前项目结构", "simplified", "默认", "fast");

        Assertions.assertFalse(fixture.modelLedStreamEngine(true).supports(context));
    }

    @Test
    void shouldNotSupportModelLedStreamingForControlIntent() {
        Fixture fixture = new Fixture();
        ChatContext context = fixture.buildChatContext("当前模型是什么", "simplified", "默认", "agent", "control_plane");

        Assertions.assertFalse(fixture.modelLedStreamEngine(true).supports(context));
    }

    @Test
    void shouldNotSupportModelLedStreamingForLocalFileIntent() {
        Fixture fixture = new Fixture();
        ChatContext context = fixture.buildChatContext("授权桌面全部", "simplified", "默认", "agent", "local_files");

        Assertions.assertFalse(fixture.modelLedStreamEngine(true).supports(context));
        Assertions.assertFalse(fixture.basicStreamEngine(true).supports(context));
    }

    @Test
    void shouldNotSupportModelLedStreamingForAgentToolDecisionsEvenWhenEnabled() {
        Fixture fixture = new Fixture();
        ChatContext context = fixture.buildChatContext(
                "分析当前项目结构",
                "simplified",
                "默认",
                "agent",
                "workspace_analysis",
                new AgentDecision("workspace_analysis", "agent_tools", java.util.List.of("workspace-review"), "read", false, "项目分析")
        );

        Assertions.assertFalse(fixture.modelLedStreamEngine(true).supports(context));
    }

    @Test
    void shouldNotSupportModelLedStreamingWhenDecisionRequiresConfirmation() {
        Fixture fixture = new Fixture();
        ChatContext context = fixture.buildChatContext(
                "修改 README 并执行命令验证",
                "simplified",
                "默认",
                "agent",
                "workspace_analysis",
                new AgentDecision("workspace_analysis", "basic_model", java.util.List.of(), "write", true, "写入类请求需要确认")
        );

        Assertions.assertFalse(fixture.modelLedStreamEngine(true).supports(context));
    }

    @Test
    void streamShouldRequestConfirmationBeforeStreamableEngineRuns() {
        Fixture fixture = new Fixture();
        AgentDecision decision = new AgentDecision(
                "workspace_analysis",
                "basic_model",
                java.util.List.of(),
                "write",
                true,
                "写入类请求需要确认"
        );
        ChatContext context = fixture.buildChatContext(
                "修改 README 并执行命令验证",
                "simplified",
                "默认",
                "agent",
                "workspace_analysis",
                decision
        );
        AgentEngine.StreamableAgentEngine streamable = mock(AgentEngine.StreamableAgentEngine.class);
        AgentActionProposal proposal = new AgentActionProposal(
                "proposal-1",
                "req-1",
                "s1",
                "u1",
                "workspace_action",
                "需要确认",
                "修改 README 并执行命令验证",
                "write",
                java.util.Map.of(),
                System.currentTimeMillis() + 60_000,
                "PENDING"
        );
        fixture.useEngine(streamable);
        when(fixture.chatContextFactory.build(any(ChatRequest.class), anyBoolean())).thenReturn(context);
        when(fixture.actionProposalService.createProposal(
                eq("s1"),
                eq("api"),
                eq("u1"),
                eq("USER"),
                eq("req-1"),
                eq("修改 README 并执行命令验证"),
                eq(decision)
        )).thenReturn(proposal);

        SseEmitter emitter = fixture.build().stream(new ChatRequest("s1", "u1", "修改 README 并执行命令验证", "api"));

        verify(fixture.sseEventBridge, timeout(1000)).sendActionRequired(eq(emitter), eq(proposal));
        verify(streamable, timeout(300).times(0)).stream(any(), any(), anyString(), any(), any(), any());
        emitter.complete();
    }

    @Test
    void shouldUseAgentRuntimeForNonGeneralDecisions() {
        Fixture fixture = new Fixture();
        fixture.useEngine(fixture.agentRuntimeEngine);
        AgentDecision decision = new AgentDecision("workspace_analysis", "agent_tools", java.util.List.of("workspace-review"), "read", false, "项目分析");
        ChatContext context = fixture.buildChatContext(
                "分析当前项目结构",
                "simplified",
                "默认",
                "agent",
                "workspace_analysis",
                decision
        );
        when(fixture.chatContextFactory.build(any(ChatRequest.class), anyBoolean())).thenReturn(context);
        when(fixture.agentRuntimeEngine.run(context)).thenReturn(new AgentRun(
                "req-1",
                decision,
                new CapabilityPlan("workspace_analysis", "agent_tools", java.util.List.of("workspace-review"), java.util.List.of("workspace"), "read", false, "项目分析"),
                java.util.List.of(),
                java.util.List.of(),
                new VerificationResult("success", true, "ok"),
                new ChatExecutionResult("observe", "PLAN", "ACTION", "runtime answer", true)
        ));

        ChatServiceImpl service = fixture.buildWithRuntime();
        ChatResponse response = service.chat(new ChatRequest("s1", "u1", "分析当前项目结构", "api"));

        Assertions.assertEquals("runtime answer", response.answer());
        verify(fixture.agentRuntimeEngine).run(context);
        verify(fixture.simplifiedOparEngine, never()).run(any(), anyString(), any(), anyString(), any(), any());
        verify(fixture.oparLoopEngine, never()).runLoop(any(), anyString(), any(), anyString(), any(), any());
    }

    @Test
    void shouldNotSelectAgentRuntimeForExplicitOparStreamContext() {
        Fixture fixture = new Fixture();
        AgentDecision decision = new AgentDecision("workspace_analysis", "agent_tools", java.util.List.of("workspace-review"), "read", false, "项目分析");
        ChatContext context = fixture.buildChatContext(
                "分析当前项目结构",
                "opar",
                "用户显式选择深度模式，使用 OPAR 链路。",
                "deep",
                "workspace_analysis",
                decision
        );
        // opar execution mode: neither basic-stream nor model-led-stream should support this
        Assertions.assertFalse(fixture.basicStreamEngine(true).supports(context));
        Assertions.assertFalse(fixture.modelLedStreamEngine(true).supports(context));
    }

    private static final class Fixture {

        private final AiProviderService aiProviderService = mock(AiProviderService.class);
        private final ChatGuardService chatGuardService = mock(ChatGuardService.class);
        private final OparLoopEngine oparLoopEngine = mock(OparLoopEngine.class);
        private final SimplifiedOparEngine simplifiedOparEngine = mock(SimplifiedOparEngine.class);
        private final ChatResponsePolicyService chatResponsePolicyService = mock(ChatResponsePolicyService.class);
        private final ModelTransportGuardService modelTransportGuardService = mock(ModelTransportGuardService.class);
        private final LlmUsageRecordService llmUsageRecordService = mock(LlmUsageRecordService.class);
        private final ConversationAdvisorSupport conversationAdvisorSupport = mock(ConversationAdvisorSupport.class);
        private final ChatContextFactory chatContextFactory = mock(ChatContextFactory.class);
        private final ChatResultPersister chatResultPersister = mock(ChatResultPersister.class);
        private final MetaGuardExecutor metaGuardExecutor = mock(MetaGuardExecutor.class);
        private final ModelCallExecutor modelCallExecutor = mock(ModelCallExecutor.class);
        private final ToolOrchestrator toolOrchestrator = mock(ToolOrchestrator.class);
        private final AgentActionProposalService actionProposalService = mock(AgentActionProposalService.class);
        private final AgentRuntimeEngine agentRuntimeEngine = mock(AgentRuntimeEngine.class);
        private final SseEventBridge sseEventBridge = mock(SseEventBridge.class);
        private final EngineSelector engineSelector = mock(EngineSelector.class);
        private final AgentSession session = new AgentSession();
        private final AssembledContext assembled = new AssembledContext(
                "s1",
                "api",
                "u1",
                "你好",
                "- USER: 你好",
                "（暂无长期语义记忆）",
                "# 当前问题\n你好"
        );
        private final AiProviderService.ActiveChatClient activeClient = new AiProviderService.ActiveChatClient(
                "deepseek",
                "deepseek-chat",
                "https://api.deepseek.com",
                mock(ChatClient.class),
                true,
                ""
        );

        private Fixture() {
            session.setId(1L);
            session.setSessionKey("s1");
            when(chatGuardService.acquireSessionLock("s1")).thenReturn("lock");
            when(aiProviderService.activeClient()).thenReturn(activeClient);
            when(modelTransportGuardService.isModelCallEnabled(activeClient)).thenReturn(true);
        }

        /** Create a real BasicStreamEngine with given basicStreamingEnabled flag for supports() testing. */
        private BasicStreamEngine basicStreamEngine(boolean basicStreamingEnabled) {
            return new BasicStreamEngine(
                    modelCallExecutor,
                    conversationAdvisorSupport,
                    modelTransportGuardService,
                    chatResponsePolicyService,
                    llmUsageRecordService,
                    oparLoopEngine,
                    sseEventBridge,
                    chatResultPersister,
                    chatGuardService,
                    basicStreamingEnabled
            );
        }

        /** Create a real ModelLedStreamEngine with given modelLedStreamingEnabled flag for supports() testing. */
        private ModelLedStreamEngine modelLedStreamEngine(boolean modelLedStreamingEnabled) {
            return new ModelLedStreamEngine(
                    conversationAdvisorSupport,
                    modelTransportGuardService,
                    chatResponsePolicyService,
                    llmUsageRecordService,
                    modelCallExecutor,
                    toolOrchestrator,
                    sseEventBridge,
                    chatResultPersister,
                    chatGuardService,
                    modelLedStreamingEnabled
            );
        }

        private ChatContext buildChatContext(String message, String executionMode, String routingReason) {
            return buildChatContext(message, executionMode, routingReason, "agent");
        }

        private ChatContext buildChatContext(String message, String executionMode, String routingReason, String responseMode) {
            return buildChatContext(message, executionMode, routingReason, responseMode, "general");
        }

        private ChatContext buildChatContext(String message, String executionMode, String routingReason, String responseMode, String intent) {
            return buildChatContext(message, executionMode, routingReason, responseMode, intent, AgentDecision.general("兼容旧构造器，默认普通聊天。"));
        }

        private ChatContext buildChatContext(String message,
                                             String executionMode,
                                             String routingReason,
                                             String responseMode,
                                             String intent,
                                             AgentDecision decision) {
            return new ChatContext(
                    session,
                    "api",
                    "u1",
                    "USER",
                    message,
                    message,
                    "req-1",
                    "system",
                    assembled,
                    activeClient,
                    executionMode,
                    routingReason,
                    responseMode,
                    intent,
                    decision
            );
        }

        private Fixture useEngine(AgentEngine engine) {
            when(engineSelector.select(any(ChatContext.class))).thenReturn(engine);
            return this;
        }

        private ChatServiceImpl build() {
            return new ChatServiceImpl(
                    aiProviderService,
                    chatGuardService,
                    oparLoopEngine,
                    simplifiedOparEngine,
                    chatResponsePolicyService,
                    modelTransportGuardService,
                    llmUsageRecordService,
                    conversationAdvisorSupport,
                    chatContextFactory,
                    chatResultPersister,
                    metaGuardExecutor,
                    toolOrchestrator,
                    actionProposalService,
                    null,
                    engineSelector,
                    null,
                    sseEventBridge,
                    null,
                    false,
                    true
            );
        }

        private ChatServiceImpl buildWithRuntime() {
            when(agentRuntimeEngine.supports(any(ChatContext.class))).thenCallRealMethod();
            return new ChatServiceImpl(
                    aiProviderService,
                    chatGuardService,
                    oparLoopEngine,
                    simplifiedOparEngine,
                    chatResponsePolicyService,
                    modelTransportGuardService,
                    llmUsageRecordService,
                    conversationAdvisorSupport,
                    chatContextFactory,
                    chatResultPersister,
                    metaGuardExecutor,
                    toolOrchestrator,
                    actionProposalService,
                    agentRuntimeEngine,
                    engineSelector,
                    null,
                    sseEventBridge,
                    null,
                    false,
                    true
            );
        }
    }
}
