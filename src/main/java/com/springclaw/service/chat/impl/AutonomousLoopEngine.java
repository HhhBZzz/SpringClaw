package com.springclaw.service.chat.impl;

import com.springclaw.common.util.TextUtils;
import com.springclaw.runtime.bridge.LegacyLifecycleObserver;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.chat.LocalSkillFallbackService;
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
 * 自主 Agent 循环引擎 — SpringClaw 从 "能看的 chatbot" 进化为 "能干活的 agent" 的核心。
 *
 * 实现 StreamableAgentEngine 接口，自主管理 SSE 生命周期。
 *
 * 关键改进：假完成防护机制
 * - 使用 AutonomousExecutionTracker 追踪真实的工具调用和副作用
 * - 按 riskLevel 区分完成条件：read-only 可纯文本完成，write-needed 必须有真实文件变化
 * - 模型声称 TASK_COMPLETE 但缺少副作用证据时，拒绝完成并提示模型继续操作
 *
 * 设计参考：Codex (shell + apply_patch 循环)、Claude Code (grep + read + edit + bash 循环)、Hermes (execute_code + 70+ 工具循环)。
 */
@Service
public class AutonomousLoopEngine implements AgentEngine.StreamableAgentEngine {

    private static final Logger log = LoggerFactory.getLogger(AutonomousLoopEngine.class);

    private static final String TASK_COMPLETE_MARKER = "TASK_COMPLETE";
    private static final String TASK_FAILED_MARKER = "TASK_FAILED";

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
    private final LegacyLifecycleObserver lifecycleObserver;
    private final boolean localFallbackEnabled;
    private final int maxAutonomousSteps;

    public AutonomousLoopEngine(AiProviderService aiProviderService,
                                ToolOrchestrator toolOrchestrator,
                                ModelTransportGuardService modelTransportGuardService,
                                ModelCallExecutor modelCallExecutor,
                                ConversationAdvisorSupport conversationAdvisorSupport,
                                LocalExecutionSupport localExecutionSupport,
                                ChatResponsePolicyService chatResponsePolicyService,
                                SseEventBridge sseEventBridge,
                                ChatResultPersister chatResultPersister,
                                ChatGuardService chatGuardService,
                                LegacyLifecycleObserver lifecycleObserver,
                                @Value("${springclaw.chat.local-fallback-enabled:true}") boolean localFallbackEnabled,
                                @Value("${springclaw.chat.max-autonomous-steps:5}") int maxAutonomousSteps) {
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
        this.localFallbackEnabled = localFallbackEnabled;
        this.maxAutonomousSteps = Math.max(1, Math.min(maxAutonomousSteps, 15));
    }

    @Override
    public String name() {
        return "autonomous-loop";
    }

    @Override
    public int priority() {
        return 2;
    }

    @Override
    public boolean supports(ChatContext ctx) {
        if (ctx == null) return false;
        if (!"opar".equals(ctx.executionMode())) return false;
        if (ctx.activeClient() == null || !ctx.activeClient().available()) return false;
        AgentDecision decision = ctx.decision();
        if (decision == null || decision.isGeneral()) return false;
        // 分级路由：按 riskLevel 决定是否走自主循环
        // write/side_effect/dangerous 必须走自主循环（无论 intent 是什么）
        // read 只读任务不走自主循环（走 opar-loop 单步快速回答）
        String riskLevel = decision.riskLevel();
        return "write".equals(riskLevel)
                || "side_effect".equals(riskLevel)
                || "dangerous".equals(riskLevel);
    }

    @Override
    public ChatExecutionResult execute(ChatContext ctx, FallbackResponder fallbackResponder) {
        return runAutonomousLoop(ctx, null, null);
    }

