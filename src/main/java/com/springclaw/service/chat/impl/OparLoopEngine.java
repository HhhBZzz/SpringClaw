package com.springclaw.service.chat.impl;

import com.springclaw.common.util.TextUtils;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.agent.AgentParadigm;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.pack.FileToolPack;
import com.springclaw.tool.pack.LocalFilesystemToolPack;
import com.springclaw.tool.pack.ScriptSkillToolPack;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 负责 OPAR 中 Observe/Plan/Act 三段的执行主循环。
 */
@Service
public class OparLoopEngine implements AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(OparLoopEngine.class);

    private final AiProviderService aiProviderService;
    private final ToolOrchestrator toolOrchestrator;
    private final LocalSkillFallbackService localSkillFallbackService;
    private final LocalExecutionNarrator localExecutionNarrator;
    private final ModelTransportGuardService modelTransportGuardService;
    private final ModelCallExecutor modelCallExecutor;
    private final OparContextAwareSupport contextAwareSupport;
    private final OparPromptSupport promptSupport;
    private final ConversationAdvisorSupport conversationAdvisorSupport;
    private final LocalExecutionSupport localExecutionSupport;
    private final BeanOutputConverter<PlanResult> planOutputConverter = new BeanOutputConverter<>(PlanResult.class);
    private final boolean localFallbackEnabled;
    private final boolean localFallbackFirst;
    private final int maxAgentSteps;

    public OparLoopEngine(AiProviderService aiProviderService,
                          ToolOrchestrator toolOrchestrator,
                          LocalSkillFallbackService localSkillFallbackService,
                          LocalExecutionNarrator localExecutionNarrator,
                          ModelTransportGuardService modelTransportGuardService,
                          ModelCallExecutor modelCallExecutor,
                          OparContextAwareSupport contextAwareSupport,
                          OparPromptSupport promptSupport,
                          ConversationAdvisorSupport conversationAdvisorSupport,
                          LocalExecutionSupport localExecutionSupport,
                          @Value("${springclaw.chat.local-fallback-enabled:true}") boolean localFallbackEnabled,
                          @Value("${springclaw.chat.local-fallback-first:true}") boolean localFallbackFirst,
                          @Value("${springclaw.chat.max-steps:3}") int maxAgentSteps) {
        this.aiProviderService = aiProviderService;
        this.toolOrchestrator = toolOrchestrator;
        this.localSkillFallbackService = localSkillFallbackService;
        this.localExecutionNarrator = localExecutionNarrator;
        this.modelTransportGuardService = modelTransportGuardService;
        this.modelCallExecutor = modelCallExecutor;
        this.contextAwareSupport = contextAwareSupport;
        this.promptSupport = promptSupport;
        this.conversationAdvisorSupport = conversationAdvisorSupport;
        this.localExecutionSupport = localExecutionSupport;
        this.localFallbackEnabled = localFallbackEnabled;
        this.localFallbackFirst = localFallbackFirst;
        this.maxAgentSteps = Math.max(1, Math.min(maxAgentSteps, 6));
    }

    @Override
    public String name() {
        return "opar-loop";
    }

    @Override
    public AgentParadigm paradigm() {
        return AgentParadigm.OPAR;
    }

    @Override
    public int priority() {
        return 3;
    }

    @Override
    public boolean supports(ChatContext ctx) {
        if (ctx == null) return false;
        return "opar".equals(ctx.executionMode())
                || (ctx.routingReason() != null && ctx.routingReason().contains("自动升级"));
    }

    @Override
    public ChatExecutionResult execute(ChatContext ctx, AgentEngine.FallbackResponder fallbackResponder) {
        return runLoop(
                ctx.activeClient(),
                ctx.systemPrompt(),
                ctx.assembled(),
                ctx.requestId(),
                fallbackResponder,
                ctx.decision(),
                ctx
        );
    }

    public ChatExecutionResult runLoop(AiProviderService.ActiveChatClient activeClient,
                                       String systemPrompt,
                                       AssembledContext assembled,
                                       String requestId,
                                       AgentEngine.FallbackResponder fallbackResponder) {
        return runLoop(activeClient, systemPrompt, assembled, requestId, fallbackResponder, null);
    }

    public ChatExecutionResult runLoop(AiProviderService.ActiveChatClient activeClient,
                                       String systemPrompt,
                                       AssembledContext assembled,
                                       String requestId,
                                       AgentEngine.FallbackResponder fallbackResponder,
                                       AgentDecision decision) {
        return runLoop(activeClient, systemPrompt, assembled, requestId, fallbackResponder, decision, null);
    }

    private ChatExecutionResult runLoop(AiProviderService.ActiveChatClient activeClient,
                                        String systemPrompt,
                                        AssembledContext assembled,
                                        String requestId,
                                        AgentEngine.FallbackResponder fallbackResponder,
                                        AgentDecision decision,
                                        ChatContext chatContext) {
        AiProviderService.ActiveChatClient currentClient = activeClient;
        // 本地短路四件套（decision-bound / control-plane / context-aware / priority structured）
        // 最终会通过 Spring AOP 反射调用 @Tool 方法（例如 SystemToolPack.now()）。
        // ToolRuntimeAspect 在拦截时读 ToolExecutionContextHolder，但本地短路路径之前没有 open()，
        // 导致 audit JSON 里 requestId/sessionKey/userId 是占位值。
        // 这里打开一个 LOCAL-SHORTCUT scope，让 audit 拿到真实上下文。
        // 见 docs/TURN_CONTRACT.md §2.4 known-gap #2。
        ToolExecutionContext localShortcutCtx = new ToolExecutionContext(
                assembled.sessionKey(),
                assembled.channel(),
                assembled.userId(),
                requestId,
                "LOCAL-SHORTCUT",
                requestId,
                null
        );
        try (ToolExecutionContextHolder.Scope localScope = ToolExecutionContextHolder.open(localShortcutCtx)) {
            LocalSkillFallbackService.LocalSkillResult decisionBoundResult = tryDecisionBoundLocalResult(assembled.question(), decision);
            if (decisionBoundResult != null) {
                return buildLocalExecutionResult(
                        systemPrompt,
                        assembled,
                        decisionBoundResult,
                        "命中已决策能力的本地执行路线，跳过模型自由规划阶段。",
                        "已由 AgentDecision 约束的受控能力完成执行。"
                );
            }
            // control-plane 确定性查询始终优先于模型调用，不受 localFallbackFirst 影响
            LocalSkillFallbackService.LocalSkillResult controlPlaneResult = tryControlPlaneLocalResult(assembled.question());
            if (controlPlaneResult != null) {
                return buildLocalExecutionResult(
                        systemPrompt,
                        assembled,
                        controlPlaneResult,
                        "命中控制面确定性查询，始终优先于模型调用。",
                        controlPlaneResult.fallbackAnswer()
                );
            }
            // 上下文感知本地能力（确认词承接、历史时间查询等）始终优先于模型调用
            LocalSkillFallbackService.LocalSkillResult contextAwareResult = contextAwareSupport.tryContextAwareLocalResult(assembled);
            if (contextAwareResult != null) {
                return buildLocalExecutionResult(
                        systemPrompt,
                        assembled,
                        contextAwareResult,
                        "命中上下文感知本地执行路线，始终优先于模型调用。",
                        "已基于当前会话真实状态生成答复。"
                );
            }
            if (localFallbackFirst) {
                LocalSkillFallbackService.LocalSkillResult priorityStructuredResult = tryPriorityStructuredLocalResult(assembled.question());
                if (priorityStructuredResult != null) {
                    return buildLocalExecutionResult(
                            systemPrompt,
                            assembled,
                            priorityStructuredResult,
                            "命中高置信度结构化技能，跳过模型计划阶段。",
                            "已由受控本地技能完成执行。"
                    );
                }
            }
        }

        if (!modelTransportGuardService.isModelCallEnabled(currentClient)) {
            LocalSkillFallbackService.LocalSkillResult localResult = tryLocalFallbackResult(assembled.question());
            return new ChatExecutionResult(
                    assembled.observePrompt(),
                    modelTransportGuardService.disabledModelPlanReason(currentClient),
                    localResult != null
                            ? localResult.executionDetails()
                            : modelTransportGuardService.disabledModelActionReason(currentClient),
                    localResult != null
                            ? localResult.fallbackAnswer()
                            : fallbackResponder.respond(modelTransportGuardService.disabledModelReason(currentClient), assembled),
                    false
            );
        }

        String observePrompt = assembled.observePrompt();
        List<AgentStep> steps = new ArrayList<>();
        for (int stepNo = 1; stepNo <= maxAgentSteps; stepNo++) {
            PlanCallResult planCall = runPlan(currentClient, systemPrompt, assembled, requestId, steps, stepNo, chatContext);
            currentClient = planCall.client();
            PlanResult plan = planCall.plan();
            if (planCall.degraded()) {
                return buildDegradedResult(
                        systemPrompt,
                        currentClient,
                        assembled,
                        buildPlanTrace(List.of(new AgentStep(stepNo, plan, new ActionResult("计划阶段失败，未进入工具执行。", true)))),
                        "模型计划阶段失败，已切换到本地技能/降级输出。",
                        fallbackResponder
                );
            }
            if (plan.ready()) {
                steps.add(new AgentStep(
                        stepNo,
                        plan,
                        new ActionResult("规划判断当前信息已足够，直接进入总结。", false)
                ));
                break;
            }

            ActionCallResult actionCall = runAction(currentClient, systemPrompt, assembled, plan, requestId, steps, stepNo, decision, chatContext);
            currentClient = actionCall.client();
            ActionResult action = actionCall.action();
            steps.add(new AgentStep(stepNo, plan, action));
            if (action.degraded()) {
                return buildDegradedResult(
                        systemPrompt,
                        currentClient,
                        assembled,
                        buildPlanTrace(steps),
                        buildActionTrace(steps),
                        fallbackResponder
                );
            }
        }

        return new ChatExecutionResult(
                observePrompt,
                buildPlanTrace(steps),
                buildActionTrace(steps),
                "",
                true
        );
    }

    public String renderReflectPrompt(AssembledContext context, String plan, String action) {
        return promptSupport.renderReflectPrompt(context, plan, action);
    }

    public String renderReflectPrompt(ChatContext context, String plan, String action) {
        return promptSupport.renderReflectPrompt(context, plan, action);
    }

    public String renderMetaRepairPrompt(AssembledContext context, String plan, String action, String badAnswer) {
        return promptSupport.renderMetaRepairPrompt(context, plan, action, badAnswer);
    }

    public String renderMetaRepairPrompt(ChatContext context, String plan, String action, String badAnswer) {
        return promptSupport.renderMetaRepairPrompt(context, plan, action, badAnswer);
    }

    public String narrateLocalExecution(String systemPrompt,
                                        AssembledContext assembled,
                                        LocalSkillFallbackService.LocalSkillResult localResult) {
        return localExecutionSupport.narrate(systemPrompt, assembled, localResult);
    }

    public LocalSkillFallbackService.LocalSkillResult tryLocalFallbackResult(String question) {
        return localExecutionSupport.tryFallback(question, localFallbackEnabled);
    }

    private LocalSkillFallbackService.LocalSkillResult tryControlPlaneLocalResult(String question) {
        return localExecutionSupport.tryControlPlane(question, localFallbackEnabled);
    }

    private LocalSkillFallbackService.LocalSkillResult tryPriorityStructuredLocalResult(String question) {
        return localExecutionSupport.tryPriorityStructured(question, localFallbackEnabled);
    }

    private PlanCallResult runPlan(AiProviderService.ActiveChatClient activeClient,
                                   String systemPrompt,
                                   AssembledContext assembled,
                                   String requestId,
                                   List<AgentStep> history,
                                   int stepNo,
                                   ChatContext chatContext) {
        try {
            String renderedHistory = renderHistory(history);
            String planPrompt = chatContext == null
                    ? promptSupport.renderPlanPrompt(
                            assembled,
                            renderedHistory,
                            stepNo,
                            planOutputConverter.getFormat()
                    )
                    : promptSupport.renderPlanPrompt(
                            chatContext,
                            renderedHistory,
                            stepNo,
                            planOutputConverter.getFormat()
                    );
            ModelCallExecutor.ModelCallResult<PlanResult> callResult = modelCallExecutor.executeChat(
                    activeClient,
                    "plan",
                    new ModelCallExecutor.ChatRequestContext(
                            requestId,
                            assembled.sessionKey(),
                            assembled.channel(),
                            assembled.userId()
                    ),
                    true,
                    client -> {
                        var response = conversationAdvisorSupport.apply(
                                        client.chatClient().prompt()
                                                .system(systemPrompt)
                                                .user(planPrompt),
                                        assembled.sessionKey(),
                                        assembled.userId())
                                .call()
                                .responseEntity(PlanResult.class);
                        return new ModelCallExecutor.ChatOperationResult<>(
                                response.entity(),
                                response.response()
                        );
                    }
            );
            return new PlanCallResult(normalizePlanResult(callResult.value(), !history.isEmpty()), callResult.client(), false);
        } catch (Exception ex) {
            log.warn("Plan 阶段失败，sessionKey={}, reason={}", assembled.sessionKey(), ex.getMessage());
            PlanResult degraded = new PlanResult();
            degraded.setStatus("CONTINUE");
            degraded.setSummary("计划降级：模型调用失败，转本地技能或降级回答。");
            return new PlanCallResult(degraded, activeClient, true);
        }
    }

    private ActionCallResult runAction(AiProviderService.ActiveChatClient activeClient,
                                       String systemPrompt,
                                       AssembledContext assembled,
                                       PlanResult plan,
                                       String requestId,
                                       List<AgentStep> history,
                                       int stepNo,
                                       AgentDecision decision,
                                       ChatContext chatContext) {
        Object[] tools = toolOrchestrator.selectTools(
                assembled.channel(),
                assembled.userId(),
                assembled.question(),
                plan.planText(),
                decision
        );
        boolean allowFailover = isSafeToRetry(tools);
        ToolExecutionContext context = new ToolExecutionContext(
                assembled.sessionKey(),
                assembled.channel(),
                assembled.userId(),
                requestId,
                "ACT-" + stepNo,
                requestId,
                null
        );

        try (ToolExecutionContextHolder.Scope ignored = ToolExecutionContextHolder.open(context)) {
            String renderedHistory = renderHistory(history);
            String actionPrompt = chatContext == null
                    ? promptSupport.renderActionPrompt(assembled, plan.planText(), renderedHistory, stepNo)
                    : promptSupport.renderActionPrompt(chatContext, plan.planText(), renderedHistory, stepNo);
            ModelCallExecutor.ModelCallResult<String> callResult = modelCallExecutor.executeChat(
                    activeClient,
                    "action",
                    new ModelCallExecutor.ChatRequestContext(
                            requestId,
                            assembled.sessionKey(),
                            assembled.channel(),
                            assembled.userId()
                    ),
                    allowFailover,
                    client -> {
                        var requestSpec = client.chatClient().prompt()
                                .system(systemPrompt)
                                .user(actionPrompt);
                        if (DeepSeekChatCompatibility.supportsNativeToolCalling(client) && tools != null && tools.length > 0) {
                            requestSpec = requestSpec.tools(tools);
                        }
                        var response = conversationAdvisorSupport.apply(
                                        requestSpec,
                                        assembled.sessionKey(),
                                        assembled.userId())
                                .call()
                                .chatResponse();
                        return new ModelCallExecutor.ChatOperationResult<>(
                                ModelCallExecutor.extractText(response),
                                response
                        );
                    }
            );
            return new ActionCallResult(new ActionResult(safe(callResult.value()), false), callResult.client());
        } catch (Exception ex) {
            log.warn("Act 阶段失败，sessionKey={}, reason={}", assembled.sessionKey(), ex.getMessage());
            return new ActionCallResult(
                    new ActionResult(
                            "行动降级：工具执行失败或不可用。reason=" + safe(ex.getMessage()),
                            true
                    ),
                    activeClient
            );
        }
    }

    private PlanResult normalizePlanResult(PlanResult rawResult, boolean hasHistory) {
        PlanResult result = rawResult == null ? new PlanResult() : rawResult;
        if (!StringUtils.hasText(result.getStatus())) {
            result.setStatus(hasHistory ? "READY" : "CONTINUE");
        }
        if (!StringUtils.hasText(result.getSummary())
                && (result.getSteps() == null || result.getSteps().isEmpty())) {
            result.setSummary("READY".equalsIgnoreCase(result.getStatus())
                    ? "当前信息已足够，总结回答即可。"
                    : "继续围绕用户问题收集信息并行动。");
        }
        return result;
    }

    private String renderHistory(List<AgentStep> history) {
        if (history == null || history.isEmpty()) {
            return "（暂无历史步骤）";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentStep step : history) {
            builder.append("Step ").append(step.stepNo()).append('\n')
                    .append("PLAN: ").append(TextUtils.truncate(step.plan().planText(), 220)).append('\n')
                    .append("ACTION: ").append(TextUtils.truncate(step.action().output(), 260)).append('\n');
        }
        return builder.toString().trim();
    }

    private String buildPlanTrace(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "（无计划轨迹）";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentStep step : steps) {
            builder.append("[STEP ").append(step.stepNo()).append("] ")
                    .append(step.plan().ready() ? "READY" : "CONTINUE")
                    .append('\n')
                    .append(step.plan().planText())
                    .append("\n\n");
        }
        return builder.toString().trim();
    }

    private String buildActionTrace(List<AgentStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "（无行动轨迹）";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentStep step : steps) {
            builder.append("[STEP ").append(step.stepNo()).append("]");
            if (step.action().degraded()) {
                builder.append(" DEGRADED");
            }
            builder.append('\n')
                    .append(step.action().output())
                    .append("\n\n");
        }
        return builder.toString().trim();
    }

    private ChatExecutionResult buildDegradedResult(String systemPrompt,
                                                    AiProviderService.ActiveChatClient activeClient,
                                                    AssembledContext assembled,
                                                    String plan,
                                                    String action,
                                                    AgentEngine.FallbackResponder fallbackResponder) {
        LocalSkillFallbackService.LocalSkillResult localResult = tryLocalFallbackResult(assembled.question());
        String reflect = localResult != null
                ? narrateLocalExecution(systemPrompt, assembled, localResult)
                : fallbackResponder.respond(modelTransportGuardService.disabledModelReason(activeClient), assembled);
        return new ChatExecutionResult(
                assembled.observePrompt(),
                safe(plan),
                localResult != null ? localResult.executionDetails() : safe(action),
                reflect,
                false
        );
    }

    private ChatExecutionResult buildLocalExecutionResult(String systemPrompt,
                                                          AssembledContext assembled,
                                                          LocalSkillFallbackService.LocalSkillResult localResult,
                                                          String plan,
                                                          String action) {
        String reflect = narrateLocalExecution(systemPrompt, assembled, localResult);
        return new ChatExecutionResult(
                assembled.observePrompt(),
                safe(plan) + "\n执行路线: " + localResult.route(),
                safe(action) + "\n真实执行结果:\n" + localResult.executionDetails(),
                reflect,
                false
        );
    }

    private LocalSkillFallbackService.LocalSkillResult tryDecisionBoundLocalResult(String question, AgentDecision decision) {
        if (!shouldUseDecisionBoundLocalResult(decision)) {
            return null;
        }
        LocalSkillFallbackService.LocalSkillResult priorityStructuredResult = tryPriorityStructuredLocalResult(question);
        if (priorityStructuredResult != null) {
            return priorityStructuredResult;
        }
        return tryLocalFallbackResult(question);
    }

    private boolean shouldUseDecisionBoundLocalResult(AgentDecision decision) {
        if (decision == null || decision.isGeneral() || decision.requiresConfirmation() || decision.isDangerous()) {
            return false;
        }
        String executionPath = decision.executionPath() == null ? "" : decision.executionPath().trim();
        return "agent_tools".equalsIgnoreCase(executionPath)
                || "skill_direct".equalsIgnoreCase(executionPath);
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private boolean isSafeToRetry(Object[] tools) {
        if (tools == null || tools.length == 0) {
            return true;
        }
        for (Object tool : tools) {
            if (tool instanceof FileToolPack || tool instanceof LocalFilesystemToolPack || tool instanceof ScriptSkillToolPack) {
                return false;
            }
        }
        return true;
    }

    private record ActionResult(String output, boolean degraded) {
    }

    private record PlanCallResult(PlanResult plan, AiProviderService.ActiveChatClient client, boolean degraded) {
    }

    private record ActionCallResult(ActionResult action, AiProviderService.ActiveChatClient client) {
    }

    private record AgentStep(int stepNo, PlanResult plan, ActionResult action) {
    }
}
