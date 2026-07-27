package com.springclaw.service.chat.impl;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.ai.AiProviderService;
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
 * Plan-Execute 范式引擎 — 先规划再执行(可重规划)。
 * <p>
 * 与 {@link ReActEngine}(Thought-Action-Observation 边推理边行动)不同,Plan-Execute 把"规划"
 * 与"执行"显式解耦:先让模型一次性产出结构化计划(步骤列表),再逐步执行(可调用工具),
 * 观察结果后允许有限次重规划——直到计划完成。适合步骤较确定、需先全局思考的多步任务。
 * </p>
 * <p>
 * <b>当前状态:PE-T3 Plan 阶段已实现</b>——
 * <ul>
 *   <li>{@code paradigm()} 声明 {@link AgentParadigm#PLAN_EXECUTE}(配合
 *       {@link AgentParadigm#isImplemented()} 与 {@code EngineSelector.LEGACY_RANK} 登记);</li>
 *   <li>{@code supports()} 仅在 {@code ctx.paradigm() == PLAN_EXECUTE} 时为真;</li>
 *   <li>{@link #runPlan}(<b>PE-T3 新增</b>):BeanOutputConverter&lt;{@link Plan}&gt; 结构化输出
 *       ——一次模型调用产出有序 {@code List<PlanStep>},{@link #renderPlanPrompt} 注入 format 强制 JSON 输出,
 *       经 {@code .responseEntity(Plan.class)} 取回(对齐 {@link OparLoopEngine#runPlan});</li>
 *   <li>{@code execute} / {@code stream} <b>占位</b>(Execute 留 Task 4):Task 4 填 Execute + Replan +
 *       假完成守护 + SSE 主路径,届时复用共享 {@link ExplicitToolExecutioner} / {@link ToolReflectionSupport}
 *       (PE-T1 已提取),并把 {@link #runPlan} 接入循环(失败重规划时 {@code feedback} 非空)。</li>
 * </ul>
 * 构造函数复用 {@link ReActEngine} 的 11 bean 依赖(剔除 Task 4 才接入的 {@link ExplicitToolExecutioner})
 * + {@code max-replan} 配置(clamp 到 [0,5])。
 * </p>
 */
@Service
public class PlanExecuteEngine implements AgentEngine.StreamableAgentEngine {

    private static final Logger log = LoggerFactory.getLogger(PlanExecuteEngine.class);

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
    private final int maxReplan;

    /**
     * Plan 阶段结构化输出转换器——把 LLM 输出反序列化为 {@link Plan} wrapper。
     * <p>对齐 {@link OparLoopEngine#planOutputConverter} 的 BeanOutputConverter 模式;
     * {@link #renderPlanPrompt} 注入 {@code getFormat()} 强制结构化输出,{@link #runPlan}
     * 经 {@code .responseEntity(Plan.class)} 取回实体。</p>
     * <p>Java 泛型擦除——不能直接用 {@code BeanOutputConverter<List<PlanStep>>}(元素类型在运行期丢失),
     * 故包一层 {@link Plan} wrapper record(与 OparLoopEngine 的 {@link PlanResult} 单对象模式同构)。</p>
     */
    private final BeanOutputConverter<Plan> planOutputConverter = new BeanOutputConverter<>(Plan.class);

    public PlanExecuteEngine(AiProviderService aiProviderService,
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
                             @Value("${springclaw.chat.max-replan:2}") int maxReplan) {
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
        this.maxReplan = Math.max(0, Math.min(maxReplan, 5));
    }

    @Override
    public String name() {
        return "plan-execute-loop";
    }

    @Override
    public AgentParadigm paradigm() {
        return AgentParadigm.PLAN_EXECUTE;
    }

    @Override
    public int priority() {
        return 6;
    }

    @Override
    public boolean supports(ChatContext ctx) {
        return ctx != null && ctx.paradigm() == AgentParadigm.PLAN_EXECUTE;
    }

    /**
     * 阻塞执行入口——<b>占位</b>(Plan/Execute 留 Task 3/4)。
     * <p>当前仅返回降级 {@link ChatExecutionResult}:调用 {@code fallbackResponder} 给出兜底回答,
     * 标注"Plan-Execute 占位",不进入 Plan/Execute 循环。Task 3/4 将替换为真正的 Plan→Execute→Replan 循环
     * (复用 {@link ExplicitToolExecutioner} 手动执行工具 + 假完成守护,对齐 {@link ReActEngine#execute})。</p>
     */
    @Override
    public ChatExecutionResult execute(ChatContext ctx, AgentEngine.FallbackResponder fallbackResponder) {
        log.warn("PlanExecuteEngine.execute 占位实现(Plan/Execute 留 Task 3/4): requestId={}", ctx.requestId());
        String observe = ctx.assembled() == null ? "" : ctx.assembled().observePrompt();
        String fallback = "";
        try {
            fallback = fallbackResponder.respond("plan-execute-placeholder", ctx.assembled());
        } catch (Exception ex) {
            log.warn("PlanExecuteEngine 占位 fallback 调用失败: requestId={}, reason={}",
                    ctx.requestId(), ex.getMessage());
        }
        return new ChatExecutionResult(
                observe,
                "Plan-Execute 占位(待 Task 3/4 实现)",
                "",
                fallback,
                false
        );
    }

    /**
     * 流式执行入口——<b>占位</b>(Plan/Execute 留 Task 3/4)。
     * <p>当前直接返回 {@code null}(不驱动 SSE 生命周期,不持久化)。Task 4 将填充完整的
     * Plan→Execute→Replan SSE 主路径(对齐 {@link ReActEngine#stream}:trace → 循环 → 发送最终答案 →
     * persist → reportResult → releaseLockOnce → completeEmitter,异常委托 {@code fallbackHandler})。</p>
     */
    @Override
    public Disposable stream(ChatContext context,
                             SseEmitter emitter,
                             String lockToken,
                             AtomicBoolean lockReleased,
                             AtomicReference<Disposable> disposableRef,
                             AgentEngine.OnStreamFailure fallbackHandler) {
        log.warn("PlanExecuteEngine.stream 占位实现(Plan/Execute 留 Task 3/4): requestId={}",
                context.requestId());
        return null;
    }

    // === PE-T3: Plan 阶段(BeanOutputConverter 结构化 List<PlanStep>)===

    /**
     * Plan 阶段产出的单个步骤——Execute 阶段(Task 4)逐步消费。
     * <p>只有 {@code stepText} 一个字段:模型自然语言描述一个明确动作(检索/调用工具/分析/综合)。
     * 保持最小化,避免过早结构化动作类型/参数——具体执行交由 Task 4 的 ExplicitToolExecutioner 解析。</p>
     */
    public record PlanStep(String stepText) {
    }

    /**
     * BeanOutputConverter 结构化输出 wrapper——包裹 {@code List<PlanStep>}。
     * <p>Java 泛型擦除下 {@code BeanOutputConverter<List<PlanStep>>} 无法保留元素类型,
     * 故用本 wrapper record 包一层(对齐 {@link OparLoopEngine} 的 {@link PlanResult} 单对象模式)。
     * Spring AI 的 BeanOutputConverter 支持反序列化 record + 嵌套 {@code List<PlanStep>}。</p>
     */
    public record Plan(List<PlanStep> steps) {
    }

    /**
     * Plan 阶段——一次模型调用,经 BeanOutputConverter 结构化产出有序 {@link PlanStep} 列表。
     * <p>结构对齐 {@link OparLoopEngine#runPlan} 的 BeanOutputConverter + {@code .responseEntity} 模式:
     * 渲染含 format 说明的 system prompt → {@code ModelCallExecutor.executeChat} 携
     * {@code .responseEntity(Plan.class)} → 取 {@code entity.steps()}。失败降级为空列表
     * (Execute 阶段 Task 4 自行处理空计划/重规划/兜底,本方法不向调用方抛)。</p>
     * <p>{@code feedback} 非空表示重规划——携带上一次执行失败的反馈注入 prompt(首次为空)。</p>
     */
    List<PlanStep> runPlan(ChatContext ctx, Object[] tools, String feedback) {
        try {
            String systemPrompt = renderPlanPrompt(ctx, tools, feedback);
            ModelCallExecutor.ModelCallResult<Plan> callResult = modelCallExecutor.executeChat(
                    ctx.activeClient(),
                    "plan-execute-plan",
                    new ModelCallExecutor.ChatRequestContext(
                            ctx.requestId(),
                            ctx.assembled() == null ? "" : ctx.assembled().sessionKey(),
                            ctx.channel(),
                            ctx.userId()
                    ),
                    true,
                    client -> {
                        var response = conversationAdvisorSupport.apply(
                                        client.chatClient().prompt()
                                                .system(systemPrompt)
                                                .user(TypedContextPromptRenderer.question(ctx)),
                                        ctx.assembled() == null ? "" : ctx.assembled().sessionKey(),
                                        ctx.userId())
                                .call()
                                .responseEntity(Plan.class);
                        return new ModelCallExecutor.ChatOperationResult<>(
                                response.entity(),
                                response.response()
                        );
                    }
            );
            Plan plan = callResult.value();
            return plan == null || plan.steps() == null ? List.of() : plan.steps();
        } catch (Exception ex) {
            log.warn("Plan 阶段失败,降级为空计划: requestId={}, reason={}",
                    ctx.requestId(), ex.getMessage());
            return List.of();
        }
    }

    /**
     * 渲染 Plan 阶段的 system prompt——要求 LLM 把问题分解为有序可执行步骤,输出 Plan JSON。
     * <p>结构模仿 {@link ReActEngine#renderReActPrompt}:{{INJECTION}}({@link TypedContextPromptRenderer#promptPrefix})
     * + {{QUESTION}}({@link TypedContextPromptRenderer#question}) + {{TOOLS}}({@link ToolReflectionSupport#renderToolList})
     * + {{FEEDBACK}}(重规划时携带失败反馈,首次为空) + {{FORMAT}}({@link BeanOutputConverter#getFormat} 注入,
     * 强制结构化输出)。整体作 system prompt,用户问题单独作 user 消息(与 ReActEngine 一致)。</p>
     */
    String renderPlanPrompt(ChatContext ctx, Object[] tools, String feedback) {
        String template = """
                {{INJECTION}}你是 Plan-Execute Agent 的 Planner 阶段,请把用户问题分解为有序、可直接执行的步骤列表。

                # 用户问题
                {{QUESTION}}

                # 可用工具(执行阶段可调用)
                {{TOOLS}}

                # 反馈(重规划时携带上一次执行失败的反馈,首次为空)
                {{FEEDBACK}}

                # 输出要求
                1) 聚焦"规划"——只列出步骤,不实际执行;
                2) 每个步骤描述一个明确的动作(检索/调用工具/分析/综合总结);
                3) 步骤数量精简(通常 1-4 步),复杂问题可更多;
                4) 步骤有序、可独立执行,后一步可依赖前一步的结果;
                5) 输出必须严格遵循下面的格式说明,不要附加任何额外文本。

                # 输出格式说明
                {{FORMAT}}
                """;
        return template
                .replace("{{INJECTION}}", TypedContextPromptRenderer.promptPrefix(ctx))
                .replace("{{QUESTION}}", StringUtils.hasText(TypedContextPromptRenderer.question(ctx))
                        ? TypedContextPromptRenderer.question(ctx).trim() : "")
                .replace("{{TOOLS}}", ToolReflectionSupport.renderToolList(tools))
                .replace("{{FEEDBACK}}", StringUtils.hasText(feedback) ? feedback.trim() : "（首次规划，暂无反馈）")
                .replace("{{FORMAT}}", planOutputConverter.getFormat());
    }
}