    @Override
    public Disposable stream(ChatContext context,
                             SseEmitter emitter,
                             String lockToken,
                             AtomicBoolean lockReleased,
                             AtomicReference<Disposable> disposableRef,
                             OnStreamFailure fallbackHandler) {
        try {
            sseEventBridge.sendTrace(emitter, context, "自主循环", "agent", "started",
                    "进入自主 Agent 循环执行。", 0L);
            sseEventBridge.sendStatus(emitter, "自主 Agent 正在执行");

            ChatExecutionResult result = runAutonomousLoop(context, emitter, context.requestId());

            // 循环完成 → 发送最终回答
            String finalAnswer = resolveFinalAnswer(result);
            sseEventBridge.sendAnswerChunks(emitter, finalAnswer);
            chatResultPersister.persist(context, finalAnswer, result);
            reportResult(context, result, finalAnswer);

            sseEventBridge.sendTrace(emitter, context, "自主循环", "agent", "success",
                    "自主循环执行 " + result.action() + "。", 0L);
            sseEventBridge.sendTrace(emitter, context, "完成", "final", "success",
                    "已生成最终回答。", 0L);
            releaseLockOnce(context, lockToken, lockReleased);
            sseEventBridge.completeEmitter(emitter);
        } catch (Exception ex) {
            log.warn("自主循环 SSE 执行失败: sessionKey={}, reason={}",
                    context.assembled().sessionKey(), ex.getMessage());
            try {
                String simplifiedReason = chatResponsePolicyService.simplifyFailureReason(ex.getMessage());
                sseEventBridge.sendTrace(emitter, context, "自主循环", "agent", "failed", simplifiedReason, 0L);
            } catch (Exception ignored) {}
            fallbackHandler.handle(context, ex, emitter, lockToken, lockReleased);
        }
        return null;
    }

    private void reportResult(
            ChatContext context,
            ChatExecutionResult result,
            String answer
    ) {
        if (lifecycleObserver == null) {
            return;
        }
        try {
            lifecycleObserver.resultReturned(context, result, answer, Instant.now());
        } catch (RuntimeException ex) {
            log.error(
                    "canonical lifecycle projection failed after autonomous persistence, requestId={}",
                    context.requestId(),
                    ex
            );
        }
    }

