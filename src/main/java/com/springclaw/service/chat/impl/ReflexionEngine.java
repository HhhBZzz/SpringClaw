package com.springclaw.service.chat.impl;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

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
 * <b>当前状态:RX-T2 prompt + ReflectionResult</b>——
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
 *   <li>{@code execute()}/{@code stream()} <b>占位</b>——循环体(Actor→Evaluator→Reflector + 假完成守护
 *       + memory 累积)留待 RX-T3 填充。</li>
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
     * 阻塞执行入口——<b>RX-T1 占位</b>:Actor→Evaluator→Reflector 循环体留待 RX-T3 填充。
     * 当前返回降级 {@link ChatExecutionResult}(本地技能兜底,对齐 {@link ReActEngine}/{@link PlanExecuteEngine}
     * 模型不可用降级路径),保证占位阶段不炸、不静默走错引擎。
     */
    @Override
    public ChatExecutionResult execute(ChatContext ctx, AgentEngine.FallbackResponder fallbackResponder) {
        log.debug("Reflexion execute 占位(RX-T3 循环未实现): requestId={}", ctx.requestId());
        AssembledContext assembled = ctx.assembled();
        LocalSkillFallbackService.LocalSkillResult fallback =
                localExecutionSupport.tryFallback(assembled == null ? "" : assembled.question(), true);
        return new ChatExecutionResult(
                observePrompt(ctx),
                "Reflexion 循环尚未实现(RX-T3 填充)",
                fallback != null ? fallback.executionDetails() : "Reflexion 引擎骨架占位",
                fallback != null ? fallback.fallbackAnswer() : "",
                false
        );
    }

    /**
     * 流式执行入口——<b>RX-T1 占位</b>:完整 SSE 生命周期(trace → 反思循环 → persist → complete,
     * 对齐 {@link ReActEngine}/{@link PlanExecuteEngine})留待 RX-T3 填充。当前直接返回 null,
     * 不发射任何事件,避免误用占位路径产生半成品流。
     */
    @Override
    public Disposable stream(ChatContext context,
                             SseEmitter emitter,
                             String lockToken,
                             AtomicBoolean lockReleased,
                             AtomicReference<Disposable> disposableRef,
                             AgentEngine.OnStreamFailure fallbackHandler) {
        log.debug("Reflexion stream 占位(RX-T3 循环未实现): requestId={}", context.requestId());
        return null;
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
