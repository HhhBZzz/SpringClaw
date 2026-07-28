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
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Multi-Agent 范式引擎 — Coordinator→Worker 并行协作 + 结果聚合。
 * <p>
 * 与 {@link ReActEngine}(单 Agent 边推理边行动)、{@link PlanExecuteEngine}(单 Agent 先规划再执行)、
 * {@link ReflexionEngine}(单 Actor→Reflector 自我纠错)不同,Multi-Agent 把"多角色协作"显式化:
 * Coordinator 把用户问题 decompose 成若干子任务 → 并行分派给 Worker(每个 Worker 跑一次工具增强推理)→
 * Coordinator aggregate Worker 结果 → 必要时再 decompose(子任务未充分覆盖),直到答案合格或达
 * {@code max-agents}——把"分工与汇总"作为循环骨架,适合需要多视角/可拆解的复杂任务。
 * </p>
 * <p>
 * <b>当前状态:MA-T1 骨架(接入地基)+ MA-T2 decompose/aggregate + MA-T3 循环/SSE + MA-T4 结果/trace 精细化 ——</b>
 * <ul>
 *   <li>{@code paradigm()} 声明 {@link AgentParadigm#MULTI_AGENT}(配合
 *       {@link AgentParadigm#isImplemented()} 与 {@code EngineSelector.LEGACY_RANK} 登记);</li>
 *   <li>{@code supports()} 仅在 {@code ctx.paradigm() == MULTI_AGENT} 时为真;</li>
 *   <li><b>MA-T2</b>:{@link SubTask}/{@link TaskDecomposition}/{@link WorkerResult} record +
 *       {@link #decompose}(BeanOutputConverter&lt;{@link TaskDecomposition}&gt; + format 注入 +
 *       {@code .responseEntity(TaskDecomposition.class)},参考 {@link PlanExecuteEngine#runPlan})+
 *       {@link #aggregate}(LLM 综合所有 Worker observation → 最终答案,返回 String,参考
 *       {@link ReflexionEngine#callReflection} 的 LLM 调用模式)+ {@link #renderDecomposePrompt}/
 *       {@link #renderAggregatePrompt}。</li>
 *   <li><b>MA-T3</b>:{@code execute()}/{@code stream()} 跑 {@link #runMultiAgentLoop}——decompose 一次 →
 *       并行 Worker({@code CompletableFuture.supplyAsync} 每个子任务一个 Worker,各跑 {@link #callLlmForWorker}
 *       + 共享 {@link ExplicitToolExecutioner} 手动执行工具 → Observation)→ aggregate 综合 → 假完成守护。
 *       <b>tracker 线程安全(方案 B)</b>:所有 Worker 共享同一 {@link AutonomousExecutionTracker}(并发集合,
 *       天然并发安全),各 Worker 线程经 {@link ToolExecutionContextHolder#setTracker} 把同一实例写进自己的
 *       ThreadLocal → {@code ToolRuntimeAspect} 上报的证据直接合并;{@code allOf().join()} 的 happens-before
 *       保证主线程读到全部证据。无需改 {@link AutonomousExecutionTracker} 的共享 API(方案 A scope 蔓延)。
 *       流式 SSE 生命周期(对齐 Reflexion/PlanExecute):trace(started) → 分解/Worker/聚合 trace →
 *       answerChunks → persist(TERMINAL_RESULT) → reportResult → trace(success) → releaseLockOnce →
 *       completeEmitter;异常委托 {@code fallbackHandler}。</li>
 *   <li>{@link #finalResult} <b>MA-T4 五字段精细化版</b>(observe / plan=summary 直进 /
 *       action={@link #buildWorkerTrace} Worker 轨迹 / reflect=raw 聚合答案 / modelEnabled)
 *       + {@link #resolveFinalAnswer} 优先 reflect 兜底;<b>M1 修复</b>:reflect 用 aggregate 的 raw 输出
 *       (不含 "Worker 结果"/"任务:"/"观察:" verbose 标签),Worker 结果只进 action(对齐 ReflexionEngine M1)。</li>
 * </ul>
 * 构造函数复用 {@link ReActEngine}/{@link PlanExecuteEngine}/{@link ReflexionEngine} 的 11 bean 依赖 +
 * {@link ExplicitToolExecutioner}(MA-T3 Worker 手动执行工具时复用)+ {@code max-agents} 配置(clamp 到 [1,8])。
 * </p>
 */
@Service
public class MultiAgentEngine implements AgentEngine.StreamableAgentEngine {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentEngine.class);

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
    private final int maxAgents;

    /**
     * Coordinator decompose 阶段结构化输出转换器——把 LLM 输出反序列化为 {@link TaskDecomposition} wrapper。
     * <p>对齐 {@link PlanExecuteEngine#planOutputConverter} / {@link ReflexionEngine#reflectionOutputConverter}
     * 的 BeanOutputConverter 模式;{@link #renderDecomposePrompt} 注入 {@code getFormat()} 强制结构化输出,
     * {@link #decompose} 经 {@code .responseEntity(TaskDecomposition.class)} 取回实体。</p>
     * <p>Java 泛型擦除——不能直接用 {@code BeanOutputConverter<List<SubTask>>}(元素类型在运行期丢失),
     * 故包一层 {@link TaskDecomposition} wrapper record(与 PlanExecuteEngine 的 {@code Plan} 同构)。</p>
     */
    private final BeanOutputConverter<TaskDecomposition> decomposeOutputConverter =
            new BeanOutputConverter<>(TaskDecomposition.class);

    public MultiAgentEngine(AiProviderService aiProviderService,
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
                            @Value("${springclaw.chat.max-agents:5}") int maxAgents) {
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
        this.maxAgents = Math.max(1, Math.min(maxAgents, 8));
    }

    @Override
    public String name() {
        return "multi-agent-loop";
    }

    @Override
    public AgentParadigm paradigm() {
        return AgentParadigm.MULTI_AGENT;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean supports(ChatContext ctx) {
        return ctx != null && ctx.paradigm() == AgentParadigm.MULTI_AGENT;
    }

    /**
     * 阻塞执行入口——内部跑 {@link #runMultiAgentLoop}(无 emitter)。
     * <p>结构对齐 {@link ReflexionEngine#execute} / {@link PlanExecuteEngine#execute}:直接进入
     * Coordinator→并行 Worker→聚合 循环,返回 {@link ChatExecutionResult}。</p>
     */
    @Override
    public ChatExecutionResult execute(ChatContext ctx, AgentEngine.FallbackResponder fallbackResponder) {
        return runMultiAgentLoop(ctx, null, ctx.requestId());
    }

    /**
     * 流式执行入口——管理完整 SSE 生命周期,内部跑 {@link #runMultiAgentLoop}。
     * <p>结构对齐 {@link ReflexionEngine#stream} / {@link PlanExecuteEngine#stream}:trace(started) →
     * Coordinator→并行 Worker→聚合 循环 → 发送最终答案 → persist(TERMINAL_RESULT) → reportResult →
     * trace(success) → releaseLockOnce → completeEmitter;异常委托 {@code fallbackHandler}。</p>
     */
    @Override
    public Disposable stream(ChatContext context,
                             SseEmitter emitter,
                             String lockToken,
                             AtomicBoolean lockReleased,
                             AtomicReference<Disposable> disposableRef,
                             AgentEngine.OnStreamFailure fallbackHandler) {
        try {
            sseEventBridge.sendTrace(emitter, context, "Multi-Agent 循环", "multi-agent", "started",
                    "进入 Multi-Agent(Coordinator→并行 Worker→聚合)循环。", 0L);
            sseEventBridge.sendStatus(emitter, "Multi-Agent 循环执行中");

            ChatExecutionResult result = runMultiAgentLoop(context, emitter, context.requestId());

            String finalAnswer = resolveFinalAnswer(result);
            sseEventBridge.sendAnswerChunks(emitter, finalAnswer);
            chatResultPersister.persist(context, finalAnswer, result, ChatPersistenceIntent.TERMINAL_RESULT);
            reportResult(context, result, finalAnswer);

            sseEventBridge.sendTrace(emitter, context, "Multi-Agent 循环", "multi-agent", "success",
                    "Multi-Agent 循环执行完成(" + result.plan() + ")。", 0L);
            sseEventBridge.sendTrace(emitter, context, "完成", "final", "success",
                    "已生成最终回答。", 0L);
            releaseLockOnce(context, lockToken, lockReleased);
            sseEventBridge.completeEmitter(emitter);
        } catch (Exception ex) {
            log.warn("Multi-Agent SSE 执行失败: sessionKey={}, reason={}",
                    context.assembled() == null ? "?" : context.assembled().sessionKey(), ex.getMessage());
            try {
                String simplifiedReason = chatResponsePolicyService.simplifyFailureReason(ex.getMessage());
                sseEventBridge.sendTrace(emitter, context, "Multi-Agent 循环", "multi-agent", "failed",
                        simplifiedReason, 0L);
            } catch (Exception ignored) {}
            fallbackHandler.handle(context, ex, emitter, lockToken, lockReleased);
        }
        return null;
    }

    private String observePrompt(ChatContext ctx) {
        return ctx == null || ctx.assembled() == null ? "" : ctx.assembled().observePrompt();
    }

    // === MA-T3: runMultiAgentLoop(分解 + 并行 Worker + 聚合 + tracker 线程安全 + 假完成守护 + SSE)===

    /**
     * Multi-Agent 核心循环——Coordinator 把问题 decompose 成子任务 → 并行分派给 Worker(每个 Worker 跑
     * 一次工具增强推理)→ Coordinator aggregate Worker 结果 → 假完成守护(基于合并的工具证据)。
     * <ul>
     *   <li>分解:{@link #decompose}(MA-T2)经 BeanOutputConverter&lt;{@link TaskDecomposition}&gt; 产出
     *       {@code List<SubTask>};</li>
     *   <li>并行 Worker:每个 {@link SubTask} 经 {@code CompletableFuture.supplyAsync} 并行执行
     *       {@link #runWorker}——一次 LLM 调用({@link #callLlmForWorker})+ 共享
     *       {@link ExplicitToolExecutioner} 手动执行工具得 Observation;</li>
     *   <li><b>tracker 线程安全(方案 B —— 共享并发安全 tracker)</b>:所有 Worker 共享同一
     *       {@link AutonomousExecutionTracker} 实例。{@link AutonomousExecutionTracker} 用
     *       CopyOnWriteArrayList / ConcurrentHashMap / volatile,显式标注"为异步执行保留兼容性"——
     *       并发写入天然安全。每个 Worker 线程经 {@link ToolExecutionContextHolder#setTracker} 把这一
     *       <b>同一</b>实例写进自己的 ThreadLocal,反射调用的工具经 {@code ToolRuntimeAspect} 上报证据时
     *       读到的就是共享 tracker。{@code CompletableFuture.allOf().join()} 的 happens-before 保证主线程
     *       之后读到全部 Worker 的证据。无需 merge / 无需改 {@link AutonomousExecutionTracker} 的共享 API
     *       (方案 A 需新增 merge 方法,改动 3 个引擎共用的类,scope 蔓延;方案 C 失去写工具证据精度)。</li>
     *   <li>聚合:{@link #aggregate}(MA-T2)综合所有 Worker observation → 最终答案;</li>
     *   <li>假完成守护:read 直通;write/side_effect/dangerous 必须校验共享 tracker 的合并工具证据,
     *       无证据则降级(summary 标 "假完成拦截")。</li>
     * </ul>
     * <p>结构对齐 {@link ReflexionEngine#runReflexionLoop} / {@link PlanExecuteEngine#runPlanExecute}:
     * 模型不可用降级 → 选工具 → tracker + scope → 循环 → 假完成守护。差异:Multi-Agent 的循环骨架是
     * "decompose 一次 → 并行 Worker → aggregate 一次"(无迭代重试,分工在 decompose 阶段一次性完成),
     * 且 Worker 并行执行是它区别于其它单 Agent 范式的核心。</p>
     */
    private ChatExecutionResult runMultiAgentLoop(ChatContext ctx, SseEmitter emitter, String requestId) {
        AiProviderService.ActiveChatClient activeClient = ctx.activeClient();
        AssembledContext assembled = ctx.assembled();
        AgentDecision decision = ctx.decision();
        final String reqId = requestId != null ? requestId : ctx.requestId();
        String riskLevel = decision != null ? decision.riskLevel() : "read";

        // 模型不可用 → 降级(对齐 ReflexionEngine.runReflexionLoop / PlanExecuteEngine.runPlanExecute)
        if (!modelTransportGuardService.isModelCallEnabled(activeClient)) {
            LocalSkillFallbackService.LocalSkillResult fallback =
                    localExecutionSupport.tryFallback(assembled == null ? "" : assembled.question(), true);
            return new ChatExecutionResult(
                    observePrompt(ctx),
                    modelTransportGuardService.disabledModelPlanReason(activeClient),
                    fallback != null ? fallback.executionDetails()
                            : modelTransportGuardService.disabledModelActionReason(activeClient),
                    fallback != null ? fallback.fallbackAnswer() : "",
                    false
            );
        }

        final Object[] tools = toolOrchestrator.selectAutonomousTools(ctx.channel(), ctx.userId(), decision);
        final boolean allowFailover = isSafeToRetry(tools);
        // 方案 B:共享并发安全 tracker —— 所有 Worker 把工具证据上报到这同一实例。CopyOnWriteArrayList /
        // ConcurrentHashMap / volatile 保证并发写入安全;allOf().join() 的 happens-before 保证主线程读到全部证据。
        final AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
        // 工具执行上下文(请求级,所有 Worker 共享同一不可变 record)——各 Worker 线程 open scope 写进自己的
        // ThreadLocal,让反射调用的工具经 ToolRuntimeAspect 时能读到 userId/sessionKey/runId 做权限检查 + 审计
        // + emit TOOL_* 事件(对齐 ReflexionEngine/PlanExecuteEngine 的 toolContext 构造)。
        final ToolExecutionContext toolContext = new ToolExecutionContext(
                assembled == null ? null : assembled.sessionKey(),
                ctx.channel(),
                ctx.userId(),
                reqId,
                "MULTI_AGENT",
                reqId,
                ctx.roleCode()
        );

        try (ToolExecutionContextHolder.Scope scope = ToolExecutionContextHolder.open(toolContext)) {
            ToolExecutionContextHolder.setTracker(tracker); // 主线程也置共享 tracker(防御性,decompose/aggregate 不调工具)

            // 1. 分解
            List<SubTask> decomposed = decompose(ctx, tools, activeClient);
            if (decomposed.isEmpty()) {
                log.warn("MultiAgent decompose 为空,终止循环: requestId={}", reqId);
                return finalResult(ctx, List.of(), "",
                        "Multi-Agent: decompose 未生成子任务", true);
            }
            // max-agents 硬上限:decompose prompt 只是软约束(LLM 可能无视),这里按 maxAgents 截断,
            // 保证并行 Worker 数量不超限(对齐"守护 max-agents"语义)。
            if (decomposed.size() > maxAgents) {
                log.warn("MultiAgent decompose 返回 {} 子任务超过 maxAgents={},截断: requestId={}",
                        decomposed.size(), maxAgents, reqId);
                decomposed = decomposed.subList(0, maxAgents);
            }
            final List<SubTask> subTasks = decomposed;
            if (emitter != null) {
                try {
                    sseEventBridge.sendTrace(emitter, ctx, "Multi-Agent 分解", "multi-agent", "decompose",
                            "分解为 " + subTasks.size() + " 子任务", 0L);
                    sseEventBridge.sendStatus(emitter, "Multi-Agent 并行执行中(" + subTasks.size() + " worker)");
                } catch (Exception ignored) {}
            }

            // 2. 并行 Workers —— 每个 SubTask 一个 CompletableFuture,共享 tracker + 共享 toolContext
            List<CompletableFuture<WorkerResult>> futures = subTasks.stream()
                    .map(task -> CompletableFuture.supplyAsync(() -> runWorker(
                            ctx, task, tools, activeClient, reqId, allowFailover, tracker, toolContext)))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            List<WorkerResult> results = futures.stream().map(CompletableFuture::join).toList();

            // Worker 三段式 trace(主线程 emitter 单线程写,无 SSE 并发竞争)
            if (emitter != null) {
                for (int i = 0; i < results.size(); i++) {
                    WorkerResult r = results.get(i);
                    try {
                        sseEventBridge.sendTrace(emitter, ctx, "Multi-Agent Worker " + (i + 1),
                                "multi-agent", "worker",
                                TextUtils.truncate(r.task() == null ? "" : r.task().description(), 120)
                                        + " → " + TextUtils.truncate(r.observation(), 200), 0L);
                    } catch (Exception ignored) {}
                }
            }

            // 3. 聚合
            String finalAnswer = aggregate(ctx, results, activeClient);
            if (emitter != null) {
                try {
                    sseEventBridge.sendTrace(emitter, ctx, "Multi-Agent 聚合", "multi-agent", "aggregate",
                            TextUtils.truncate(finalAnswer, 200), 0L);
                } catch (Exception ignored) {}
            }

            // 假完成守护(主线程,基于共享 tracker 合并证据)
            if ("read".equals(riskLevel) || tracker.satisfiesCompletionCondition(riskLevel)) {
                log.info("MultiAgent 任务完成: requestId={}, workers={}, riskLevel={}, hasWrite={}, hasCmd={}, hasVerified={}",
                        reqId, results.size(), riskLevel,
                        tracker.hasWriteToolCall(), tracker.hasRunCommandCall(), tracker.hasVerifiedSideEffect());
                return finalResult(ctx, results, finalAnswer,
                        "Multi-Agent: " + results.size() + " worker 并行后聚合", true);
            }
            // 假完成:聚合产出答案但无足够工具证据 → 降级标注(summary 写明拦截原因,Task 4 细化 trace)
            String rejection = tracker.renderFakeCompletionRejection(riskLevel);
            log.warn("MultiAgent 假完成拦截: requestId={}, workers={}, riskLevel={}, hasWrite={}, hasCmd={}",
                    reqId, results.size(), riskLevel,
                    tracker.hasWriteToolCall(), tracker.hasRunCommandCall());
            if (emitter != null) {
                try {
                    sseEventBridge.sendTrace(emitter, ctx, "Multi-Agent 假完成拦截",
                            "multi-agent", "warning",
                            "聚合产出答案但缺少真实工具证据: " + TextUtils.truncate(rejection, 200), 0L);
                } catch (Exception ignored) {}
            }
            return finalResult(ctx, results, finalAnswer,
                    "Multi-Agent: 假完成拦截(" + riskLevel + " 任务无足够工具证据)", true);
        } catch (Exception ex) {
            log.warn("Multi-Agent 循环执行失败: requestId={}, reason={}", reqId, ex.getMessage());
            LocalSkillFallbackService.LocalSkillResult fallback =
                    localExecutionSupport.tryFallback(assembled == null ? "" : assembled.question(), true);
            return new ChatExecutionResult(
                    observePrompt(ctx),
                    "Multi-Agent 异常终止: " + chatResponsePolicyService.simplifyFailureReason(ex.getMessage()),
                    "(Multi-Agent 循环异常)",
                    fallback != null ? fallback.fallbackAnswer() : "",
                    false
            );
        } finally {
            // scope close 只还原 ToolExecutionContext,不清 tracker ThreadLocal;手动 clear(对齐 Reflexion/PlanExecute)。
            // 各 Worker 线程的 tracker ThreadLocal 在 runWorker finally 自行 clear。
            ToolExecutionContextHolder.clearTracker();
        }
    }

    /**
     * 单个 Worker 的执行——一次 LLM 调用({@link #callLlmForWorker} 据 SubTask 输出 Thought+Action 文本)
     * + 共享 {@link ExplicitToolExecutioner} 手动执行工具得 Observation。
     * <p><b>tracker 线程安全(方案 B)</b>:Worker 线程 open scope(写共享 toolContext 到本线程 ThreadLocal)+
     * {@link ToolExecutionContextHolder#setTracker} 把<b>共享</b>tracker 写到本线程 ThreadLocal。这样反射调用的
     * 工具经 {@code ToolRuntimeAspect} 读本线程 ThreadLocal 拿到的就是共享 tracker,证据直接合并到主线程的
     * tracker(并发写入安全,因 {@link AutonomousExecutionTracker} 用并发集合)。finally clearTracker 清本线程。</p>
     * <p>异常不向调用方抛——返回 {@code failed=true} 的 {@link WorkerResult},让 aggregate 阶段降权/标注
     * (对齐 ExplicitToolExecutioner 的"解析失败返回错误说明"理念:保持循环推进)。</p>
     */
    private WorkerResult runWorker(ChatContext ctx,
                                   SubTask task,
                                   Object[] tools,
                                   AiProviderService.ActiveChatClient client,
                                   String requestId,
                                   boolean allowFailover,
                                   AutonomousExecutionTracker sharedTracker,
                                   ToolExecutionContext toolContext) {
        try (ToolExecutionContextHolder.Scope scope = ToolExecutionContextHolder.open(toolContext)) {
            ToolExecutionContextHolder.setTracker(sharedTracker); // 本线程置共享 tracker → 工具证据合并
            ModelCallExecutor.ModelCallResult<String> callResult = callLlmForWorker(
                    ctx, task, tools, client, requestId, allowFailover);
            String thought = callResult.value();
            boolean hasAction = explicitToolExecutioner.hasActionLine(thought);
            String observation = hasAction
                    ? explicitToolExecutioner.execute(thought, tools, requestId) : "";
            return new WorkerResult(task, thought, observation, false);
        } catch (Exception ex) {
            log.warn("Multi-Agent Worker 失败: requestId={}, task={}, reason={}",
                    requestId, task == null ? "?" : task.description(), ex.getMessage());
            return new WorkerResult(task, "", "worker 失败: " + ex.getMessage(), true);
        } finally {
            ToolExecutionContextHolder.clearTracker(); // 清本线程 tracker ThreadLocal
        }
    }

    /**
     * Worker 阶段的 LLM 调用——不挂 {@code .tools()},系统 prompt({@link #renderWorkerPrompt})要求 LLM
     * 据当前 SubTask 输出 Thought+Action 文本(或直接答案)。返回 {@link ModelCallExecutor.ModelCallResult}
     * 让调用方更新 failover 后的 client(对齐 {@link ReflexionEngine#callLlmForAttempt} /
     * {@link PlanExecuteEngine#callLlmForStep} 的 LLM 调用模式)。经 {@link ExplicitToolExecutioner} 在
     * {@link #runWorker} 下方解析 Action 并手动执行工具。
     */
    private ModelCallExecutor.ModelCallResult<String> callLlmForWorker(ChatContext ctx,
                                                                       SubTask task,
                                                                       Object[] tools,
                                                                       AiProviderService.ActiveChatClient client,
                                                                       String requestId,
                                                                       boolean allowFailover) throws Exception {
        AssembledContext assembled = ctx.assembled();
        String sessionKey = assembled == null ? "" : assembled.sessionKey();
        String systemPrompt = renderWorkerPrompt(ctx, task, tools);
        return modelCallExecutor.executeChat(
                client,
                "multi-agent-worker",
                new ModelCallExecutor.ChatRequestContext(requestId, sessionKey, ctx.channel(), ctx.userId()),
                allowFailover,
                c -> {
                    // 手动循环主路径:不挂 .tools()——LLM 文本输出 Thought + Action,Worker 下方
                    // ExplicitToolExecutioner 解析并手动执行工具(对齐 Reflexion/PlanExecute 的 LLM 调用模式)。
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
     * 渲染 Worker 阶段(单子任务执行)的 system prompt——要求 LLM 基于用户问题 + 当前 SubTask 输出 Thought+Action。
     * <p>结构模仿 {@link ReflexionEngine#renderAttemptPrompt} / {@link PlanExecuteEngine#renderExecutePrompt}:
     * {{INJECTION}}({@link TypedContextPromptRenderer#promptPrefix}) + {{QUESTION}}
     * ({@link TypedContextPromptRenderer#question}) + {{SUB_TASK}}(Coordinator 分配的子任务描述)
     * + {{TOOLS}}({@link ToolReflectionSupport#renderToolList})。差异:Multi-Agent 的 Worker 是"并行分工下
     * 的单次执行"——只负责自己那一个 SubTask(由 Coordinator decompose 分配),不边推理边循环(ReAct)、
     * 不带反思 memory(Reflexion);子任务之间的综合交由 aggregate 阶段。</p>
     */
    String renderWorkerPrompt(ChatContext ctx, SubTask task, Object[] tools) {
        String template = """
                {{INJECTION}}你是 Multi-Agent Agent 的一个 Worker,请独立完成 Coordinator 分配给你的子任务。

                # 用户问题(全局上下文)
                {{QUESTION}}

                # 你负责的子任务
                {{SUB_TASK}}

                # 可用工具
                {{TOOLS}}

                # 执行协议
                先输出 Thought(简短分析:如何完成本子任务、是否需要调工具),再:
                  - 若需要调用工具 → 输出一行 Action: 工具名(参数名="值", ...),例如 Action: search(query="Spring AI")
                    工具会被引擎执行,结果作为本子任务的 Observation;若工具无参数则写 Action: 工具名()
                  - 若已能直接作答(无需工具)→ 直接输出本子任务的答案/观察文本(不输出 Action 视为本子任务完成)

                # 现在输出本子任务的 Thought(与 Action 或答案):
                """;
        return template
                .replace("{{INJECTION}}", TypedContextPromptRenderer.promptPrefix(ctx))
                .replace("{{QUESTION}}", StringUtils.hasText(TypedContextPromptRenderer.question(ctx))
                        ? TypedContextPromptRenderer.question(ctx).trim() : "")
                .replace("{{SUB_TASK}}", task == null || !StringUtils.hasText(task.description())
                        ? "(未分配具体子任务)" : task.description().trim())
                .replace("{{TOOLS}}", ToolReflectionSupport.renderToolList(tools));
    }

    /**
     * 构造终态 {@link ChatExecutionResult}(<b>MA-T4 五字段精细化版</b>,对齐 Reflexion/PlanExecute finalResult)。
     * <ul>
     *   <li>observe = {@link #observePrompt}</li>
     *   <li>plan = summary 直进(如 "Multi-Agent: N worker 并行后聚合" /
     *       "Multi-Agent: 假完成拦截(...)" / "Multi-Agent: decompose 未生成子任务");
     *       stream success trace 复用作完成摘要</li>
     *   <li>action = {@link #buildWorkerTrace}(每 Worker 的 task.description + Thought + Observation 三段式轨迹);</li>
     *   <li>reflect = raw 聚合答案(<b>MA-T4 M1</b>:不含 verbose "Worker 结果"/"任务:"/"观察:" 标签)
     *       —— Worker 结果已由 action 投影,这里只放 {@code finalAnswer},让 {@link #resolveFinalAnswer}
     *       发给用户的是 clean 答案(对齐 {@link ReflexionEngine#finalResult} 的 M1 修复);</li>
     *   <li>modelEnabled = 形参</li>
     * </ul>
     * <p><b>MA-T4 M1</b>:Task 3 基础版 reflect = answer + "\nWorker 结果:\n" + {@link #renderWorkerResults}(results),
     * 把 Worker 的 task/observation verbose 段也塞进 reflect,而 {@link #resolveFinalAnswer} 又把 reflect 当
     * 最终答案发给用户(看到 verbose 标签)。本版拆开:Worker 结果只进 action({@link #buildWorkerTrace}),
     * reflect 用 raw {@code finalAnswer}(aggregate 阶段 LLM 综合的 clean 答案)。对齐
     * {@link PlanExecuteEngine#finalResult}(answer + 步骤概要)与 {@link ReflexionEngine#finalResult}(raw answer + memory)。</p>
     */
    private ChatExecutionResult finalResult(ChatContext ctx, List<WorkerResult> results,
                                            String finalAnswer, String summary, boolean modelEnabled) {
        String answer = StringUtils.hasText(finalAnswer)
                ? TextUtils.truncate(finalAnswer, 800) : "(无最终答案)";
        return new ChatExecutionResult(
                observePrompt(ctx),
                summary,
                buildWorkerTrace(results),
                answer,
                modelEnabled
        );
    }

    /**
     * 把各 Worker 执行轨迹拼成 action 字段——每 Worker 投影 task.description + Thought + Observation
     * (对齐 {@link PlanExecuteEngine#buildActionTrace} 的 "[Step N] ..." 结构,差异:这里是并行 Worker 而非串行步骤)。
     * 综合步 observation 为空时标注 "(无工具调用)" 避免读起来突兀。
     */
    private String buildWorkerTrace(List<WorkerResult> results) {
        if (results == null || results.isEmpty()) return "[Worker] (未执行任何 worker)";
        StringJoiner sj = new StringJoiner("\n");
        for (int i = 0; i < results.size(); i++) {
            WorkerResult r = results.get(i);
            String observation = StringUtils.hasText(r.observation())
                    ? TextUtils.truncate(r.observation(), 200)
                    : (r.failed() ? "(worker 失败)" : "(无工具调用)");
            sj.add("[Worker " + (i + 1) + "] "
                    + TextUtils.truncate(r.task() == null ? "" : r.task().description(), 120)
                    + " → Thought: " + TextUtils.truncate(r.thought(), 200)
                    + " → Observation: " + observation);
        }
        return sj.toString();
    }

    /**
     * 从 {@link ChatExecutionResult} 取回用户可见的最终答案(模仿 Reflexion/PlanExecute resolveFinalAnswer)。
     * <p>优先 {@code reflect}(MA-T4 起 = raw 聚合答案);reflect 空时按 modelEnabled 给兜底语。
     * <b>MA-T4 M1</b>:reflect 是 raw 答案(不含 Worker 结果 verbose 标签),用户不会再看到 verbose 轨迹
     * (Worker 结果已由 {@link ChatExecutionResult#action()} 投影)。</p>
     */
    private String resolveFinalAnswer(ChatExecutionResult result) {
        if (StringUtils.hasText(result.reflect())) {
            return result.reflect();
        }
        if (!result.modelEnabled()) {
            return "Multi-Agent 循环执行完成,但模型不可用。";
        }
        return "Multi-Agent 循环执行完成(" + result.plan() + ")。";
    }

    private void reportResult(ChatContext context, ChatExecutionResult result, String answer) {
        if (lifecycleObserver == null) return;
        try {
            lifecycleObserver.resultReturned(context, result, answer, Instant.now());
        } catch (RuntimeException ex) {
            log.error("canonical lifecycle projection failed after multi-agent persistence, requestId={}",
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
     * 复制自 {@link ReflexionEngine} / {@link PlanExecuteEngine}。
     */
    private boolean isSafeToRetry(Object[] tools) {
        if (tools == null) return true;
        for (Object tool : tools) {
            if (tool instanceof com.springclaw.tool.pack.WorkspaceEditToolPack) return false;
            if (tool instanceof com.springclaw.tool.pack.ScriptSkillToolPack) return false;
        }
        return true;
    }

    // === MA-T2: Coordinator decompose / aggregate(BeanOutputConverter<TaskDecomposition>)===

    /**
     * Coordinator decompose 阶段——一次模型调用,经 BeanOutputConverter 结构化产出可并行的 {@link SubTask} 列表。
     * <p>结构对齐 {@link PlanExecuteEngine#runPlan} 的 BeanOutputConverter + {@code .responseEntity} 模式:
     * 渲染含 format 说明的 system prompt({@link #renderDecomposePrompt})→ {@code ModelCallExecutor.executeChat}
     * 携 {@code .responseEntity(TaskDecomposition.class)} → 取 {@code entity.tasks()}。失败降级为空列表
     * (MA-T3 循环自行处理空分解/兜底,本方法不向调用方抛)。</p>
     * <p>要求 LLM 分解为 ≤ {@code maxAgents} 个可并行子任务(视角/信息源/子问题维度拆分)。</p>
     */
    List<SubTask> decompose(ChatContext ctx, Object[] tools, AiProviderService.ActiveChatClient client) {
        try {
            String systemPrompt = renderDecomposePrompt(ctx, tools);
            ModelCallExecutor.ModelCallResult<TaskDecomposition> callResult = modelCallExecutor.executeChat(
                    client,
                    "multi-agent-decompose",
                    new ModelCallExecutor.ChatRequestContext(
                            ctx.requestId(),
                            ctx.assembled() == null ? "" : ctx.assembled().sessionKey(),
                            ctx.channel(),
                            ctx.userId()
                    ),
                    true,
                    c -> {
                        var response = conversationAdvisorSupport.apply(
                                        c.chatClient().prompt()
                                                .system(systemPrompt)
                                                .user(TypedContextPromptRenderer.question(ctx)),
                                        ctx.assembled() == null ? "" : ctx.assembled().sessionKey(),
                                        ctx.userId())
                                .call()
                                .responseEntity(TaskDecomposition.class);
                        return new ModelCallExecutor.ChatOperationResult<>(
                                response.entity(),
                                response.response()
                        );
                    }
            );
            TaskDecomposition decomp = callResult.value();
            return decomp == null || decomp.tasks() == null ? List.of() : decomp.tasks();
        } catch (Exception ex) {
            log.warn("Decompose 阶段失败,降级为空子任务列表: requestId={}, reason={}",
                    ctx.requestId(), ex.getMessage());
            return List.of();
        }
    }

    /**
     * Coordinator aggregate 阶段——LLM 综合所有 Worker 的 observation → 最终答案文本。
     * <p>结构对齐 {@link ReflexionEngine#callLlmForAttempt} / {@link PlanExecuteEngine#callLlmForStep} 的
     * LLM 文本调用模式(不挂 {@code .tools()},返回 String 而非 BeanOutputConverter):渲染
     * {@link #renderAggregatePrompt}(注入全部 Worker 结果)→ {@code executeChat} + {@code .chatResponse()}
     * → {@link ModelCallExecutor#extractText} 取文本。与 decompose 不同,aggregate 产出自然语言最终答案,
     * 无需结构化 wrapper。</p>
     * <p>异常降级:返回拼接的 Worker observation({@link #renderWorkerResults}),保证循环终态有可读答案。
     * LLM 返回空文本时同样降级,避免终态空白。</p>
     */
    String aggregate(ChatContext ctx, List<WorkerResult> results, AiProviderService.ActiveChatClient client) {
        AssembledContext assembled = ctx.assembled();
        String sessionKey = assembled == null ? "" : assembled.sessionKey();
        try {
            String systemPrompt = renderAggregatePrompt(ctx, results);
            ModelCallExecutor.ModelCallResult<String> callResult = modelCallExecutor.executeChat(
                    client,
                    "multi-agent-aggregate",
                    new ModelCallExecutor.ChatRequestContext(
                            ctx.requestId(), sessionKey, ctx.channel(), ctx.userId()
                    ),
                    true,
                    c -> {
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
            String answer = callResult.value();
            return StringUtils.hasText(answer) ? answer : renderWorkerResults(results);
        } catch (Exception ex) {
            log.warn("Aggregate 阶段失败,降级为拼接 worker observation: requestId={}, reason={}",
                    ctx.requestId(), ex.getMessage());
            return renderWorkerResults(results);
        }
    }

    /**
     * 渲染 Coordinator decompose 阶段的 system prompt——要求 LLM 把问题分解为 ≤ maxAgents 个可并行子任务,
     * 输出 {@link TaskDecomposition} JSON。
     * <p>结构模仿 {@link PlanExecuteEngine#renderPlanPrompt}:{{INJECTION}}({@link TypedContextPromptRenderer#promptPrefix})
     * + {{QUESTION}}({@link TypedContextPromptRenderer#question}) + {{TOOLS}}({@link ToolReflectionSupport#renderToolList})
     * + {{MAX_AGENTS}}(并行上限)+ {{FORMAT}}({@link #decomposeOutputConverter}.{@link BeanOutputConverter#getFormat()
     * getFormat()} 注入,强制结构化输出)。差异:Plan-Execute 的 Planner 产出有序串行步骤,Multi-Agent 的
     * Coordinator 产出可并行子任务(每个由一个独立 Worker 消费)。</p>
     */
    String renderDecomposePrompt(ChatContext ctx, Object[] tools) {
        String template = """
                {{INJECTION}}你是 Multi-Agent Agent 的 Coordinator,请把用户问题分解为若干可并行的子任务。

                # 用户问题
                {{QUESTION}}

                # 可用工具(Worker 执行阶段可调用)
                {{TOOLS}}

                # 分解要求
                1) 把问题拆为可并行执行的独立子任务(每个子任务由一个 Worker 独立完成);
                2) 子任务数量不超过 {{MAX_AGENTS}} 个,聚焦不同视角 / 信息源 / 子问题;
                3) 每个子任务用一句自然语言描述明确的目标(检索 / 调用工具 / 分析 / 综合);
                4) 子任务之间尽量独立,便于并行;有明显依赖时可拆,但注明依赖关系;
                5) 输出必须严格遵循下面的格式说明,不要附加任何额外文本。

                # 输出格式说明
                {{FORMAT}}
                """;
        return template
                .replace("{{INJECTION}}", TypedContextPromptRenderer.promptPrefix(ctx))
                .replace("{{QUESTION}}", StringUtils.hasText(TypedContextPromptRenderer.question(ctx))
                        ? TypedContextPromptRenderer.question(ctx).trim() : "")
                .replace("{{TOOLS}}", ToolReflectionSupport.renderToolList(tools))
                .replace("{{MAX_AGENTS}}", String.valueOf(maxAgents))
                .replace("{{FORMAT}}", decomposeOutputConverter.getFormat());
    }

    /**
     * 渲染 Coordinator aggregate 阶段的 system prompt——要求 LLM 综合所有 Worker 的 observation,
     * 输出连贯完整的最终答案文本(无 Thought/Action 标签)。
     * <p>结构模仿 {@link ReflexionEngine#renderReflectionPrompt} / {@link PlanExecuteEngine#renderExecutePrompt}:
     * {{INJECTION}} + {{QUESTION}} + {{WORKER_RESULTS}}(每 Worker 的 task.description + observation,
     * 由 {@link #renderWorkerResults} 拼装)。整体作 system prompt,user 消息取自
     * {@link TypedContextPromptRenderer#question}(对齐 PlanExecute/Reflexion 的 prompt 布局)。</p>
     */
    String renderAggregatePrompt(ChatContext ctx, List<WorkerResult> results) {
        String template = """
                {{INJECTION}}你是 Multi-Agent Agent 的 Coordinator,请综合各 Worker 的结果,产出最终答案。

                # 用户问题
                {{QUESTION}}

                # Worker 结果(各子任务的执行观察)
                {{WORKER_RESULTS}}

                # 综合要求
                1) 把所有 Worker 的 observation 综合为一个连贯、完整的最终答案;
                2) 保留关键事实与证据,去除冗余;若有冲突,指出并给出权衡后的判断;
                3) 直接输出最终答案文本(不要 Thought / Action 标签),回答用户问题。

                # 现在输出综合后的最终答案:
                """;
        return template
                .replace("{{INJECTION}}", TypedContextPromptRenderer.promptPrefix(ctx))
                .replace("{{QUESTION}}", StringUtils.hasText(TypedContextPromptRenderer.question(ctx))
                        ? TypedContextPromptRenderer.question(ctx).trim() : "")
                .replace("{{WORKER_RESULTS}}", renderWorkerResults(results));
    }

    /**
     * 把 Worker 结果拼装成结构化文本——供 {@link #renderAggregatePrompt} 的 {{WORKER_RESULTS}} 注入
     * 及 {@link #aggregate} 异常 / 空输出降级时直接返回。每 Worker 投影任务描述 + observation,
     * 让综合 prompt(或降级兜底)能完整看到所有 Worker 的执行观察(对齐 "LLM 透明 / 全可视化")。
     */
    private String renderWorkerResults(List<WorkerResult> results) {
        if (results == null || results.isEmpty()) return "(暂无 Worker 结果)";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            WorkerResult r = results.get(i);
            sb.append("[Worker ").append(i + 1).append("]\n");
            sb.append("任务: ").append(r.task() == null ? "" : r.task().description()).append("\n");
            sb.append("观察: ").append(StringUtils.hasText(r.observation()) ? r.observation() : "(无)").append("\n");
            if (r.failed()) {
                sb.append("(该 Worker 执行失败)\n");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    // === MA-T2: Coordinator / Worker 结构化 record ===

    /**
     * Coordinator decompose 产出的单个子任务——由一个 Worker 独立消费(MA-T3 并行执行)。
     * <p>只有 {@code description} 一个字段:Coordinator 自然语言描述一个明确的子目标
     * (检索 / 调用工具 / 分析 / 综合)。保持最小化,避免过早结构化动作类型/参数——
     * 具体执行交由 MA-T3 的 Worker(经 {@link ExplicitToolExecutioner} 解析 Action,与
     * {@link PlanExecuteEngine} 的 Execute 步同构)。</p>
     */
    public record SubTask(String description) {
    }

    /**
     * BeanOutputConverter 结构化输出 wrapper——包裹 {@code List<SubTask>}。
     * <p>Java 泛型擦除下 {@code BeanOutputConverter<List<SubTask>>} 无法保留元素类型,
     * 故用本 wrapper record 包一层(对齐 {@link PlanExecuteEngine.Plan} / {@link ReflexionEngine.ReflectionResult}
     * 的单对象 wrapper 模式)。Spring AI 的 BeanOutputConverter 支持反序列化 record + 嵌套 {@code List<SubTask>}。</p>
     */
    public record TaskDecomposition(List<SubTask> tasks) {
    }

    /**
     * 单个 Worker 的执行结果——aggregate 阶段综合的输入。
     * <ul>
     *   <li>{@code task}:该 Worker 承担的 {@link SubTask}(含 Coordinator 分配的描述);</li>
     *   <li>{@code thought}:Worker 的推理文本(MA-T3 Worker 跑一次工具增强推理的 Thought);</li>
     *   <li>{@code observation}:Worker 的执行观察(工具返回或综合结论)——aggregate 综合的核心材料;</li>
     *   <li>{@code failed}:该 Worker 是否执行失败(工具异常/未找到等)——aggregate 据此降权或标注。</li>
     * </ul>
     */
    public record WorkerResult(SubTask task, String thought, String observation, boolean failed) {
    }
}