    /**
     * 自主循环核心执行逻辑。
     *
     * 关键改进：每一步使用 AutonomousExecutionTracker 追踪真实的工具调用，
     * 模型声称完成时验证副作用证据，防止"假完成"。
     */
    private ChatExecutionResult runAutonomousLoop(ChatContext ctx, SseEmitter emitter, String requestId) {
        AiProviderService.ActiveChatClient activeClient = ctx.activeClient();
        AssembledContext assembled = ctx.assembled();
        AgentDecision decision = ctx.decision();
        if (requestId == null) requestId = ctx.requestId();
        String riskLevel = decision != null ? decision.riskLevel() : "read";

        // 模型不可用 → 降级
        if (!modelTransportGuardService.isModelCallEnabled(activeClient)) {
            LocalSkillFallbackService.LocalSkillResult fallback = localExecutionSupport.tryFallback(assembled.question(), localFallbackEnabled);
            return new ChatExecutionResult(
                    assembled.observePrompt(),
                    modelTransportGuardService.disabledModelPlanReason(activeClient),
                    fallback != null ? fallback.executionDetails() : modelTransportGuardService.disabledModelActionReason(activeClient),
                    fallback != null ? fallback.fallbackAnswer() : "",
                    false
            );
        }

        // 自主循环核心
        final Object[] tools = toolOrchestrator.selectAutonomousTools(ctx.channel(), ctx.userId(), decision);
        final boolean allowFailover = isSafeToRetry(tools);
        final String systemPrompt = ctx.systemPrompt();
        StringJoiner actionTrace = new StringJoiner("\n\n");
        List<String> stepSummaries = new ArrayList<>();

        // 执行追踪器 — 每一步关联同一个 tracker，累积真实工具调用记录
        AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();

        ToolExecutionContext toolContext = new ToolExecutionContext(
                assembled.sessionKey(),
                assembled.channel(),
                assembled.userId(),
                requestId,
                "AUTONOMOUS",
                requestId,
                ctx.roleCode()
        );

        try (ToolExecutionContextHolder.Scope scope = ToolExecutionContextHolder.open(toolContext)) {
            // 将 tracker 注册到线程上下文，让 WorkspaceEditToolPack 的 @Tool 方法可以记录
            ToolExecutionContextHolder.setTracker(tracker);

            String initialPrompt = renderAutonomousPrompt(ctx, tools, "", riskLevel);

            for (int stepNo = 1; stepNo <= maxAutonomousSteps; stepNo++) {
                log.info("自主循环步骤 {}/{}: requestId={}, riskLevel={}, toolsCount={}, trackerState={}",
                        stepNo, maxAutonomousSteps, requestId, riskLevel, tools.length,
                        tracker.hasWriteToolCall() ? "hasWrite" : "noWrite");

                // SSE 进度事件
                if (emitter != null) {
                    try {
                        sseEventBridge.sendStatus(emitter, "自主循环步骤 " + stepNo + "/" + maxAutonomousSteps);
                    } catch (Exception e) {
                        log.warn("SSE 进度事件发送失败（可能客户端已断开）: stepNo={}", stepNo);
                    }
                }

                final String promptForStep = initialPrompt;
                ModelCallExecutor.ModelCallResult<String> callResult = modelCallExecutor.executeChat(
                        activeClient,
                        "autonomous-act-" + stepNo,
                        new ModelCallExecutor.ChatRequestContext(
                                requestId, assembled.sessionKey(), assembled.channel(), assembled.userId()
                        ),
                        allowFailover,
                        client -> {
                            var requestSpec = client.chatClient().prompt()
                                    .system(systemPrompt)
                                    .user(promptForStep);
                            if (tools != null && tools.length > 0) {
                                requestSpec = requestSpec.tools(tools);
                            }
                            var response = conversationAdvisorSupport.apply(
                                            requestSpec,
                                            assembled.sessionKey(),
                                            assembled.userId())
                                    .call()
                                    .chatResponse();
                            String text = ModelCallExecutor.extractText(response);
                            return new ModelCallExecutor.ChatOperationResult<>(text, response);
                        }
                );

                String stepOutput = callResult.value();
                activeClient = callResult.client();

                if (!StringUtils.hasText(stepOutput)) {
                    log.warn("自主循环步骤 {} 模型输出为空，终止循环: requestId={}", stepNo, requestId);
                    break;
                }

                actionTrace.add("[Step " + stepNo + "]");
                actionTrace.add(TextUtils.truncate(stepOutput, 600));
                stepSummaries.add(stepOutput);

                // SSE 步骤完成事件
                if (emitter != null) {
                    try {
                        sseEventBridge.sendTrace(emitter, ctx, "自主循环步骤 " + stepNo,
                                "agent", "success",
                                "步骤 " + stepNo + " 完成（" + TextUtils.truncate(stepOutput, 80) + "）",
                                0L);
                    } catch (Exception ignored) {}
                }

                // === 完成条件判断（按 riskLevel 区分） ===

                // 1. 检查 TASK_FAILED — 任何级别都可以直接失败
                if (isTaskFailed(stepOutput)) {
                    log.info("自主循环任务失败: requestId={}, steps={}, marker found", requestId, stepNo);
                    break;
                }

                // 2. 检查 TASK_COMPLETE — read-only 可直接完成，write-needed 必须验证
                if (isTaskComplete(stepOutput)) {
                    if ("read".equals(riskLevel)) {
                        // read-only：TASK_COMPLETE 可以直接完成
                        log.info("自主循环任务完成(read-only): requestId={}, steps={}", requestId, stepNo);
                        break;
                    }
                    // write-needed / side_effect / dangerous：必须验证真实副作用
                    if (tracker.satisfiesCompletionCondition(riskLevel)) {
                        log.info("自主循环任务完成(verified): requestId={}, steps={}, riskLevel={}, " +
                                "hasWrite={}, createdFiles={}, modifiedFiles={}, hasCmd={}, hasVerified={}",
                                requestId, stepNo, riskLevel,
                                tracker.hasWriteToolCall(), tracker.getCreatedFiles(),
                                tracker.getModifiedFiles(), tracker.hasRunCommandCall(),
                                tracker.hasVerifiedSideEffect());
                        break;
                    }
                    // 假完成：模型声称完成但缺少真实副作用证据
                    log.warn("自主循环假完成拦截: requestId={}, steps={}, riskLevel={}, " +
                            "hasWrite={}, createdFiles={}, hasCmd={}",
                            requestId, stepNo, riskLevel,
                            tracker.hasWriteToolCall(), tracker.getCreatedFiles(),
                            tracker.hasRunCommandCall());
                    // 不终止循环 — 将拒绝提示加入下一轮 prompt
                    String rejectionHint = tracker.renderFakeCompletionRejection(riskLevel);
                    initialPrompt = renderAutonomousPrompt(ctx, tools,
                            buildStepHistory(stepSummaries) + "\n\n" + rejectionHint, riskLevel);
                    // SSE 通知前端：假完成被拦截
                    if (emitter != null) {
                        try {
                            sseEventBridge.sendTrace(emitter, ctx, "假完成拦截",
                                    "guard", "warning",
                                    "模型声称完成但缺少真实操作证据，继续执行。",
                                    0L);
                        } catch (Exception ignored) {}
                    }
                    continue; // 继续循环，不终止
                }

                // 3. 早停机制 — 只允许 read-only 任务使用
                if ("read".equals(riskLevel) && isLikelyFinalAnswer(stepOutput, stepNo)) {
                    log.info("自主循环早停(read-only): requestId={}, steps={}, 模型输出看起来是最终回答",
                            requestId, stepNo);
                    break;
                }

                // 4. write-needed/side_effect/dangerous 禁止纯文本早停
                // 即使模型输出了一段看起来像最终回答的文本，
                // 如果 tracker 没有记录任何工具调用，说明模型只是在用文字描述操作，
                // 必须继续循环直到真正调用工具
                if (!"read".equals(riskLevel) && !tracker.hasAnyToolCall() && stepNo >= 2) {
                    // 模型已经至少跑了 2 步但没调用任何工具
                    // 加入提示要求模型必须使用工具
                    if (isLikelyFinalAnswer(stepOutput, stepNo)) {
                        log.warn("自主循环纯文本拦截: requestId={}, steps={}, riskLevel={}, " +
                                "模型输出看起来像最终回答但没有工具调用",
                                requestId, stepNo, riskLevel);
                        String toolHint = "你已输出了文字描述，但没有调用任何工具执行实际操作。"
                                + "请使用 workspaceWriteFile / workspaceApplyPatch / workspaceRunCommand 等工具完成实际修改。"
                                + "不要只用纯文本描述你做了什么。";
                        initialPrompt = renderAutonomousPrompt(ctx, tools,
                                buildStepHistory(stepSummaries) + "\n\n" + toolHint, riskLevel);
                        continue;
                    }
                }

                // 5. 最大步数限制
                if (stepNo == maxAutonomousSteps) {
                    log.info("自主循环达到最大步数限制: requestId={}, maxSteps={}, riskLevel={}",
                            requestId, maxAutonomousSteps, riskLevel);
                    break;
                }

                initialPrompt = renderAutonomousPrompt(ctx, tools,
                        buildStepHistory(stepSummaries), riskLevel);
            }

            // 清理 tracker
            ToolExecutionContextHolder.clearTracker();

        } catch (Exception ex) {
            ToolExecutionContextHolder.clearTracker();
            log.warn("自主循环执行失败: requestId={}, reason={}", requestId, ex.getMessage());
            LocalSkillFallbackService.LocalSkillResult fallback = localExecutionSupport.tryFallback(assembled.question(), localFallbackEnabled);
            return new ChatExecutionResult(
                    assembled.observePrompt(),
                    "自主循环异常终止: " + chatResponsePolicyService.simplifyFailureReason(ex.getMessage()),
                    actionTrace.toString(),
                    fallback != null ? fallback.fallbackAnswer() : "",
                    false
            );
        }

        String reflectContent = "自主循环执行 " + stepSummaries.size() + " 步完成。\n"
                + "步骤概要：\n" + buildStepHistory(stepSummaries);
        return new ChatExecutionResult(
                assembled.observePrompt(),
                "自主循环执行 " + stepSummaries.size() + " 步",
                actionTrace.toString(),
                reflectContent,
                true
        );
    }

