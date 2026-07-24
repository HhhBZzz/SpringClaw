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
 * <b>当前状态:Task 1-3 已完成</b>——
 * <ul>
 *   <li>{@code paradigm()} 声明 {@link AgentParadigm#REACT}(配合
 *       {@link AgentParadigm#isImplemented()} 与 {@code EngineSelector.LEGACY_RANK} 登记);</li>
 *   <li>{@code supports()} 仅在 {@code ctx.paradigm() == REACT} 时为真;</li>
 *   <li>{@code execute} / {@code stream} 跑 {@link #runReActLoop}:每步一次模型调用 +
 *       原生工具调用({@code .tools()})+ Thought/Action/Observation 历史 + "无工具调用即终止" + max-steps。</li>
 *   <li>待办:Task 4 假完成守护;Task 5 DeepSeek V4 显式文本回退(!supportsNativeToolCalling 路径);
 *       Task 6 ChatExecutionResult/trace 精细化投影。</li>
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
     * 由 Spring AI 在 {@code .call()} 内部完成工具往返;DeepSeek V4 的显式文本回退留待 Task 5。
     * 假完成守护留待 Task 4;ChatExecutionResult/trace 的精细化投影留待 Task 6。</p>
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
        final StringJoiner actionTrace = new StringJoiner("\n");
        String history = "";

        // 工具执行上下文 scope —— 让 Spring AI 原生工具调用(.tools())经 ToolRuntimeAspect 时
        // 能读到 userId/sessionKey/runId 等做权限检查 + 审计 + emit TOOL_* 事件(对齐
        // AutonomousLoopEngine L223-235)。本 Task 仅 open scope;AutonomousExecutionTracker
        // 留待 Task 4(届时在 scope 内 setTracker + finally clearTracker)。
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
                            // Task 5:!supportsNativeToolCalling 时改走显式 prompt 回退(本 Task 先不实现)。
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

                boolean hasToolCall = hasActionLine(thought);
                String action = describeAction(thought, hasToolCall);
                String observation = describeObservation(hasToolCall);
                steps.add(new ReActStep(thought, action, observation));
                actionTrace.add("[Step " + stepNo + "] " + TextUtils.truncate(action, 400));

                if (emitter != null) {
                    try {
                        sseEventBridge.sendTrace(emitter, ctx, "ReAct Thought " + stepNo,
                                "react", "thought", TextUtils.truncate(thought, 200), 0L);
                    } catch (Exception ignored) {}
                }

                history = buildReActHistory(steps);

                // 终止:本轮无工具调用(纯最终答案)= 完成。Task 4 在此之前加假完成守护。
                if (!hasToolCall) {
                    log.info("ReAct 任务完成: requestId={}, steps={}", requestId, stepNo);
                    return finalResult(ctx, steps, actionTrace, thought);
                }
            }

            // 达到 maxReactSteps 仍未终止 → 返回当前最佳(降级提示)
            log.info("ReAct 达到最大步数限制: requestId={}, maxSteps={}", requestId, maxReactSteps);
            return finalResult(ctx, steps, actionTrace,
                    "已达 max-react-steps(" + maxReactSteps + "),返回当前最佳答案");
        } catch (Exception ex) {
            log.warn("ReAct 循环执行失败: requestId={}, reason={}", requestId, ex.getMessage());
            LocalSkillFallbackService.LocalSkillResult fallback =
                    localExecutionSupport.tryFallback(assembled == null ? "" : assembled.question(), true);
            return new ChatExecutionResult(
                    observePrompt(ctx),
                    "ReAct 循环异常终止: " + chatResponsePolicyService.simplifyFailureReason(ex.getMessage()),
                    actionTrace.toString(),
                    fallback != null ? fallback.fallbackAnswer() : "",
                    false
            );
        }
    }

    /**
     * 构造终态 {@link ChatExecutionResult}(Task 6 细化,本 Task 基础版)。
     * <ul>
     *   <li>observe = observePrompt</li>
     *   <li>plan = "ReAct 执行 N 步"(stream success trace 复用)</li>
     *   <li>action = Action 轨迹(每步一行)</li>
     *   <li>reflect = 最终答案({@link #resolveFinalAnswer} 直接取此字段回传用户)</li>
     * </ul>
     */
    private ChatExecutionResult finalResult(ChatContext ctx, List<ReActStep> steps,
                                            StringJoiner actionTrace, String finalAnswer) {
        return new ChatExecutionResult(
                observePrompt(ctx),
                "ReAct 执行 " + steps.size() + " 步",
                actionTrace.toString(),
                StringUtils.hasText(finalAnswer) ? finalAnswer : "ReAct 循环未产生最终答案。",
                true
        );
    }

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
     * 模型本轮输出含以 "Action:" 起首的行 → 视为发起工具调用。</p>
     * <ul>
     *   <li>原生工具调用主路径(Task 3):Spring AI 在 {@code .call()} 内完成 tool 往返,
     *       返回的是观察后的推理/最终答案,通常不再含 "Action:" 行 → 循环很快终止(多数 1 步)。</li>
     *   <li>DeepSeek 文本回退(Task 5):不走 {@code .tools()},模型在文本里输出 "Action:",
     *       引擎解析后手动执行——此判定驱动真正的多步循环。</li>
     * </ul>
     */
    private boolean hasActionLine(String thought) {
        if (!StringUtils.hasText(thought)) return false;
        for (String line : thought.split("\n")) {
            if (line.strip().toLowerCase(Locale.ROOT).startsWith("action:")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从模型输出提取 Action 描述(取首个 "Action:" 行);无工具调用时标注为最终答案。
     * 用于 history/trace 投影(Task 6 细化)。
     */
    private String describeAction(String thought, boolean hasToolCall) {
        if (!hasToolCall) {
            return "(无工具调用,给出最终答案)";
        }
        for (String line : thought.split("\n")) {
            String stripped = line.strip();
            if (stripped.toLowerCase(Locale.ROOT).startsWith("action:")) {
                return TextUtils.truncate(stripped, 400);
            }
        }
        return "(原生工具调用)";
    }

    /**
     * 描述本轮 Observation。原生工具调用时 Spring AI 在 {@code .call()} 内部执行工具并回灌结果,
     * 工具输出不外露(ModelCallExecutor 只返回文本),故此处给占位说明;
     * Task 5(DeepSeek 文本回退)将在此填入引擎手动执行的 Observation。
     */
    private String describeObservation(boolean hasToolCall) {
        if (!hasToolCall) return "";
        return "(原生工具调用已由 Spring AI 内部执行,Observation 已并入下一轮上下文)";
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
                  - 调用一个工具 → 输出 Action(工具会自动执行,结果作为下一步的 Observation)
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
     * 反射扫描工具 bean 上的 {@link Tool} 注解,列成 "- name: description" 清单。
     * <p>与 {@link AutonomousLoopEngine#renderToolList} 等价(含 CGLIB 代理穿透),复制于该类——
     * 二者都是 private,暂不抽公共基类以免越出 Task 2 范围。</p>
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
                    builder.append("- ").append(toolName).append(": ").append(description).append("\n");
                }
            }
        }
        return builder.toString().trim();
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
