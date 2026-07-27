package com.springclaw.service.chat.impl;

import com.springclaw.common.util.TextUtils;
import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
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
 * <b>当前状态:Task 1-6 全部完成</b>——
 * <ul>
 *   <li>{@code paradigm()} 声明 {@link AgentParadigm#REACT}(配合
 *       {@link AgentParadigm#isImplemented()} 与 {@code EngineSelector.LEGACY_RANK} 登记);</li>
 *   <li>{@code supports()} 仅在 {@code ctx.paradigm() == REACT} 时为真;</li>
 *   <li>{@code execute} / {@code stream} 跑 {@link #runReActLoop}:经典 ReAct 手动循环——
 *       每步一次模型调用(不挂 {@code .tools()},系统 prompt 让 LLM 文本输出 Thought + Action),
 *       引擎解析 Action(经共享 {@link ExplicitToolExecutioner} 容错 markdown 噪声)→ 在 tools 中按名查找 @Tool 方法
 *       ({@link ToolReflectionSupport#findToolMethod})→ 参数绑定 → 反射调用
 *       Spring 代理 bean(经 ToolRuntimeAspect 审计 + tracker 证据上报)→ 结果作为 Observation 回灌,
 *       进入下一轮 Thought;"无 Action 行即最终答案"终止 + max-steps 兜底。<b>所有模型一致走手动循环</b>
 *       (ReAct 的多步可见循环依赖引擎手动执行工具,Spring AI {@code .tools()} 在 {@code .call()} 内部
 *       完成工具往返不可见,不符合 ReAct 语义)。</li>
 *   <li>假完成守护(Task 4):复用 {@link AutonomousExecutionTracker},write/side_effect/dangerous
 *       任务在完成判定处校验工具证据,无证据则拒绝并注入提示继续循环。</li>
 *   <li>ChatExecutionResult/trace 精细化投影(Task 6):{@link #finalResult} 五字段完整
 *       (observe / plan="ReAct 执行 N 步" / action=每步 Thought-Action 轨迹 /
 *       reflect=最终答案+步骤概要 / modelEnabled);{@link #resolveFinalAnswer} 优先 reflect 兜底;
 *       每步 SSE 三段式 trace(Thought/Action/Observation)。</li>
 *   <li>手动循环主路径提取为共享 {@link ExplicitToolExecutioner} + {@link ToolReflectionSupport}
 *       (Plan-Execute 范式 Task 4 复用),ReAct 注入共享类,行为不变。</li>
 * </ul>
 * 构造函数全量复用 AutonomousLoopEngine 的 11 bean 依赖 + {@link ExplicitToolExecutioner}。
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
    private final ExplicitToolExecutioner explicitToolExecutioner;
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
                       ExplicitToolExecutioner explicitToolExecutioner,
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
        this.explicitToolExecutioner = explicitToolExecutioner;
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
     * 阻塞执行入口——内部跑 {@link #runReActLoop}(无 emitter)。
     * <p>结构对齐 {@link AutonomousLoopEngine#execute}:直接进入循环,返回 {@link ChatExecutionResult}。</p>
     */
    @Override
    public ChatExecutionResult execute(ChatContext ctx, FallbackResponder fallbackResponder) {
        return runReActLoop(ctx, null, ctx.requestId());
    }

    /**
     * 流式执行入口——管理完整 SSE 生命周期,内部跑 {@link #runReActLoop}。
     * <p>结构对齐 {@link AutonomousLoopEngine#stream}:trace(started) → 循环 → 发送最终答案 →
     * persist(TERMINAL_RESULT) → reportResult → trace(success) → releaseLockOnce → completeEmitter;
     * 异常委托 {@code fallbackHandler}。</p>
     */
    @Override
    public Disposable stream(ChatContext context,
                             SseEmitter emitter,
                             String lockToken,
                             AtomicBoolean lockReleased,
                             AtomicReference<Disposable> disposableRef,
                             OnStreamFailure fallbackHandler) {
        try {
            sseEventBridge.sendTrace(emitter, context, "ReAct 循环", "react", "started",
                    "进入 ReAct Thought-Action-Observation 循环。", 0L);
            sseEventBridge.sendStatus(emitter, "ReAct 循环执行中");

            ChatExecutionResult result = runReActLoop(context, emitter, context.requestId());

            String finalAnswer = resolveFinalAnswer(result);
            sseEventBridge.sendAnswerChunks(emitter, finalAnswer);
            chatResultPersister.persist(context, finalAnswer, result, ChatPersistenceIntent.TERMINAL_RESULT);
            reportResult(context, result, finalAnswer);

            sseEventBridge.sendTrace(emitter, context, "ReAct 循环", "react", "success",
                    "ReAct 循环执行完成(" + result.plan() + ")。", 0L);
            sseEventBridge.sendTrace(emitter, context, "完成", "final", "success",
                    "已生成最终回答。", 0L);
            releaseLockOnce(context, lockToken, lockReleased);
            sseEventBridge.completeEmitter(emitter);
        } catch (Exception ex) {
            log.warn("ReAct 循环 SSE 执行失败: sessionKey={}, reason={}",
                    context.assembled() == null ? "?" : context.assembled().sessionKey(), ex.getMessage());
            try {
                String simplifiedReason = chatResponsePolicyService.simplifyFailureReason(ex.getMessage());
                sseEventBridge.sendTrace(emitter, context, "ReAct 循环", "react", "failed", simplifiedReason, 0L);
            } catch (Exception ignored) {}
            fallbackHandler.handle(context, ex, emitter, lockToken, lockReleased);
        }
        return null;
    }

    // === Thought-Action-Observation 循环(手动循环主路径:所有模型一致) ===

    /**
     * ReAct 核心循环——每步一次模型调用(不挂 {@code .tools()}),根据 LLM 输出判定是否含 Action 行,
     * 无 Action 行即视为最终答案并终止;否则解析 Action、手动执行工具,把本轮 Thought/Action/Observation
     * 拼入历史进入下一轮。
     * <p>结构对齐 {@link AutonomousLoopEngine#runAutonomousLoop}(L194-411):
     * 模型不可用降级 → 选工具 → 步数受限的阻塞循环 → 每步 sendStatus + 模型调用 + trace → 终止/max-steps。</p>
     * <p><b>手动循环主路径(所有模型)</b>:不挂 {@code .tools()}——LLM 按 ReAct 协议文本输出 Thought + Action,
     * 引擎用共享 {@link ExplicitToolExecutioner}({@link ExplicitToolExecutioner#hasActionLine}/
     * {@link ExplicitToolExecutioner#execute})解析并手动执行工具(反射 + 经 ToolRuntimeAspect 审计 + tracker 证据上报),
     * 把 Observation 回灌下一轮。这是经典 ReAct 的多步可见循环;Spring AI {@code .tools()} 在 {@code .call()}
     * 内部完成工具往返不可见,不符合 ReAct 语义,故不采用。假完成守护由 Task 4 接入({@link AutonomousExecutionTracker}
     * 在 scope 内 setTracker,完成判定处校验工具证据);ChatExecutionResult/trace 的精细化投影由 Task 6 完成
     * ({@link #finalResult} 五字段 + 每步 Thought/Action/Observation 三段式 SSE trace)。</p>
     */
    private ChatExecutionResult runReActLoop(ChatContext ctx, SseEmitter emitter, String requestId) {
        AiProviderService.ActiveChatClient activeClient = ctx.activeClient();
        AssembledContext assembled = ctx.assembled();
        AgentDecision decision = ctx.decision();
        if (requestId == null) requestId = ctx.requestId();
        String riskLevel = decision != null ? decision.riskLevel() : "read";

        // 模型不可用 → 降级(对齐 AutonomousLoop L202-210)
        if (!modelTransportGuardService.isModelCallEnabled(activeClient)) {
            LocalSkillFallbackService.LocalSkillResult fallback =
                    localExecutionSupport.tryFallback(assembled == null ? "" : assembled.question(), true);
            return new ChatExecutionResult(
                    observePrompt(ctx),
                    modelTransportGuardService.disabledModelPlanReason(activeClient),
                    fallback != null ? fallback.executionDetails() : modelTransportGuardService.disabledModelActionReason(activeClient),
                    fallback != null ? fallback.fallbackAnswer() : "",
                    false
            );
        }

        final Object[] tools = toolOrchestrator.selectAutonomousTools(ctx.channel(), ctx.userId(), decision);
        final boolean allowFailover = isSafeToRetry(tools);
        final List<ReActStep> steps = new ArrayList<>();
        String history = "";

        // 执行追踪器 — 每一步关联同一 tracker,累积真实工具调用/副作用证据(对齐
        // AutonomousLoopEngine L221)。Task 4 假完成守护用它校验 write/side_effect/dangerous
        // 任务是否真有工具证据;工具包经 ToolRuntimeAspect 上报到此 tracker。
        AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();

        // 工具执行上下文 scope —— 让 ReAct 手动循环里反射调用的工具方法经 ToolRuntimeAspect 时
        // 能读到 userId/sessionKey/runId 等做权限检查 + 审计 + emit TOOL_* 事件(对齐
        // AutonomousLoopEngine L223-235)。scope 内 setTracker 让工具包上报证据,finally clearTracker。
        ToolExecutionContext toolContext = new ToolExecutionContext(
                assembled == null ? null : assembled.sessionKey(),
                ctx.channel(),
                ctx.userId(),
                requestId,
                "REACT",
                requestId,
                ctx.roleCode()
        );
        try (ToolExecutionContextHolder.Scope scope = ToolExecutionContextHolder.open(toolContext)) {
            // tracker 注册到线程上下文,WorkspaceEditToolPack / ScriptSkillToolPack 的 @Tool
            // 方法经 ToolRuntimeAspect 时上报到这里(对齐 AutonomousLoopEngine L235)。
            ToolExecutionContextHolder.setTracker(tracker);
            for (int stepNo = 1; stepNo <= maxReactSteps; stepNo++) {
                log.info("ReAct 步骤 {}/{}: requestId={}, riskLevel={}, toolsCount={}",
                        stepNo, maxReactSteps, requestId, riskLevel, tools == null ? 0 : tools.length);

                if (emitter != null) {
                    try {
                        sseEventBridge.sendStatus(emitter, "ReAct 步骤 " + stepNo + "/" + maxReactSteps);
                    } catch (Exception e) {
                        log.warn("SSE 进度事件发送失败（可能客户端已断开）: stepNo={}", stepNo);
                    }
                }

                final String systemPrompt = renderReActPrompt(ctx, tools, history, riskLevel);
                ModelCallExecutor.ModelCallResult<String> callResult = modelCallExecutor.executeChat(
                        activeClient,
                        "react-step-" + stepNo,
                        new ModelCallExecutor.ChatRequestContext(
                                requestId,
                                assembled == null ? "" : assembled.sessionKey(),
                                ctx.channel(),
                                ctx.userId()
                        ),
                        allowFailover,
                        client -> {
                            var req = client.chatClient().prompt()
                                    .system(systemPrompt)
                                    .user(TypedContextPromptRenderer.question(ctx));
                            // 手动循环主路径:不挂 .tools()——LLM 按 ReAct 协议文本输出
                            // Thought + Action,循环下方 ExplicitToolExecutioner
                            // 解析并手动执行工具(经典 ReAct 多步可见循环,所有模型一致)。
                            var resp = conversationAdvisorSupport.apply(
                                            req,
                                            assembled == null ? "" : assembled.sessionKey(),
                                            ctx.userId())
                                    .call()
                                    .chatResponse();
                            return new ModelCallExecutor.ChatOperationResult<>(
                                    ModelCallExecutor.extractText(resp), resp);
                        }
                );

                String thought = callResult.value();
                activeClient = callResult.client(); // failover 后更新

                if (!StringUtils.hasText(thought)) {
                    log.warn("ReAct 步骤 {} 模型输出为空,终止循环: requestId={}", stepNo, requestId);
                    break;
                }

                boolean hasToolCall = explicitToolExecutioner.hasActionLine(thought);
                String action = explicitToolExecutioner.describeAction(thought, hasToolCall);
                // 手动循环主路径(所有模型):LLM 文本输出 Thought + Action,引擎解析 Action 后
                // 手动执行工具(经 Spring AOP 代理 → ToolRuntimeAspect 审计 + tracker 证据上报),
                // 把结果作为 Observation 拼入历史进入下一轮。无 Action 行即最终答案,下方终止循环。
                String observation = hasToolCall ? explicitToolExecutioner.execute(thought, tools, requestId) : "";
                steps.add(new ReActStep(thought, action, observation));

                if (emitter != null) {
                    try {
                        // ReAct 三段式 trace — 每步 Thought/Action/Observation 各发一条(模仿
                        // AutonomousLoop sendTrace L294;Task 3 只发 Thought,Task 6 补 Action/Observation)。
                        sseEventBridge.sendTrace(emitter, ctx, "ReAct Thought " + stepNo,
                                "react", "thought", TextUtils.truncate(thought, 200), 0L);
                        sseEventBridge.sendTrace(emitter, ctx, "ReAct Action " + stepNo,
                                "react", "action", TextUtils.truncate(action, 200), 0L);
                        if (StringUtils.hasText(observation)) {
                            sseEventBridge.sendTrace(emitter, ctx, "ReAct Observation " + stepNo,
                                    "react", "observation", TextUtils.truncate(observation, 200), 0L);
                        }
                    } catch (Exception ignored) {}
                }

                history = buildReActHistory(steps);

                // 终止判定:本轮无工具调用(纯最终答案)。read 直接完成;write/side_effect/
                // dangerous 必须校验 tracker 工具证据(假完成守护,对齐 AutonomousLoop L309-345)。
                if (!hasToolCall) {
                    if ("read".equals(riskLevel) || tracker.satisfiesCompletionCondition(riskLevel)) {
                        log.info("ReAct 任务完成: requestId={}, steps={}, riskLevel={}, hasWrite={}, hasCmd={}, hasVerified={}",
                                requestId, stepNo, riskLevel,
                                tracker.hasWriteToolCall(), tracker.hasRunCommandCall(), tracker.hasVerifiedSideEffect());
                        return finalResult(ctx, steps, thought, true);
                    }
                    // 假完成:写/副作用/高风险任务无真实工具证据 → 拒绝,注入提示,继续循环。
                    String rejection = tracker.renderFakeCompletionRejection(riskLevel);
                    log.warn("ReAct 假完成拦截: requestId={}, steps={}, riskLevel={}, hasWrite={}, hasCmd={}, hasVerified={}",
                            requestId, stepNo, riskLevel,
                            tracker.hasWriteToolCall(), tracker.hasRunCommandCall(), tracker.hasVerifiedSideEffect());
                    if (emitter != null) {
                        try {
                            sseEventBridge.sendTrace(emitter, ctx, "ReAct 假完成拦截",
                                    "react", "warning",
                                    "模型声称完成但缺少真实操作证据，继续执行: "
                                            + TextUtils.truncate(rejection, 200), 0L);
                        } catch (Exception ignored) {}
                    }
                    history = buildReActHistory(steps) + "\n\n" + rejection;
                    continue; // 不终止,下一步 prompt 含拒绝提示
                }
            }

            // 达到 maxReactSteps 仍未终止 → 返回当前最佳(降级提示)
            log.info("ReAct 达到最大步数限制: requestId={}, maxSteps={}", requestId, maxReactSteps);
            return finalResult(ctx, steps,
                    "已达 max-react-steps(" + maxReactSteps + "),返回当前最佳答案", true);
        } catch (Exception ex) {
            log.warn("ReAct 循环执行失败: requestId={}, reason={}", requestId, ex.getMessage());
            LocalSkillFallbackService.LocalSkillResult fallback =
                    localExecutionSupport.tryFallback(assembled == null ? "" : assembled.question(), true);
            return new ChatExecutionResult(
                    observePrompt(ctx),
                    "ReAct 循环异常终止: " + chatResponsePolicyService.simplifyFailureReason(ex.getMessage()),
                    buildActionTrace(steps),
                    fallback != null ? fallback.fallbackAnswer() : "",
                    false
            );
        } finally {
            // scope close 只还原 ToolExecutionContext,不清 tracker ThreadLocal;
            // 手动 clear(对齐 AutonomousLoopEngine L387/390,ReAct 用 finally 覆盖 return/异常全路径)。
            ToolExecutionContextHolder.clearTracker();
        }
    }

    /**
     * 构造终态 {@link ChatExecutionResult}(Task 6 细化,模仿 AutonomousLoopEngine L402-411)。
     * <ul>
     *   <li>observe = {@link #observePrompt}</li>
     *   <li>plan = "ReAct 执行 N 步"(stream success trace 复用)</li>
     *   <li>action = {@link #buildActionTrace}(每步 Thought/Action 摘要拼接,模仿 AutonomousLoop actionTrace L287-289)</li>
     *   <li>reflect = 最终答案 + "\n步骤概要:\n" + {@link #buildReActHistory}(模仿 AutonomousLoop reflectContent L402-403,
     *       透明投影 Thought/Action/Observation 三段式,对齐产品愿景 "LLM 透明 / 全可视化")</li>
     *   <li>modelEnabled = 形参(循环正常退出/降级均传 true;模型不可用/异常路径不经过此方法,直接构造 false)</li>
     * </ul>
     */
    private ChatExecutionResult finalResult(ChatContext ctx, List<ReActStep> steps,
                                            String finalAnswer, boolean modelEnabled) {
        String answer = StringUtils.hasText(finalAnswer) ? finalAnswer : "ReAct 循环未产生最终答案。";
        String reflect = answer + "\n步骤概要:\n" + buildReActHistory(steps);
        return new ChatExecutionResult(
                observePrompt(ctx),
                "ReAct 执行 " + steps.size() + " 步",
                buildActionTrace(steps),
                reflect,
                modelEnabled
        );
    }

    /**
     * 把已执行步骤拼成 Action 轨迹(每步一行 Thought/Action 摘要),作为 {@link ChatExecutionResult#action()}。
     * <p>模仿 {@link AutonomousLoopEngine} L287-289 的 {@code actionTrace.add("[Step N]"); actionTrace.add(...)}
     * 结构,差异:ReAct 步骤已结构化为 Thought/Action/Observation 三段,此处把 Thought 摘要 + Action 摘要
     * 拼到一行,既保留"每步干了什么"的诊断价值,又让 action 字段读起来是一条清晰轨迹。</p>
     */
    private String buildActionTrace(List<ReActStep> steps) {
        if (steps == null || steps.isEmpty()) return "";
        StringJoiner sj = new StringJoiner("\n");
        for (int i = 0; i < steps.size(); i++) {
            ReActStep s = steps.get(i);
            sj.add("[Step " + (i + 1) + "] Thought: " + TextUtils.truncate(s.thought(), 200)
                    + " → Action: " + TextUtils.truncate(s.action(), 400));
        }
        return sj.toString();
    }

    /**
     * 从 {@link ChatExecutionResult} 取回用户可见的最终答案(模仿 AutonomousLoopEngine L413-421)。
     * <p>优先 {@code reflect}(Task 6 起含最终答案 + 步骤概要);reflect 空时按 modelEnabled 给兜底语。
     * 模型不可用/异常路径直接构造 ChatExecutionResult,reflect 留空或仅含 fallback 答案,走兜底分支。</p>
     */
    private String resolveFinalAnswer(ChatExecutionResult result) {
        if (StringUtils.hasText(result.reflect())) {
            return result.reflect();
        }
        if (!result.modelEnabled()) {
            return "ReAct 循环执行完成,但模型不可用。";
        }
        return "ReAct 循环执行完成,共 " + result.plan() + "。";
    }

    private String observePrompt(ChatContext ctx) {
        return ctx.assembled() == null ? "" : ctx.assembled().observePrompt();
    }

    private void reportResult(ChatContext context, ChatExecutionResult result, String answer) {
        if (lifecycleObserver == null) return;
        try {
            lifecycleObserver.resultReturned(context, result, answer, Instant.now());
        } catch (RuntimeException ex) {
            log.error("canonical lifecycle projection failed after react persistence, requestId={}",
                    context.requestId(), ex);
        }
    }

    private void releaseLockOnce(ChatContext context, String lockToken, AtomicBoolean lockReleased) {
        if (!lockReleased.compareAndSet(false, true) || lockToken == null) return;
        String sessionKey = context.assembled() == null ? null : context.assembled().sessionKey();
        if (sessionKey != null) {
            chatGuardService.releaseSessionLock(sessionKey, lockToken);
        }
    }

    /**
     * 是否允许同 provider 内 failover——含副作用型工具(写文件/脚本)时禁止重试,避免重复执行。
     * 复制自 {@link AutonomousLoopEngine}(L635-642)。
     */
    private boolean isSafeToRetry(Object[] tools) {
        if (tools == null) return true;
        for (Object tool : tools) {
            if (tool instanceof com.springclaw.tool.pack.WorkspaceEditToolPack) return false;
            if (tool instanceof com.springclaw.tool.pack.ScriptSkillToolPack) return false;
        }
        return true;
    }

    // === Prompt 渲染(Task 2) ===
    // 循环体(Task 3)将调用本节方法拼装每一步的 model prompt。

    /**
     * 一轮 ReAct 推理的结构化轨迹(Thought-Action-Observation 三段式)。
     * <p>Task 3 的循环每执行完一轮填充一条,经 {@link #buildReActHistory} 拼回 prompt。</p>
     */
    public record ReActStep(String thought, String action, String observation) {
    }

    /**
     * 渲染 ReAct 每一步的 model prompt。
     * <p>结构模仿 {@link AutonomousLoopEngine#renderAutonomousPrompt}:注入前缀 + 用户问题 +
     * 工具列表 + 完成规则 + 历史,差异在协议——ReAct 要求模型按 Thought/Action/Observation 输出,
     * 引擎解析 Action 调工具,把 Observation 回灌下一轮。</p>
     */
    String renderReActPrompt(ChatContext ctx, Object[] tools, String history, String riskLevel) {
        String template = """
                {{INJECTION}}你是 ReAct Agent,按 Thought-Action-Observation 循环推理。

                # 用户问题
                {{QUESTION}}

                # 推理协议
                每一步:先输出 Thought(简短推理:分析现状、决定下一步),再决定:
                  - 调用一个工具 → 输出一行 Action: 工具名(参数名="值", ...),例如 Action: search(query="Spring AI")
                    工具会被引擎执行,结果作为下一步的 Observation;若工具无参数则写 Action: 工具名()
                  - 或已得到最终答案 → 直接输出最终答案(不输出 Action 视为完成)

                # 可用工具
                {{TOOLS}}

                # 完成规则
                {{COMPLETION_RULE}}

                # 历史步骤
                {{HISTORY}}

                # 现在输出下一步的 Thought(与 Action 或最终答案):
                """;
        return template
                .replace("{{INJECTION}}", TypedContextPromptRenderer.promptPrefix(ctx))
                .replace("{{QUESTION}}", StringUtils.hasText(TypedContextPromptRenderer.question(ctx))
                        ? TypedContextPromptRenderer.question(ctx).trim() : "")
                .replace("{{TOOLS}}", ToolReflectionSupport.renderToolList(tools))
                .replace("{{COMPLETION_RULE}}", renderReActCompletionRule(riskLevel))
                .replace("{{HISTORY}}", StringUtils.hasText(history) ? history : "（第一轮，暂无历史）");
    }

    /**
     * 把已执行的 ReAct 步骤拼成结构化历史(Step N + Thought/Action/Observation 三段式)。
     * <p>空历史返回第一轮提示,供首轮 prompt 使用。每段经 {@link TextUtils#truncate} 截断,
     * 与 {@link AutonomousLoopEngine#buildStepHistory} 的 400 字符上限一致。</p>
     */
    String buildReActHistory(List<ReActStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "（第一轮，暂无历史）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            ReActStep step = steps.get(i);
            sb.append("Step ").append(i + 1).append(":\n")
                    .append("Thought: ").append(TextUtils.truncate(step.thought(), 400)).append("\n")
                    .append("Action: ").append(TextUtils.truncate(step.action(), 400)).append("\n")
                    .append("Observation: ").append(TextUtils.truncate(step.observation(), 400)).append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * 按 riskLevel 渲染 ReAct 语境的完成规则。
     * <p>模仿 {@link AutonomousLoopEngine#renderCompletionRule} 的 riskLevel 分级,但以
     * ReAct 的 "Thought/Action/Observation" 语境表达:read 可直接给最终答案,写/命令/高风险
     * 必须通过 Action 实际调用对应工具并观察 Observation 后才允许完成。</p>
     */
    private String renderReActCompletionRule(String riskLevel) {
        if ("read".equals(riskLevel)) {
            return "当前是只读分析任务:你可以在任意一步直接给出最终答案(不输出 Action 即视为完成)。";
        }
        if ("write".equals(riskLevel)) {
            return "当前是写操作任务(创建文件、修改代码等)。\n"
                    + "⚠️ 完成条件:你必须通过 Action 实际调用写工具(如 workspaceWriteFile/workspaceApplyPatch),"
                    + "并观察 Observation 确认修改生效后再给出最终答案。\n"
                    + "只用 Thought 描述修改而不输出 Action 调用工具,系统会拒绝完成并要求你继续。";
        }
        if ("side_effect".equals(riskLevel)) {
            return "当前是命令执行任务(运行测试、编译检查等)。\n"
                    + "⚠️ 完成条件:你必须通过 Action 实际调用命令工具(如 workspaceRunCommand),"
                    + "并观察 Observation 后再给出最终答案。\n"
                    + "只用 Thought 描述而不实际调用工具,系统会拒绝完成并要求你继续。";
        }
        if ("dangerous".equals(riskLevel)) {
            return "当前是高风险操作任务。\n"
                    + "⚠️ 完成条件:你必须通过 Action 实际执行高风险工具,并用 Observation 验证结果后再给出最终答案。\n"
                    + "只用 Thought 描述而不实际调用工具,系统会拒绝完成并要求你继续。";
        }
        return "请通过 Action 实际执行操作、观察 Observation 后,再给出最终答案。";
    }
}
