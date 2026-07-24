package com.springclaw.service.chat.impl;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ReAct 范式引擎 — Thought → Action → Observation 显式循环。
 * <p>
 * 与 {@link AutonomousLoopEngine}(模型自主选工具)不同,ReAct 要求模型按
 * "Thought / Action / Observation"结构化输出,引擎解析 Action 后调用工具,
 * 把 Observation 回灌给模型进入下一轮 Thought——直到模型给出 Final Answer。
 * </p>
 * <p>
 * <b>本类当前是骨架(Task 1)</b>:只完成接入地基——
 * <ul>
 *   <li>{@code paradigm()} 声明 {@link AgentParadigm#REACT}(配合
 *       {@link AgentParadigm#isImplemented()} 与 {@code EngineSelector.LEGACY_RANK} 登记);</li>
 *   <li>{@code supports()} 仅在 {@code ctx.paradigm() == REACT} 时为真;</li>
 *   <li>{@code execute} / {@code stream} 返回占位结果,不进入循环——循环体留待 Task 3 实现。</li>
 * </ul>
 * 构造函数已全量复用 AutonomousLoopEngine 的 11 bean 依赖,Task 3 填充循环时无需改签名。
 * </p>
 */
@Service
public class ReActEngine implements AgentEngine.StreamableAgentEngine {

    private static final Logger log = LoggerFactory.getLogger(ReActEngine.class);

    private final AiProviderService aiProviderService;
    private final ToolOrchestrator toolOrchestrator;
    private final ModelTransportGuardService modelTransportGuardService;
    private final ModelCallExecutor modelCallExecutor;
    private final ConversationAdvisorSupport conversationAdvisorSupport;
    private final LocalExecutionSupport localExecutionSupport;
    private final ChatResponsePolicyService chatResponsePolicyService;
    private final SseEventBridge sseEventBridge;
    private final ChatResultPersister chatResultPersister;
    private final ChatGuardService chatGuardService;
    private final RunLifecycleObserver lifecycleObserver;
    private final int maxReactSteps;

    public ReActEngine(AiProviderService aiProviderService,
                       ToolOrchestrator toolOrchestrator,
                       ModelTransportGuardService modelTransportGuardService,
                       ModelCallExecutor modelCallExecutor,
                       ConversationAdvisorSupport conversationAdvisorSupport,
                       LocalExecutionSupport localExecutionSupport,
                       ChatResponsePolicyService chatResponsePolicyService,
                       SseEventBridge sseEventBridge,
                       ChatResultPersister chatResultPersister,
                       ChatGuardService chatGuardService,
                       RunLifecycleObserver lifecycleObserver,
                       @Value("${springclaw.chat.max-react-steps:6}") int maxReactSteps) {
        this.aiProviderService = aiProviderService;
        this.toolOrchestrator = toolOrchestrator;
        this.modelTransportGuardService = modelTransportGuardService;
        this.modelCallExecutor = modelCallExecutor;
        this.conversationAdvisorSupport = conversationAdvisorSupport;
        this.localExecutionSupport = localExecutionSupport;
        this.chatResponsePolicyService = chatResponsePolicyService;
        this.sseEventBridge = sseEventBridge;
        this.chatResultPersister = chatResultPersister;
        this.chatGuardService = chatGuardService;
        this.lifecycleObserver = lifecycleObserver;
        this.maxReactSteps = Math.max(1, Math.min(maxReactSteps, 15));
    }

    @Override
    public String name() {
        return "react-loop";
    }

    @Override
    public AgentParadigm paradigm() {
        return AgentParadigm.REACT;
    }

    @Override
    public int priority() {
        return 4;
    }

    @Override
    public boolean supports(ChatContext ctx) {
        return ctx != null && ctx.paradigm() == AgentParadigm.REACT;
    }

    /**
     * 阻塞执行入口——Task 3 填充 Thought-Action-Observation 循环。
     * <p>骨架占位:不进入循环,直接返回降级结果(不炸)。</p>
     */
    @Override
    public ChatExecutionResult execute(ChatContext ctx, FallbackResponder fallbackResponder) {
        log.debug("ReActEngine.execute 骨架占位(待 Task 3 实现): requestId={}", ctx.requestId());
        AssembledContext assembled = ctx.assembled();
        String observe = assembled == null ? "" : assembled.observePrompt();
        String fallback = assembled == null ? "" : fallbackResponder.respond("ReAct", assembled);
        return new ChatExecutionResult(
                observe,
                "ReAct 阻塞入口(待 Task 3 实现)",
                "",
                fallback,
                false
        );
    }

    /**
     * 流式执行入口——Task 3 填充。
     * <p>骨架占位:不启动流,返回 {@code null}(与 {@link AutonomousLoopEngine#stream}
     * 返回 null 一致,由调用方处理)。</p>
     */
    @Override
    public Disposable stream(ChatContext context,
                             SseEmitter emitter,
                             String lockToken,
                             AtomicBoolean lockReleased,
                             AtomicReference<Disposable> disposableRef,
                             OnStreamFailure fallbackHandler) {
        log.debug("ReActEngine.stream 骨架占位(待 Task 3 实现): requestId={}", context.requestId());
        return null;
    }
}
