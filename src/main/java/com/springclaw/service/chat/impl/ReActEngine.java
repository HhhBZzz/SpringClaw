package com.springclaw.service.chat.impl;

import com.springclaw.common.util.TextUtils;
import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.guard.ChatGuardService;
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
import java.util.List;
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
