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
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reflexion 范式引擎 — 执行→反思→改进重试(+ memory 累积)。
 * <p>
 * 与 {@link ReActEngine}(Thought-Action-Observation 边推理边行动)和 {@link PlanExecuteEngine}(先规划再执行)
 * 不同,Reflexion 把"反思"显式化为一等阶段:Actor 产出答案 → Evaluator 评估打分/指出缺陷 →
 * Reflector 据反馈生成改进意见注入下一轮 Actor,直到答案合格或达 {@code max-reflections}——
 * 把"自我纠错"作为循环骨架,适合需要多轮打磨的质量敏感任务。
 * </p>
 * <p>
 * <b>当前状态:RX-T4 结果/trace 精细化(在 RX-T3 循环 + 流式 SSE 之上)——</b>
 * <ul>
 *   <li>{@code paradigm()} 声明 {@link AgentParadigm#REFLECTION}(配合
 *       {@link AgentParadigm#isImplemented()} 与 {@code EngineSelector.LEGACY_RANK} 登记);</li>
 *   <li>{@code supports()} 仅在 {@code ctx.paradigm() == REFLECTION} 时为真;</li>
 *   <li>{@link #renderAttemptPrompt}(RX-T2):Actor 单次尝试 prompt——注入问题 + 工具清单 +
 *       前轮反思 {{MEMORY}},要求 LLM 输出 Thought+Action(ReAct 风格协议);</li>
 *   <li>{@link #renderReflectionPrompt}(RX-T2):Evaluator/Reflector prompt——注入本次尝试 +
 *       历史 memory + {@link BeanOutputConverter} format,要求 LLM 输出 {@link ReflectionResult}
 *       (success/critique/lesson);{@link #reflectionOutputConverter} 复用 PlanExecute/OparLoop 的
 *       BeanOutputConverter 模式,RX-T3 经 {@code .responseEntity(ReflectionResult.class)} 取回实体;</li>
 *   <li>{@code execute()}/{@code stream()}(<b>RX-T3 填充</b>)跑 {@link #runReflexionLoop}:Actor→Evaluator/Reflector
 *       循环——每次尝试 {@link #callLlmForAttempt}(LLM 据 memory → Thought+Action 文本 → 共享
 *       {@link ExplicitToolExecutioner} 手动执行工具 → Observation)→ {@link #callReflection}(BeanOutputConverter
 *       结构化自评);success(且 read 或 {@link AutonomousExecutionTracker} 通过工具证据校验)即收敛,
 *       否则把 lesson/rejection 累积进 memory 注入下一轮;{@code max-reflections} 兜底。
 *       假完成守护复用 AutonomousLoop/ReAct/Plan-Execute 模式;流式 SSE 生命周期(对齐 PlanExecuteEngine):
 *       trace(started) → 循环 → answerChunks → persist(TERMINAL_RESULT) → reportResult → trace(success) →
 *       releaseLockOnce → completeEmitter;异常委托 {@code fallbackHandler}。</li>
 *   <li>{@link #finalResult} <b>RX-T4 五字段精细化版</b>(observe / plan=summary / action=attemptTrace 轨迹 /
 *       reflect=raw 答案+累积 memory / modelEnabled)+ {@link #resolveFinalAnswer} 优先 reflect 兜底;
 *       <b>M1 修复</b>:reflect 用 Actor 的 raw 输出(不含 Thought/Action/Observation 标签),attemptTrace 只进 action。</li>
 * </ul>
 * 构造函数复用 {@link ReActEngine}/{@link PlanExecuteEngine} 的 11 bean 依赖 + {@link ExplicitToolExecutioner}
 * (RX-T3 反思循环手动执行工具时复用)+ {@code max-reflections} 配置(clamp 到 [1,5])。
 * </p>
 */
@Service
public class ReflexionEngine implements AgentEngine.StreamableAgentEngine {

    private static final Logger log = LoggerFactory.getLogger(ReflexionEngine.class);

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
    private final int maxReflections;

    /**
     * Evaluator/Reflector 阶段结构化输出转换器——把 LLM 自评输出反序列化为 {@link ReflectionResult}。
     * <p>对齐 {@link PlanExecuteEngine#planOutputConverter} / {@link OparLoopEngine#planOutputConverter} 的
     * BeanOutputConverter 模式;{@link #renderReflectionPrompt} 注入 {@code getFormat()} 强制结构化输出,
     * RX-T3 反思循环经 {@code .responseEntity(ReflectionResult.class)} 取回实体。</p>
     */
    private final BeanOutputConverter<ReflectionResult> reflectionOutputConverter =
            new BeanOutputConverter<>(ReflectionResult.class);

    public ReflexionEngine(AiProviderService aiProviderService,
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
                           @Value("${springclaw.chat.max-reflections:3}") int maxReflections) {
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
        this.maxReflections = Math.max(1, Math.min(maxReflections, 5));
    }

    @Override
    public String name() {
        return "reflexion-loop";
    }

    @Override
    public AgentParadigm paradigm() {
        return AgentParadigm.REFLECTION;
    }

    @Override
    public int priority() {
        return 8;
    }

    @Override
    public boolean supports(ChatContext ctx) {
        return ctx != null && ctx.paradigm() == AgentParadigm.REFLECTION;
    }

    /**
     * 阻塞执行入口——内部跑 {@link #runReflexionLoop}(无 emitter)。
     * <p>结构对齐 {@link ReActEngine#execute} / {@link PlanExecuteEngine#execute}:直接进入
     * Actor→Evaluator/Reflector 循环,返回 {@link ChatExecutionResult}。</p>
     */
    @Override
    public ChatExecutionResult execute(ChatContext ctx, AgentEngine.FallbackResponder fallbackResponder) {
        return runReflexionLoop(ctx, null, ctx.requestId());
    }

    /**
     * 流式执行入口——管理完整 SSE 生命周期,内部跑 {@link #runReflexionLoop}。
     * <p>结构对齐 {@link PlanExecuteEngine#stream} / {@link ReActEngine#stream}:trace(started) →
     * Actor→反思循环 → 发送最终答案 → persist(TERMINAL_RESULT) → reportResult → trace(success) →
     * releaseLockOnce → completeEmitter;异常委托 {@code fallbackHandler}。</p>
     */
    @Override
    public Disposable stream(ChatContext context,
                             SseEmitter emitter,
                             String lockToken,
                             AtomicBoolean lockReleased,
                             AtomicReference<Disposable> disposableRef,
                             AgentEngine.OnStreamFailure fallbackHandler) {
        try {
            sseEventBridge.sendTrace(emitter, context, "Reflexion 循环", "reflexion", "started",
                    "进入 Reflexion 执行→反思→改进重试循环。", 0L);
            sseEventBridge.sendStatus(emitter, "Reflexion 循环执行中");

            ChatExecutionResult result = runReflexionLoop(context, emitter, context.requestId());

            String finalAnswer = resolveFinalAnswer(result);
            sseEventBridge.sendAnswerChunks(emitter, finalAnswer);
            chatResultPersister.persist(context, finalAnswer, result, ChatPersistenceIntent.TERMINAL_RESULT);
            reportResult(context, result, finalAnswer);

            sseEventBridge.sendTrace(emitter, context, "Reflexion 循环", "reflexion", "success",
                    "Reflexion 循环执行完成(" + result.plan() + ")。", 0L);
            sseEventBridge.sendTrace(emitter, context, "完成", "final", "success",
                    "已生成最终回答。", 0L);
            releaseLockOnce(context, lockToken, lockReleased);
            sseEventBridge.completeEmitter(emitter);
        } catch (Exception ex) {
            log.warn("Reflexion SSE 执行失败: sessionKey={}, reason={}",
                    context.assembled() == null ? "?" : context.assembled().sessionKey(), ex.getMessage());
            try {
                String simplifiedReason = chatResponsePolicyService.simplifyFailureReason(ex.getMessage());
                sseEventBridge.sendTrace(emitter, context, "Reflexion 循环", "reflexion", "failed",
                        simplifiedReason, 0L);
            } catch (Exception ignored) {}
            fallbackHandler.handle(context, ex, emitter, lockToken, lockReleased);
        }
        return null;
    }

    // === RX-T3: Actor→Evaluator/Reflector 循环(memory 累积 + 假完成守护 + 流式 SSE) ===

    /**
     * Reflexion 核心循环——Actor 产出一次尝试 → Evaluator/Reflector 自评 → 据 lesson/rejection 累积 memory
     * → 进入下一轮,直到反思 success(且 read 或 tracker 通过工具证据校验)或达 {@code max-reflections}。
     * <ul>
     *   <li>每次尝试:{@link #callLlmForAttempt}(LLM 据 memory → Thought+Action 文本)→ 经共享
     *       {@link ExplicitToolExecutioner} 手动执行工具得 Observation,拼成 attemptTrace;</li>
     *   <li>{@link #callReflection}:BeanOutputConverter 结构化自评 → {@link ReflectionResult}(success/lesson);</li>
     *   <li>success + read 直接收敛;success + write/side_effect/dangerous 必须校验
     *       {@link AutonomousExecutionTracker} 工具证据,无证据则假完成拦截(rejection 注入 memory,继续);</li>
     *   <li>未 success → lesson 累积进 memory 注入下一轮 Actor(Reflexion 的"自我纠错"闭环);</li>
     *   <li>{@code max-reflections} 兜底返回当前最佳尝试。</li>
     * </ul>
     * <p>结构对齐 {@link PlanExecuteEngine#runPlanExecute} / {@link ReActEngine#runReActLoop}:模型不可用降级 →
     * 选工具 → tracker + scope → 循环 → 假完成守护(参考 AutonomousLoop L317-345)。差异:Reflexion 的循环骨架是
     * "Actor→Reflector 显式反思 + memory 累积",而非 ReAct 的 Thought-Action-Observation 或 Plan-Execute 的
     * Plan→Execute→Replan;Reflector 的 success + tracker 证据共同决定收敛。</p>
     */
    private ChatExecutionResult runReflexionLoop(ChatContext ctx, SseEmitter emitter, String requestId) {
        AiProviderService.ActiveChatClient activeClient = ctx.activeClient();
        AssembledContext assembled = ctx.assembled();
        AgentDecision decision = ctx.decision();
        if (requestId == null) requestId = ctx.requestId();
        String riskLevel = decision != null ? decision.riskLevel() : "read";

        // 模型不可用 → 降级(对齐 PlanExecuteEngine.runPlanExecute / ReActEngine.runReActLoop)
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
        // 执行追踪器 — 复用同一 tracker 累积真实工具证据(对齐 PlanExecute/ReAct)。假完成守护用它校验
        // write/side_effect/dangerous 任务是否真有工具证据;工具包经 ToolRuntimeAspect 上报到此 tracker。
        final AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
        // 工具执行上下文 scope —— 让 Actor 反射调用的工具方法经 ToolRuntimeAspect 时能读到
        // userId/sessionKey/runId 等做权限检查 + 审计 + emit TOOL_* 事件(对齐 PlanExecute/ReAct)。
        ToolExecutionContext toolContext = new ToolExecutionContext(
                assembled == null ? null : assembled.sessionKey(),
                ctx.channel(),
                ctx.userId(),
                requestId,
                "REFLECTION",
                requestId,
                ctx.roleCode()
        );

        String memory = "";
        String lastAttempt = "";
        String lastAnswer = "";

        try (ToolExecutionContextHolder.Scope scope = ToolExecutionContextHolder.open(toolContext)) {
            ToolExecutionContextHolder.setTracker(tracker);
            for (int attempt = 1; attempt <= maxReflections; attempt++) {
                log.info("Reflexion 尝试 {}/{}: requestId={}, riskLevel={}, toolsCount={}",
                        attempt, maxReflections, requestId, riskLevel, tools == null ? 0 : tools.length);
                if (emitter != null) {
                    try {
                        sseEventBridge.sendStatus(emitter, "Reflexion 尝试 " + attempt + "/" + maxReflections);
                    } catch (Exception e) {
                        log.warn("SSE 进度事件发送失败(可能客户端已断开): attempt={}", attempt);
                    }
                }

                // Actor:LLM 据 memory → Thought+Action 文本 → ExplicitToolExecutioner 手动执行工具 → Observation
                ModelCallExecutor.ModelCallResult<String> attemptResult = callLlmForAttempt(
                        ctx, memory, tools, activeClient, requestId, allowFailover);
                activeClient = attemptResult.client(); // failover 后更新
                String thought = attemptResult.value();
                boolean hasAction = explicitToolExecutioner.hasActionLine(thought);
                String observation = hasAction
                        ? explicitToolExecutioner.execute(thought, tools, requestId) : "";
                String attemptTrace = "Thought: " + TextUtils.truncate(thought, 400)
                        + "\nAction: " + explicitToolExecutioner.describeAction(thought, hasAction)
                        + "\nObservation: " + TextUtils.truncate(observation, 400);
                lastAttempt = attemptTrace;
                // RX-T4 M1:raw 最终答案(用户可读的 clean 文本,不含 attemptTrace 的 Thought/Action/Observation 标签)。
                // Actor 调工具 → 用 Observation(工具结果)作本轮答案;Actor 纯文本作答(无 Action)→ 用 thought 原文。
                // 只进 reflect(resolveFinalAnswer 发给用户);attemptTrace 只进 action(轨迹)。
                lastAnswer = hasAction ? observation : thought;

                if (emitter != null) {
                    try {
                        // Actor 三段式 trace — Thought/Action/Observation(模仿 ReAct/PlanExecute)
                        sseEventBridge.sendTrace(emitter, ctx, "Reflexion Thought " + attempt,
                                "reflexion", "thought", TextUtils.truncate(thought, 200), 0L);
                        sseEventBridge.sendTrace(emitter, ctx, "Reflexion Action " + attempt,
                                "reflexion", "action",
                                TextUtils.truncate(explicitToolExecutioner.describeAction(thought, hasAction), 200), 0L);
                        if (StringUtils.hasText(observation)) {
                            sseEventBridge.sendTrace(emitter, ctx, "Reflexion Observation " + attempt,
                                    "reflexion", "observation", TextUtils.truncate(observation, 200), 0L);
                        }
                    } catch (Exception ignored) {}
                }

                // Evaluator/Reflector:自评本次尝试(BeanOutputConverter 结构化输出)
                ReflectionResult reflection = callReflection(ctx, attemptTrace, memory, activeClient, requestId, allowFailover);
                if (emitter != null) {
                    try {
                        sseEventBridge.sendTrace(emitter, ctx, "Reflexion 反思 " + attempt,
                                "reflexion", "reflect",
                                "success=" + reflection.success()
                                        + " lesson=" + TextUtils.truncate(reflection.lesson(), 200), 0L);
                    } catch (Exception ignored) {}
                }

                // 终止判定:read 直接完成;write/side_effect/dangerous 必须校验 tracker 工具证据(假完成守护)
                if (reflection.success()) {
                    if ("read".equals(riskLevel) || tracker.satisfiesCompletionCondition(riskLevel)) {
                        log.info("Reflexion 任务完成: requestId={}, attempt={}, riskLevel={}, hasWrite={}, hasCmd={}, hasVerified={}",
                                requestId, attempt, riskLevel,
                                tracker.hasWriteToolCall(), tracker.hasRunCommandCall(), tracker.hasVerifiedSideEffect());
                        return finalResult(ctx, attemptTrace, lastAnswer, memory,
                                "Reflexion: " + attempt + " 次尝试后成功", true);
                    }
                    // 假完成:反思 success 但无真实工具证据 → rejection 注入 memory,继续(对齐 ReAct/PlanExecute)
                    String rejection = tracker.renderFakeCompletionRejection(riskLevel);
                    log.warn("Reflexion 假完成拦截: requestId={}, attempt={}, riskLevel={}, hasWrite={}, hasCmd={}",
                            requestId, attempt, riskLevel, tracker.hasWriteToolCall(), tracker.hasRunCommandCall());
                    memory += "\n尝试 " + attempt + " 反思: 声称完成但无工具证据。" + rejection;
                    if (emitter != null) {
                        try {
                            sseEventBridge.sendTrace(emitter, ctx, "Reflexion 假完成拦截",
                                    "reflexion", "warning",
                                    "反思声称完成但缺少真实工具证据,继续尝试: "
                                            + TextUtils.truncate(rejection, 200), 0L);
                        } catch (Exception ignored) {}
                    }
                    continue;
                }

                // 未完成 → lesson 累积进 memory,注入下一轮 Actor(Reflexion 自我纠错闭环)
                memory += "\n尝试 " + attempt + " 反思: " + reflection.lesson();
            }

            // max-reflections 兜底:返回当前最佳尝试(对齐 PlanExecute max-replan / ReAct max-steps 兜底)
            log.info("Reflexion 达到最大反思次数: requestId={}, maxReflections={}", requestId, maxReflections);
            return finalResult(ctx, lastAttempt, lastAnswer, memory,
                    "已达 max-reflections(" + maxReflections + "),返回当前最佳尝试", true);
        } catch (Exception ex) {
            log.warn("Reflexion 循环执行失败: requestId={}, reason={}", requestId, ex.getMessage());
            LocalSkillFallbackService.LocalSkillResult fallback =
                    localExecutionSupport.tryFallback(assembled == null ? "" : assembled.question(), true);
            return new ChatExecutionResult(
                    observePrompt(ctx),
                    "Reflexion 异常终止: " + chatResponsePolicyService.simplifyFailureReason(ex.getMessage()),
                    StringUtils.hasText(lastAttempt) ? TextUtils.truncate(lastAttempt, 600) : "(无尝试输出)",
                    fallback != null ? fallback.fallbackAnswer() : "",
                    false
            );
        } finally {
            // scope close 只还原 ToolExecutionContext,不清 tracker ThreadLocal;手动 clear(对齐 PlanExecute/ReAct)。
            ToolExecutionContextHolder.clearTracker();
        }
    }

    /**
     * Actor 阶段的 LLM 调用——不挂 {@code .tools()},系统 prompt({@link #renderAttemptPrompt})要求 LLM
     * 据当前 memory → 输出 Thought+Action 文本(或直接答案)。返回 {@link ModelCallExecutor.ModelCallResult}
     * 让调用方更新 failover 后的 client(对齐 PlanExecuteEngine.callLlmForStep / ReActEngine 的 LLM 调用模式)。
     * 经 {@link ExplicitToolExecutioner} 在循环下方解析 Action 并手动执行工具。
     */
    private ModelCallExecutor.ModelCallResult<String> callLlmForAttempt(ChatContext ctx,
                                                                         String memory,
                                                                         Object[] tools,
                                                                         AiProviderService.ActiveChatClient client,
                                                                         String requestId,
                                                                         boolean allowFailover) throws Exception {
        AssembledContext assembled = ctx.assembled();
        String sessionKey = assembled == null ? "" : assembled.sessionKey();
        String systemPrompt = renderAttemptPrompt(ctx, tools, memory);
        return modelCallExecutor.executeChat(
                client,
                "reflexion-attempt",
                new ModelCallExecutor.ChatRequestContext(requestId, sessionKey, ctx.channel(), ctx.userId()),
                allowFailover,
                c -> {
                    // 手动循环主路径:不挂 .tools()——LLM 文本输出 Thought + Action,循环下方
                    // ExplicitToolExecutioner 解析并手动执行工具(对齐 ReAct/PlanExecute 的 LLM 调用模式)。
                    var resp = conversationAdvisorSupport.apply(
                                    c.chatClient().prompt()
                                            .system(systemPrompt)
                                            .user(TypedContextPromptRenderer.question(ctx)),
                                    sessionKey,
                                    ctx.userId())
                            .call()
                            .chatResponse();
                    return new ModelCallExecutor.ChatOperationResult<>(
                            ModelCallExecutor.extractText(resp), resp);
                }
        );
    }

    /**
     * Evaluator/Reflector 阶段的 LLM 调用——经 BeanOutputConverter 结构化产出 {@link ReflectionResult}
     * (对齐 PlanExecuteEngine.runPlan 的 {@code .responseEntity(Plan.class)} 模式)。异常降级为
     * {@code ReflectionResult(false, "反思失败: ...", "")}——不向调用方抛,保持循环推进(下一轮 Actor 据新 memory 继续)。
     */
    private ReflectionResult callReflection(ChatContext ctx,
                                             String attempt,
                                             String memory,
                                             AiProviderService.ActiveChatClient client,
                                             String requestId,
                                             boolean allowFailover) {
        AssembledContext assembled = ctx.assembled();
        String sessionKey = assembled == null ? "" : assembled.sessionKey();
        try {
            String systemPrompt = renderReflectionPrompt(ctx, attempt, memory);
            ModelCallExecutor.ModelCallResult<ReflectionResult> result = modelCallExecutor.executeChat(
                    client,
                    "reflexion-reflect",
                    new ModelCallExecutor.ChatRequestContext(requestId, sessionKey, ctx.channel(), ctx.userId()),
                    allowFailover,
                    c -> {
                        var response = conversationAdvisorSupport.apply(
                                        c.chatClient().prompt()
                                                .system(systemPrompt)
                                                .user(TypedContextPromptRenderer.question(ctx)),
                                        sessionKey,
                                        ctx.userId())
                                .call()
                                .responseEntity(ReflectionResult.class);
                        return new ModelCallExecutor.ChatOperationResult<>(
                                response.entity(), response.response());
                    }
            );
            ReflectionResult reflection = result.value();
            return reflection != null ? reflection
                    : new ReflectionResult(false, "反思返回空结果", "");
        } catch (Exception ex) {
            log.warn("Reflexion 反思失败,降级为未完成继续尝试: requestId={}, reason={}",
                    requestId, ex.getMessage());
            return new ReflectionResult(false, "反思失败: " + ex.getMessage(), "");
        }
    }

    /**
     * 构造终态 {@link ChatExecutionResult}(<b>RX-T4 精细化版</b>,对齐 PlanExecute/ReAct finalResult 五字段)。
     * <ul>
     *   <li>observe = {@link #observePrompt}</li>
     *   <li>plan = summary 直进(如 "Reflexion: N 次尝试后成功" / "已达 max-reflections(N),返回当前最佳尝试");
     *       stream success trace 复用作完成摘要</li>
     *   <li>action = lastAttempt(末次尝试 attemptTrace,Thought/Action/Observation 轨迹)</li>
     *   <li>reflect = lastAnswer(raw 最终答案,<b>M1 修复</b>:不含 Thought/Action/Observation 标签)
     *       + "\n累积反思:\n" + memory(前几轮 Reflector 的 lesson)</li>
     *   <li>modelEnabled = 形参</li>
     * </ul>
     * <p><b>M1 修复(Task 3 review)</b>:Task 3 把 attemptTrace(Thought/Action/Observation 标签)塞进 reflect,
     * {@link #resolveFinalAnswer} 又把 reflect 当最终答案发给用户(看到 verbose 标签)。本版拆开:
     * attemptTrace 只进 action(轨迹),reflect 用 {@code lastAnswer}(Actor 末次输出的 clean 答案——
     * 调工具时取 Observation,纯文本作答时取 thought 原文)+ 累积 memory。对齐
     * {@link PlanExecuteEngine#finalResult}(answer + 步骤概要)与 {@link ReActEngine#finalResult}(finalAnswer + 步骤概要)。</p>
     */
    private ChatExecutionResult finalResult(ChatContext ctx, String lastAttempt, String lastAnswer,
                                            String memory, String summary, boolean modelEnabled) {
        String action = StringUtils.hasText(lastAttempt)
                ? TextUtils.truncate(lastAttempt, 600) : "(无尝试输出)";
        String answer = StringUtils.hasText(lastAnswer) ? TextUtils.truncate(lastAnswer, 800) : "(无最终答案)";
        String reflect = answer + "\n累积反思:\n"
                + (StringUtils.hasText(memory) ? TextUtils.truncate(memory, 800) : "（无）");
        return new ChatExecutionResult(
                observePrompt(ctx),
                summary,
                action,
                reflect,
                modelEnabled
        );
    }

    /**
     * 从 {@link ChatExecutionResult} 取回用户可见的最终答案(模仿 PlanExecute/ReAct resolveFinalAnswer)。
     * <p>优先 {@code reflect}(RX-T4 起 = raw 最终答案 + 累积反思 memory);reflect 空时按 modelEnabled 给兜底语。
     * <b>M1</b>:reflect 是 raw 答案(不含 attemptTrace 的 Thought/Action/Observation 标签),用户不会再看到 verbose 轨迹。</p>
     */
    private String resolveFinalAnswer(ChatExecutionResult result) {
        if (StringUtils.hasText(result.reflect())) {
            return result.reflect();
        }
        if (!result.modelEnabled()) {
            return "Reflexion 循环执行完成,但模型不可用。";
        }
        return "Reflexion 循环执行完成,共 " + result.plan() + "。";
    }

    private void reportResult(ChatContext context, ChatExecutionResult result, String answer) {
        if (lifecycleObserver == null) return;
        try {
            lifecycleObserver.resultReturned(context, result, answer, Instant.now());
        } catch (RuntimeException ex) {
            log.error("canonical lifecycle projection failed after reflexion persistence, requestId={}",
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
     * 复制自 {@link PlanExecuteEngine} / {@link ReActEngine}。
     */
    private boolean isSafeToRetry(Object[] tools) {
        if (tools == null) return true;
        for (Object tool : tools) {
            if (tool instanceof com.springclaw.tool.pack.WorkspaceEditToolPack) return false;
            if (tool instanceof com.springclaw.tool.pack.ScriptSkillToolPack) return false;
        }
        return true;
    }

    private String observePrompt(ChatContext ctx) {
        return ctx.assembled() == null ? "" : ctx.assembled().observePrompt();
    }

    // === RX-T2: Actor / Evaluator-Reflector prompt + ReflectionResult(BeanOutputConverter)===

    /**
     * Evaluator/Reflector 阶段产出的自评结果——Actor 完成一次尝试后,Reflector 据此判定是否收敛。
     * <ul>
     *   <li>{@code success}:本次尝试是否已达成用户目标(为真则循环收敛,不再重试);</li>
     *   <li>{@code critique}:对本次尝试的评估(哪里不足、缺什么证据、错在哪);</li>
     *   <li>{@code lesson}:可操作的改进意见——RX-T3 把它累积进 memory,注入下一轮 {@link #renderAttemptPrompt}。</li>
     * </ul>
     * <p>对齐 Reflexion 范式(Actor→Evaluator→Reflector + memory 累积);record 形态让
     * {@link BeanOutputConverter} 直接 round-trip(参考 {@link PlanExecuteEngine.Plan} wrapper)。</p>
     */
    public record ReflectionResult(boolean success, String critique, String lesson) {
    }

    /**
     * 渲染 Actor 阶段(单次尝试)的 system prompt——要求 LLM 基于问题 + 前轮反思 memory 输出 Thought+Action。
     * <p>结构模仿 {@link ReActEngine#renderReActPrompt} / {@link PlanExecuteEngine#renderExecutePrompt}:
     * {{INJECTION}}({@link TypedContextPromptRenderer#promptPrefix}) + {{QUESTION}}
     * ({@link TypedContextPromptRenderer#question}) + {{TOOLS}}({@link ToolReflectionSupport#renderToolList})
     * + {{MEMORY}}(前几轮 Reflector 的反思意见,首轮为空)。差异:Reflexion 的 Actor 是"带 memory 的单次
     * 尝试"——不边推理边循环(ReAct)、不由 Planner 拆步(Plan-Execute),而是把"自我纠错"交给 Reflector
     * 阶段({@link #renderReflectionPrompt}),Actor 只管基于最新 memory 跑一次。</p>
     * <p>memory 为空时注入"（首次尝试，暂无反思）"占位语,避免空段读起来突兀(对齐 ReAct 的
     * "（第一轮，暂无历史）" 模式)。</p>
     */
    String renderAttemptPrompt(ChatContext ctx, Object[] tools, String memory) {
        String template = """
                {{INJECTION}}你是 Reflexion Agent 的 Actor,请基于用户问题与前轮反思,进行本次尝试。

                # 用户问题
                {{QUESTION}}

                # 可用工具
                {{TOOLS}}

                # 历史反思(前几轮 Reflector 的自我纠错意见,首轮为空)
                {{MEMORY}}

                # 执行协议
                先输出 Thought(简短分析:如何解决、是否需要调工具),再:
                  - 若需要调用工具 → 输出一行 Action: 工具名(参数名="值", ...),例如 Action: search(query="Spring AI")
                    工具会被引擎执行,结果作为本次尝试的 Observation;若工具无参数则写 Action: 工具名()
                  - 若已能直接作答(无需工具)→ 直接输出本次尝试的答案文本(不输出 Action 视为本轮完成)

                # 现在输出本次尝试的 Thought(与 Action 或答案):
                """;
        return template
                .replace("{{INJECTION}}", TypedContextPromptRenderer.promptPrefix(ctx))
                .replace("{{QUESTION}}", StringUtils.hasText(TypedContextPromptRenderer.question(ctx))
                        ? TypedContextPromptRenderer.question(ctx).trim() : "")
                .replace("{{TOOLS}}", ToolReflectionSupport.renderToolList(tools))
                .replace("{{MEMORY}}", StringUtils.hasText(memory) ? memory : "（首次尝试，暂无反思）");
    }

    /**
     * 渲染 Evaluator/Reflector 阶段的 system prompt——要求 LLM 自评本次尝试是否达成目标,
     * 并输出 {@link ReflectionResult}(success/critique/lesson)结构化 JSON。
     * <p>结构模仿 {@link PlanExecuteEngine#renderPlanPrompt}:{{INJECTION}} + {{QUESTION}} +
     * {{ATTEMPT}}(本次 Actor 的 Thought/Action/Observation 或答案)+ {{HISTORY}}(前几轮 Reflector
     * 的 memory,首轮为空)+ {{FORMAT}}({@link #reflectionOutputConverter}.{@link BeanOutputConverter#getFormat()
     * getFormat()} 注入,强制结构化输出)。整体作 system prompt,user 消息仍由循环({@link ModelCallExecutor}
     * 调用处)取自 {@link TypedContextPromptRenderer#question}(对齐 PlanExecuteEngine.runPlan)。</p>
     * <p>Reflector 的 {@code lesson} 会被 RX-T3 累积进 memory,注入下一轮 {@link #renderAttemptPrompt},
     * 形成"反思→改进重试"的闭环——这是 Reflexion 区别于 ReAct/Plan-Execute 的核心。</p>
     */
    String renderReflectionPrompt(ChatContext ctx, String attempt, String memory) {
        String template = """
                {{INJECTION}}你是 Reflexion Agent 的 Evaluator/Reflector,请自评本次尝试并产出结构化反思。

                # 用户问题
                {{QUESTION}}

                # 本次尝试(Actor 的 Thought/Action/Observation 或答案)
                {{ATTEMPT}}

                # 历史反思(前几轮 Reflector 的自我纠错意见,首轮为空)
                {{HISTORY}}

                # 评估要求
                1) 判断本次尝试是否已充分回答用户问题(success=true 表示已达成,无需再试);
                2) 若未达成,在 critique 指出缺陷(哪里不足、缺什么证据、错在哪里);
                3) 在 lesson 给出可操作的改进意见(供下一轮 Actor 注入,应具体、可执行);
                4) 输出必须严格遵循下面的格式说明,不要附加任何额外文本。

                # 输出格式说明
                {{FORMAT}}
                """;
        return template
                .replace("{{INJECTION}}", TypedContextPromptRenderer.promptPrefix(ctx))
                .replace("{{QUESTION}}", StringUtils.hasText(TypedContextPromptRenderer.question(ctx))
                        ? TypedContextPromptRenderer.question(ctx).trim() : "")
                .replace("{{ATTEMPT}}", StringUtils.hasText(attempt) ? attempt : "（本次尝试无输出）")
                .replace("{{HISTORY}}", StringUtils.hasText(memory) ? memory : "（首次尝试，暂无反思）")
                .replace("{{FORMAT}}", reflectionOutputConverter.getFormat());
    }
}
