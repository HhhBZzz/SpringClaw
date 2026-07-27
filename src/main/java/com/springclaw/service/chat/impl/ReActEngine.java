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
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 *   <li>{@code execute} / {@code stream} 跑 {@link #runReActLoop}:每步一次模型调用 +
 *       原生工具调用({@code .tools()})+ Thought/Action/Observation 历史 + "无工具调用即终止" + max-steps。</li>
 *   <li>假完成守护(Task 4):复用 {@link AutonomousExecutionTracker},write/side_effect/dangerous
 *       任务在完成判定处校验工具证据,无证据则拒绝并注入提示继续循环。</li>
 *   <li>DeepSeek V4 显式 prompt 回退(Task 5):{@code !supportsNativeToolCalling} 时不挂 {@code .tools()},
 *       LLM 文本输出 Thought + Action,引擎解析 Action({@link #findActionLine} 容错 markdown 噪声)→
 *       在 tools 中按名查找 @Tool 方法({@link #findToolMethod})→ 参数绑定({@link #bindArguments},
 *       支持命名/位置参数)→ 反射调用 Spring 代理 bean(经 ToolRuntimeAspect 审计 + tracker 证据上报)→
 *       结果作为 Observation 拼入历史,驱动真正的多步 Thought-Action-Observation 循环。</li>
 *   <li>ChatExecutionResult/trace 精细化投影(Task 6):{@link #finalResult} 五字段完整
 *       (observe / plan="ReAct 执行 N 步" / action=每步 Thought-Action 轨迹 /
 *       reflect=最终答案+步骤概要 / modelEnabled);{@link #resolveFinalAnswer} 优先 reflect 兜底;
 *       每步 SSE 三段式 trace(Thought/Action/Observation)。</li>
 * </ul>
 * 构造函数全量复用 AutonomousLoopEngine 的 11 bean 依赖。
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

    // === Thought-Action-Observation 循环(Task 3 主路径:原生工具调用) ===

    /**
     * ReAct 核心循环——每步一次模型调用,根据 LLM 输出判定是否发起了工具调用,
     * 无工具调用即视为最终答案并终止;否则把本轮 Thought/Action/Observation 拼入历史进入下一轮。
     * <p>结构对齐 {@link AutonomousLoopEngine#runAutonomousLoop}(L194-411):
     * 模型不可用降级 → 选工具 → 步数受限的阻塞循环 → 每步 sendStatus + 模型调用 + trace → 终止/max-steps。</p>
     * <p><b>主路径(Task 3)</b>:{@code DeepSeekChatCompatibility.supportsNativeToolCalling} 为真时挂 {@code .tools(tools)},
     * 由 Spring AI 在 {@code .call()} 内部完成工具往返。
     * <b>显式 prompt 回退(Task 5)</b>:该判定为假(DeepSeek V4 等)时不挂 {@code .tools()},
     * LLM 文本输出 Thought + Action,引擎解析 Action 后手动执行工具并回灌 Observation。
     * 假完成守护已由 Task 4 接入({@link AutonomousExecutionTracker} 在 scope 内 setTracker,
     * 完成判定处校验工具证据);ChatExecutionResult/trace 的精细化投影由 Task 6 完成
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

        // 工具执行上下文 scope —— 让 Spring AI 原生工具调用(.tools())经 ToolRuntimeAspect 时
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
                            // 主路径:原生工具调用(Spring AI 在 .call() 内完成 tool 往返)。
                            // DeepSeek V4(!supportsNativeToolCalling)不挂 .tools(),改走显式 prompt 回退——
                            // LLM 文本输出 Action,循环下方 executeExplicitAction 解析+手动执行(Task 5)。
                            if (DeepSeekChatCompatibility.supportsNativeToolCalling(client)
                                    && tools != null && tools.length > 0) {
                                req = req.tools(tools);
                            }
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

                boolean nativeTools = DeepSeekChatCompatibility.supportsNativeToolCalling(activeClient);
                boolean hasToolCall = hasActionLine(thought);
                String action = describeAction(thought, hasToolCall);
                // DeepSeek V4(!supportsNativeToolCalling)显式 prompt 回退:LLM 在文本里输出
                // Thought + Action,引擎解析 Action 后手动执行工具(经 Spring AOP 代理 →
                // ToolRuntimeAspect 审计 + tracker 证据上报),把结果作为 Observation 拼入历史。
                // 原生路径下工具由 Spring AI 在 .call() 内执行,Observation 不外露,此处只标占位。
                String observation;
                if (hasToolCall && !nativeTools) {
                    observation = executeExplicitAction(thought, tools, requestId);
                } else {
                    observation = describeObservation(hasToolCall);
                }
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

    /**
     * 判定本轮模型输出是否发起了工具调用。
     * <p>{@link ModelCallExecutor#executeChat} 内部只透出文本(L122 取
     * {@code ChatOperationResult.value()}, {@link org.springframework.ai.chat.model.ChatResponse}
     * 不外露),故无法从响应元数据读 tool call。此处以 ReAct 协议文本契约为信号:
     * 模型本轮输出含以 "Action:" 起首的行(经 markdown 归一化,容忍 {@code **Action:**} /
     * {@code - Action:} / {@code ## Action:} 等噪声)→ 视为发起工具调用。</p>
     * <ul>
     *   <li>原生工具调用主路径(Task 3):Spring AI 在 {@code .call()} 内完成 tool 往返,
     *       返回的是观察后的推理/最终答案,通常不再含 "Action:" 行 → 循环很快终止(多数 1 步)。</li>
     *   <li>DeepSeek 文本回退(Task 5):不走 {@code .tools()},模型在文本里输出 "Action:",
     *       引擎解析后手动执行——此判定驱动真正的多步循环。</li>
     * </ul>
     */
    private boolean hasActionLine(String thought) {
        return findActionLine(thought) != null;
    }

    /**
     * 扫描模型输出,返回首个 "Action:" 行**前缀之后的内容(取自原始行,未归一化)**。
     * <p>归一化({@link #stripMarkdownLinePrefix})仅用于"行首是不是 Action: 前缀"的**判定**
     * (容忍 {@code **Action:**} / {@code - Action:} / {@code ## Action:} 等噪声);
     * 内容必须从**原始行**取——{@code stripMarkdownLinePrefix} 会把 {@code *.*} → {@code .}
     * (italic 正则)、剥掉反引号,损坏 glob 通配符与 shell 反引号参数。
     * 归一化借鉴 {@link AutonomousLoopEngine#normalizeMarkerLine} 的前缀清洗,差异:
     * 不 lowercases 全行(参数需保大小写),只在比较前缀时局部 lowercase。</p>
     */
    private String findActionLine(String thought) {
        if (!StringUtils.hasText(thought)) return null;
        for (String line : thought.split("\n")) {
            String stripped = stripMarkdownLinePrefix(line);
            if (stripped == null) continue;
            if (stripped.toLowerCase(Locale.ROOT).startsWith("action:")) {
                return rawActionContent(line);
            }
        }
        return null;
    }

    /**
     * 从**原始行**(未归一化)提取 "Action:" 前缀之后的内容,保留参数里的 * / 反引号 / _ 原样。
     * <p>在原始行里(case-insensitive)找首个 {@code "action:"}——它对应归一化检测命中的前缀位置
     * (原始行首可能带 {@code **} / {@code -} / {@code #} 等 markdown 噪声,但首个 {@code "action:"}
     * 必是前缀,因为归一化检测已确认行首非噪声部分即 {@code "action:"})。然后跳过紧随前缀的闭合型
     * markdown 标记({@code *} / {@code _} / {@code `},如 {@code **Action:**} 右侧的 {@code **})与空白——
     * 这些是 "Action:" 关键字的格式包装,不是参数;参数必以工具名(标识符)起首,不会以这些字符开头,
     * 故安全跳过。</p>
     */
    private String rawActionContent(String rawLine) {
        String lower = rawLine.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("action:");
        if (idx < 0) return rawLine.strip();
        int after = idx + "action:".length();
        while (after < rawLine.length() && isPrefixMarkdownCloser(rawLine.charAt(after))) {
            after++;
        }
        return rawLine.substring(after);
    }

    private boolean isPrefixMarkdownCloser(char c) {
        return c == '*' || c == '_' || c == '`' || Character.isWhitespace(c);
    }

    /**
     * 去掉行首 markdown 噪声(`、**、*、__、heading、列表符、数字列表)与两端空白。
     * 空行返回 null。保留内容大小写(Action 参数可能含大写)。
     */
    private String stripMarkdownLinePrefix(String line) {
        String s = line.strip();
        if (s.isEmpty()) return null;
        s = s.replace("`", "");
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "$1");  // **bold**
        s = s.replaceAll("\\*(.+?)\\*", "$1");        // *italic*
        s = s.replaceAll("__(.+?)__", "$1");           // __bold__
        s = s.strip();
        if (s.isEmpty()) return null;
        s = s.replaceAll("^#{1,6}\\s*", "");           // heading
        s = s.replaceAll("^[-*+]\\s+", "");            // list bullet
        s = s.replaceAll("^\\d+[.)]\\s+", "");         // numbered list
        s = s.strip();
        return s.isEmpty() ? null : s;
    }

    /**
     * 从模型输出提取 Action 描述(取首个 "Action:" 行);无工具调用时标注为最终答案。
     * 用于 history/trace 投影(Task 6 细化)。
     */
    private String describeAction(String thought, boolean hasToolCall) {
        if (!hasToolCall) {
            return "(无工具调用,给出最终答案)";
        }
        String content = findActionLine(thought);
        return content == null ? "(原生工具调用)" : TextUtils.truncate(content.trim(), 400);
    }

    /**
     * 描述本轮 Observation。原生工具调用时 Spring AI 在 {@code .call()} 内部执行工具并回灌结果,
     * 工具输出不外露(ModelCallExecutor 只返回文本),故此处给占位说明;
     * DeepSeek 文本回退路径({@link #executeExplicitAction})返回引擎手动执行的真实 Observation。
     */
    private String describeObservation(boolean hasToolCall) {
        if (!hasToolCall) return "";
        return "(原生工具调用已由 Spring AI 内部执行,Observation 已并入下一轮上下文)";
    }

    // === DeepSeek V4 显式 prompt 回退(Task 5)===
    // !supportsNativeToolCalling 路径:LLM 文本输出 Thought + Action,引擎解析 Action →
    // 在 tools 中按名查 @Tool 方法 → 解析参数 → 反射调用(经 Spring AOP 代理 → ToolRuntimeAspect
    // 审计 + AutonomousExecutionTracker 证据上报)→ 结果作为 Observation。

    /** 解析后的 Action:工具名 + 原始参数串(尚未拆分)。 */
    private record ParsedAction(String toolName, String rawArgs) {
    }

    /** 工具定位结果:Spring 代理 bean + @Tool 方法。 */
    private record ToolMethod(Object bean, Method method) {
    }

    /**
     * 显式 prompt 回退主逻辑:从模型输出解析 Action 行,在 tools 中按名查找 @Tool 方法,
     * 手动调用(经 Spring AOP 代理 → ToolRuntimeAspect 审计 + tracker 证据上报),
     * 把结果作为 Observation 返回。
     * <p>解析失败 / 工具未找到 / 执行异常时返回错误说明字符串(不抛,保持循环推进,让 LLM 下一轮纠错)。</p>
     */
    private String executeExplicitAction(String thought, Object[] tools, String requestId) {
        String actionContent = findActionLine(thought);
        if (actionContent == null) {
            return "(Action 行解析失败)";
        }
        ParsedAction parsed = splitAction(actionContent);
        if (parsed == null) {
            return "(Action 格式无法解析: " + TextUtils.truncate(actionContent.trim(), 120) + ")";
        }
        ToolMethod target = findToolMethod(tools, parsed.toolName());
        if (target == null) {
            log.warn("ReAct 显式回退未找到工具: tool={}, requestId={}", parsed.toolName(), requestId);
            return "(未找到工具: " + parsed.toolName() + ")";
        }
        try {
            Object[] args = bindArguments(target.method(), parsed.rawArgs());
            target.method().setAccessible(true);
            // 反射调用 Spring 代理 bean → Java 虚拟分派到 CGLIB override → ToolRuntimeAspect @Around
            // 拦截(权限/限流/审计 + tracker 证据上报 + RunCoordinator TOOL_* emit)。
            Object result = target.method().invoke(target.bean(), args);
            String obs = result == null ? "(工具返回 null)" : String.valueOf(result);
            log.info("ReAct 显式回退执行工具: tool={}, requestId={}, observationLen={}",
                    parsed.toolName(), requestId, obs.length());
            return TextUtils.truncate(obs, 400);
        } catch (Exception ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            log.warn("ReAct 显式回退工具执行失败: tool={}, requestId={}, reason={}",
                    parsed.toolName(), requestId, cause.getMessage());
            return "(工具执行失败: " + cause.getClass().getSimpleName() + ": "
                    + TextUtils.truncate(String.valueOf(cause.getMessage()), 200) + ")";
        }
    }

    /**
     * 把 "search(query=\"q\")" 拆成 (toolName=search, rawArgs=query="q")。
     * 无括号的裸工具名(无参)也接受。格式不符返回 null。
     */
    private ParsedAction splitAction(String actionContent) {
        String s = actionContent.trim();
        int open = s.indexOf('(');
        int close = s.lastIndexOf(')');
        if (open <= 0 || close <= open) {
            // 无括号:仅当整体是一个裸标识符(无空白)时视为零参工具调用
            if (StringUtils.hasText(s) && !s.contains(" ") && s.matches("[\\w-]+")) {
                return new ParsedAction(s, "");
            }
            return null;
        }
        String name = s.substring(0, open).trim();
        String args = s.substring(open + 1, close).trim();
        if (!StringUtils.hasText(name)) return null;
        return new ParsedAction(name, args);
    }

    /**
     * 在 tools 数组里按名(忽略大小写)查找 @Tool 方法。工具名取 {@link Tool#name()}(空则取方法名)。
     * 经 {@link #getTargetClass} 穿透 CGLIB 代理。
     */
    private ToolMethod findToolMethod(Object[] tools, String toolName) {
        if (tools == null || !StringUtils.hasText(toolName)) return null;
        for (Object bean : tools) {
            if (bean == null) continue;
            Class<?> targetClass = getTargetClass(bean);
            for (Method method : targetClass.getDeclaredMethods()) {
                Tool anno = method.getAnnotation(Tool.class);
                if (anno == null) continue;
                String name = StringUtils.hasText(anno.name()) ? anno.name() : method.getName();
                if (toolName.equalsIgnoreCase(name)) {
                    return new ToolMethod(bean, method);
                }
            }
        }
        return null;
    }

    /**
     * 把原始参数串绑定到方法的形参,返回可反射 invoke 的 Object[]。
     * 支持:
     * <ul>
     *   <li>命名参数 {@code name="value"}(形参名经 {@code -parameters} 保留,按名匹配;)</li>
     *   <li>位置参数 {@code "value"} / 裸 token(按序填充空位)</li>
     *   <li>混合:命名找不到形参时回退位置绑定</li>
     *   <li>引号剥离 + String/int/long/boolean/double 类型转换</li>
     * </ul>
     * 顶层级逗号拆分(尊重 "..." 与 '...' 引号及 ()/[]/{})。缺省值:null(对象)/0false(原始)。
     */
    private Object[] bindArguments(Method method, String rawArgs) {
        Parameter[] params = method.getParameters();
        Object[] bound = new Object[params.length];
        if (params.length == 0 || !StringUtils.hasText(rawArgs)) {
            return fillDefaults(bound, params);
        }
        List<String> tokens = splitArgs(rawArgs);
        boolean anyNamed = false;
        for (String t : tokens) {
            if (nameValueSplitIndex(t) >= 0) { anyNamed = true; break; }
        }
        int pos = 0;
        for (String token : tokens) {
            int eq = anyNamed ? nameValueSplitIndex(token) : -1;
            if (eq >= 0) {
                String nm = token.substring(0, eq).trim();
                String val = token.substring(eq + 1).trim();
                int idx = findParamIndex(params, nm);
                if (idx >= 0) {
                    bound[idx] = parseValue(val, params[idx].getType());
                    continue;
                }
                // 命名但形参名不匹配(如未带 -parameters)→ 回退位置绑定(用 value)
                int slot = nextSlot(bound, pos);
                if (slot >= 0) {
                    bound[slot] = parseValue(val, params[slot].getType());
                    pos = slot + 1;
                }
            } else {
                int slot = nextSlot(bound, pos);
                if (slot >= 0) {
                    bound[slot] = parseValue(token, params[slot].getType());
                    pos = slot + 1;
                }
            }
        }
        return fillDefaults(bound, params);
    }

    /** 顶层级逗号拆分,尊重双引号/单引号与 ()/[]/{} 嵌套。 */
    private List<String> splitArgs(String rawArgs) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        int depth = 0;
        for (int i = 0; i < rawArgs.length(); i++) {
            char c = rawArgs.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
                cur.append(c);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
                cur.append(c);
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                cur.append(c);
            } else if (c == ',' && depth == 0) {
                String t = cur.toString().trim();
                if (!t.isEmpty()) out.add(t);
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        String last = cur.toString().trim();
        if (!last.isEmpty()) out.add(last);
        return out;
    }

    /**
     * 返回 token 中分隔命名参数的 {@code =} 的索引;不是命名参数返回 -1。
     * 规则:= 左侧必须是纯标识符([A-Za-z0-9_-],避开 ==、&gt;=、&lt;=、!=)。
     */
    private int nameValueSplitIndex(String token) {
        if (token == null || token.isEmpty()) return -1;
        int eq = token.indexOf('=');
        if (eq <= 0) return -1;
        String lhs = token.substring(0, eq);
        if (lhs.endsWith(">") || lhs.endsWith("<") || lhs.endsWith("!")) return -1;
        for (int i = 0; i < lhs.length(); i++) {
            char c = lhs.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_' || c == '-')) return -1;
        }
        return eq;
    }

    private int findParamIndex(Parameter[] params, String name) {
        for (int i = 0; i < params.length; i++) {
            if (name.equalsIgnoreCase(params[i].getName())) return i;
        }
        return -1;
    }

    private int nextSlot(Object[] bound, int from) {
        for (int i = from; i < bound.length; i++) {
            if (bound[i] == null) return i;
        }
        return -1;
    }

    /** 剥引号 + 按 target 类型转换(String/int/long/boolean/double);其余按 String 返回。 */
    private Object parseValue(String value, Class<?> type) {
        String v = value == null ? "" : value.trim();
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            v = v.substring(1, v.length() - 1);
        }
        if (type == String.class) return v;
        if (type == int.class || type == Integer.class) {
            try { return Integer.parseInt(v); } catch (Exception e) { return 0; }
        }
        if (type == long.class || type == Long.class) {
            try { return Long.parseLong(v); } catch (Exception e) { return 0L; }
        }
        if (type == boolean.class || type == Boolean.class) {
            return "true".equalsIgnoreCase(v) || "1".equals(v);
        }
        if (type == double.class || type == Double.class) {
            try { return Double.parseDouble(v); } catch (Exception e) { return 0d; }
        }
        return v;
    }

    private Object[] fillDefaults(Object[] bound, Parameter[] params) {
        for (int i = 0; i < bound.length; i++) {
            if (bound[i] == null) {
                bound[i] = primitiveDefault(params[i].getType());
            }
        }
        return bound;
    }

    private Object primitiveDefault(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == boolean.class) return false;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
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
                .replace("{{TOOLS}}", renderToolList(tools))
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

    /**
     * 反射扫描工具 bean 上的 {@link Tool} 注解,列成 "- name(param1, param2): description" 清单。
     * <p>与 {@link AutonomousLoopEngine#renderToolList} 等价(含 CGLIB 代理穿透),差异:ReAct
     * 额外列出形参名(Task 5 显式 prompt 回退需要——DeepSeek V4 不走 .tools(),LLM 据此格式化
     * {@code Action: toolName(param="value")})。复制于 AutonomousLoopEngine 的同名方法,二者都是
     * private,暂不抽公共基类以免越出 Task 范围。</p>
     */
    private String renderToolList(Object[] tools) {
        if (tools == null || tools.length == 0) {
            return "（无可用工具）";
        }
        StringBuilder builder = new StringBuilder();
        for (Object toolBean : tools) {
            if (toolBean == null) continue;
            Class<?> targetClass = getTargetClass(toolBean);
            for (Method method : targetClass.getDeclaredMethods()) {
                Tool toolAnno = method.getAnnotation(Tool.class);
                if (toolAnno != null) {
                    String toolName = StringUtils.hasText(toolAnno.name()) ? toolAnno.name() : method.getName();
                    String description = toolAnno.description();
                    builder.append("- ").append(toolName)
                            .append("(").append(renderParamSignature(method)).append(")")
                            .append(": ").append(description).append("\n");
                }
            }
        }
        return builder.toString().trim();
    }

    /** 渲染方法形参名清单(逗号分隔),供显式 prompt 回退时 LLM 格式化 Action 入参。 */
    private String renderParamSignature(Method method) {
        Parameter[] params = method.getParameters();
        if (params.length == 0) return "";
        StringJoiner sj = new StringJoiner(", ");
        for (Parameter p : params) {
            sj.add(p.getName());
        }
        return sj.toString();
    }

    private Class<?> getTargetClass(Object bean) {
        if (bean.getClass().getName().contains("$SpringCGLIB$")) {
            Class<?> superclass = bean.getClass().getSuperclass();
            if (superclass != null && !superclass.getName().contains("$SpringCGLIB$")) {
                return superclass;
            }
        }
        return bean.getClass();
    }
}