    private String resolveFinalAnswer(ChatExecutionResult result) {
        if (StringUtils.hasText(result.reflect())) {
            return result.reflect();
        }
        if (!result.modelEnabled()) {
            return "自主循环执行完成，但模型不可用。";
        }
        return "自主循环执行完成，共 " + result.action() + "。";
    }

    private void releaseLockOnce(ChatContext context, String lockToken, AtomicBoolean lockReleased) {
        if (lockReleased.compareAndSet(false, true) && lockToken != null) {
            chatGuardService.releaseSessionLock(context.assembled().sessionKey(), lockToken);
        }
    }

    // === Prompt 渲染 ===

    String renderAutonomousPrompt(ChatContext ctx, Object[] tools, String history, String riskLevel) {
        String toolList = renderToolList(tools);
        String completionRule = renderCompletionRule(riskLevel);
        String injection = ctx == null ? "" : ctx.contextInjection().renderForPrompt();
        String question = ctx == null || ctx.assembled() == null ? "" : ctx.assembled().question();
        String template = """
                {{INJECTION}}# SpringClaw 自主 Agent 执行循环

                你是一个自主执行 Agent，可以自主决定每一步做什么来完成任务。
                你有完整的工具集可用，每一步自主选择调用哪个工具。
                观察工具输出后，自主判断下一步是继续操作还是任务已完成。

                # 任务
                {{QUESTION}}

                # 可用工具
                {{TOOLS}}

                # 完成规则
                {{COMPLETION_RULE}}

                # 执行规则
                1. 每一步自主决定调用哪个工具——先读、再改、再验证，按需组合
                2. 调用工具后仔细观察输出结果，根据结果决定下一步
                3. 修改代码前先读取文件确认当前内容，避免盲目修改
                4. 修改代码后用 workspaceRunCommand 验证（编译检查、跑测试）
                5. 如果验证失败，读取错误信息，再次修改，循环直到成功
                6. 任务完成后，在回答开头标记 {{COMPLETE}} 并给出简要总结
                7. 任务无法完成时，在回答开头标记 {{FAILED}} 并说明原因
                8. 不要输出空内容，每一步都要有实际操作或判断
                9. 重要：不要只用纯文本描述你做了什么——必须实际调用工具执行操作

                # 历史步骤
                {{HISTORY}}
                """;
        return template
                .replace("{{INJECTION}}", injection)
                .replace("{{QUESTION}}", question == null ? "" : question.trim())
                .replace("{{TOOLS}}", toolList)
                .replace("{{COMPLETION_RULE}}", completionRule)
                .replace("{{HISTORY}}", StringUtils.hasText(history) ? history : "（第一轮，暂无历史）")
                .replace("{{COMPLETE}}", TASK_COMPLETE_MARKER)
                .replace("{{FAILED}}", TASK_FAILED_MARKER);
    }

