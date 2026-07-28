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
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.List;
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
 * <b>当前状态:MA-T1 骨架(接入地基)+ MA-T2 decompose/aggregate ——</b>
 * <ul>
 *   <li>{@code paradigm()} 声明 {@link AgentParadigm#MULTI_AGENT}(配合
 *       {@link AgentParadigm#isImplemented()} 与 {@code EngineSelector.LEGACY_RANK} 登记);</li>
 *   <li>{@code supports()} 仅在 {@code ctx.paradigm() == MULTI_AGENT} 时为真;</li>
 *   <li>{@code execute()}/{@code stream()} <b>占位</b>:返回降级 {@link ChatExecutionResult}(execute)/
 *       {@code null}(stream),不接入 Coordinator→Worker 循环——runMultiAgentLoop 并行执行 + tracker 合并 +
 *       假完成守护 由 MA-T3 填充。</li>
 *   <li><b>MA-T2 新增</b>:{@link SubTask}/{@link TaskDecomposition}/{@link WorkerResult} record +
 *       {@link #decompose}(BeanOutputConverter&lt;{@link TaskDecomposition}&gt; + format 注入 +
 *       {@code .responseEntity(TaskDecomposition.class)},参考 {@link PlanExecuteEngine#runPlan})+
 *       {@link #aggregate}(LLM 综合所有 Worker observation → 最终答案,返回 String,参考
 *       {@link ReflexionEngine#callReflection} 的 LLM 调用模式)+ {@link #renderDecomposePrompt}/
 *       {@link #renderAggregatePrompt}。</li>
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
     * 阻塞执行入口(<b>MA-T1 占位</b>)——Coordinator→Worker 并行循环由 MA-T3 实现。
     * 当前返回降级 {@link ChatExecutionResult}(modelEnabled=false),不接入多智能体循环。
     */
    @Override
    public ChatExecutionResult execute(ChatContext ctx, AgentEngine.FallbackResponder fallbackResponder) {
        log.warn("MultiAgentEngine.execute 占位: 多智能体循环待 MA-T3 实现, requestId={}",
                ctx == null ? "null" : ctx.requestId());
        return new ChatExecutionResult(
                observePrompt(ctx),
                "多智能体循环(待 MA-T3 实现)",
                "(占位:Coordinator→Worker 并行协作循环未接入)",
                "多智能体循环尚未实现,暂未产出答案。",
                false
        );
    }

    /**
     * 流式执行入口(<b>MA-T1 占位</b>)——多智能体 SSE 生命周期由 MA-T3 实现。当前直接返回 {@code null}。
     */
    @Override
    public Disposable stream(ChatContext context,
                             SseEmitter emitter,
                             String lockToken,
                             AtomicBoolean lockReleased,
                             AtomicReference<Disposable> disposableRef,
                             AgentEngine.OnStreamFailure fallbackHandler) {
        log.warn("MultiAgentEngine.stream 占位: 多智能体循环待 MA-T3 实现, requestId={}",
                context == null ? "null" : context.requestId());
        return null;
    }

    private String observePrompt(ChatContext ctx) {
        return ctx == null || ctx.assembled() == null ? "" : ctx.assembled().observePrompt();
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