    /**
     * 按 riskLevel 渲染不同的完成规则提示。
     *
     * 这让模型明确知道什么样的完成条件是可接受的，
     * 避免模型只用纯文本声称完成而不执行任何实际操作。
     */
    private String renderCompletionRule(String riskLevel) {
        if ("read".equals(riskLevel)) {
            return "当前是只读分析任务。你可以直接给出分析结果并标记 " + TASK_COMPLETE_MARKER + "。";
        }
        if ("write".equals(riskLevel)) {
            return "当前是写操作任务（创建文件、修改代码等）。\n"
                    + "⚠️ 完成条件：你必须实际调用 workspaceWriteFile 或 workspaceApplyPatch 执行修改，"
                    + "然后用 workspaceRunCommand 验证结果。\n"
                    + "只输出文字描述" + TASK_COMPLETE_MARKER + " 而不实际修改文件，系统会拒绝完成并要求你继续操作。";
        }
        if ("side_effect".equals(riskLevel)) {
            return "当前是命令执行任务（运行测试、编译检查等）。\n"
                    + "⚠️ 完成条件：你必须实际调用 workspaceRunCommand 执行命令并观察结果。\n"
                    + "只输出文字描述而不实际执行命令，系统会拒绝完成并要求你继续操作。";
        }
        if ("dangerous".equals(riskLevel)) {
            return "当前是高风险操作任务。\n"
                    + "⚠️ 完成条件：你必须实际执行操作并用工具验证结果。\n"
                    + "只输出文字描述而不实际执行操作，系统会拒绝完成并要求你继续操作。";
        }
        return "请实际执行操作后再标记 " + TASK_COMPLETE_MARKER + "。";
    }

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

    private String buildStepHistory(List<String> summaries) {
        if (summaries == null || summaries.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < summaries.size(); i++) {
            builder.append("Step ").append(i + 1).append(": ")
                    .append(TextUtils.truncate(summaries.get(i), 400))
                    .append("\n\n");
        }
        return builder.toString().trim();
    }

    private boolean isTaskComplete(String output) {
        return isMarkerPresent(output, TASK_COMPLETE_MARKER);
    }

    private boolean isTaskFailed(String output) {
        return isMarkerPresent(output, TASK_FAILED_MARKER);
    }

    /**
     * 通用完成/失败标记识别 — 对模型输出做归一化后检测。
     *
     * 归一化规则：
     * 1. 按行扫描，只检查行首（避免正文中间误匹配）
     * 2. trim + 大小写不敏感
     * 3. 去掉反引号
     * 4. 去掉粗体/斜体符号 (* 和 **)
     * 5. 去掉开头 Markdown heading (# ## ###)
     * 6. 去掉开头列表符号 (- * +)
     * 7. 去掉开头数字列表 (1. 2) 等)
     * 8. 容忍开头 emoji/Unicode 符号
     * 9. 容忍结尾标点 (. 。 ! ！ : ：)
     * 10. 完整 token 匹配 — 标记后必须紧跟空白/行尾/标点，避免 TASK_COMPLETED 误匹配
     */
    static boolean isMarkerPresent(String output, String marker) {
        if (!StringUtils.hasText(output)) return false;
        String markerLower = marker.toLowerCase(Locale.ROOT);
        for (String line : output.split("\n")) {
            String normalized = normalizeMarkerLine(line);
            if (normalized.startsWith(markerLower)) {
                // 完整 token 匹配：标记后必须是行尾、空白或标点，不是字母/数字/下划线
                int end = markerLower.length();
                if (end == normalized.length()) return true; // 行尾
                char next = normalized.charAt(end);
                if (Character.isLetterOrDigit(next) || next == '_' || next == '-') continue; // e.g. TASK_COMPLETED
                return true;
            }
        }
        return false;
    }

    static String normalizeMarkerLine(String line) {
        String s = line.strip();
        // 去掉反引号
        s = s.replace("`", "");
        // 去掉成对的粗体/斜体标记（**text** → text, *text* → text, __text__ → text）
        // 不用全局删除 _ 和 *，因为 TASK_COMPLETE 本身含下划线
        s = s.replaceAll("\\*\\*(.+?)\\*\\*", "$1");  // **bold**
        s = s.replaceAll("\\*(.+?)\\*", "$1");        // *italic*
        s = s.replaceAll("__(.+?)__", "$1");           // __bold__
        s = s.strip();
        // 去掉 Markdown heading (## ### ...)
        s = s.replaceAll("^#{1,6}\\s*", "");
        // 去掉列表符号 (- * + 后跟空格)
        s = s.replaceAll("^[-*+]\\s+", "");
        // 去掉数字列表 (1. 2) 等)
        s = s.replaceAll("^\\d+[.)]\\s+", "");
        // 去掉开头 emoji/符号
        // 只针对常见 emoji 范围：Miscellaneous Symbols (2600-26FF)、Dingbats (2700-27BF)、
        // Emoji (1F300-1F9FF)、Variation Selectors 等
        // 不能用 \\p{So} 因为 Java 17 中匹配范围过宽会吃掉 ASCII 字符
        s = s.replaceAll("^[☀-➿︀-️‍⃣🌀-🛿🤀-🧿]+\\s*", "");
        // 去掉结尾标点
        s = s.replaceAll("[.。!！:：]$", "");
        s = s.strip().toLowerCase(Locale.ROOT);
        return s;
    }

    /**
     * 早停检测 — 只允许 read-only 任务使用。
     *
     * 条件：
     * 1. riskLevel=read（只读分析任务）
     * 2. 输出足够长（≥200字符），说明模型给出了有实质内容的回答
     * 3. 不是第1步（第1步可能是模型在解释计划）
     * 4. 不含继续操作的提示词
     */
    private boolean isLikelyFinalAnswer(String output, int stepNo) {
        if (stepNo <= 1) return false;
        if (!StringUtils.hasText(output)) return false;
        String trimmed = output.trim();
        if (trimmed.length() < 200) return false;
        String lower = trimmed.toLowerCase();
        if (lower.contains("接下来") || lower.contains("下一步") || lower.contains("然后我会")
                || lower.contains("next, i") || lower.contains("next step")) {
            return false;
        }
        return true;
    }

    private boolean isSafeToRetry(Object[] tools) {
        if (tools == null) return true;
        for (Object tool : tools) {
            if (tool instanceof com.springclaw.tool.pack.WorkspaceEditToolPack) return false;
            if (tool instanceof com.springclaw.tool.pack.ScriptSkillToolPack) return false;
        }
        return true;
    }
}
