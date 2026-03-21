package com.openclaw.service.chat.impl;

import com.openclaw.service.ai.AiProviderService;
import com.openclaw.service.chat.LocalSkillFallbackService;
import com.openclaw.service.context.AssembledContext;
import com.openclaw.tool.pack.FileToolPack;
import com.openclaw.tool.pack.ScriptSkillToolPack;
import com.openclaw.tool.runtime.ToolExecutionContext;
import com.openclaw.tool.runtime.ToolExecutionContextHolder;
import com.openclaw.tool.runtime.ToolOrchestrator;
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
public class OparLoopEngine {

    private static final Logger log = LoggerFactory.getLogger(OparLoopEngine.class);

    private final AiProviderService aiProviderService;
    private final ToolOrchestrator toolOrchestrator;
    private final LocalSkillFallbackService localSkillFallbackService;
    private final ModelControlIntentService modelControlIntentService;
    private final LocalExecutionNarrator localExecutionNarrator;
    private final ModelTransportGuardService modelTransportGuardService;
    private final ModelCallExecutor modelCallExecutor;
    private final OparContextAwareSupport contextAwareSupport;
    private final OparPromptSupport promptSupport;
    private final ConversationAdvisorSupport conversationAdvisorSupport;
    private final BeanOutputConverter<PlanResult> planOutputConverter = new BeanOutputConverter<>(PlanResult.class);
    private final boolean localFallbackEnabled;
    private final boolean localFallbackFirst;
    private final int maxAgentSteps;

    public OparLoopEngine(AiProviderService aiProviderService,
                          ToolOrchestrator toolOrchestrator,
                          LocalSkillFallbackService localSkillFallbackService,
                          ModelControlIntentService modelControlIntentService,
                          LocalExecutionNarrator localExecutionNarrator,
                          ModelTransportGuardService modelTransportGuardService,
                          ModelCallExecutor modelCallExecutor,
                          OparContextAwareSupport contextAwareSupport,
                          OparPromptSupport promptSupport,
                          ConversationAdvisorSupport conversationAdvisorSupport,
                          @Value("${openclaw.chat.local-fallback-enabled:true}") boolean localFallbackEnabled,
                          @Value("${openclaw.chat.local-fallback-first:true}") boolean localFallbackFirst,
                          @Value("${openclaw.chat.max-steps:3}") int maxAgentSteps) {
        this.aiProviderService = aiProviderService;
        this.toolOrchestrator = toolOrchestrator;
        this.localSkillFallbackService = localSkillFallbackService;
        this.modelControlIntentService = modelControlIntentService;
        this.localExecutionNarrator = localExecutionNarrator;
        this.modelTransportGuardService = modelTransportGuardService;
        this.modelCallExecutor = modelCallExecutor;
        this.contextAwareSupport = contextAwareSupport;
        this.promptSupport = promptSupport;
        this.conversationAdvisorSupport = conversationAdvisorSupport;
        this.localFallbackEnabled = localFallbackEnabled;
        this.localFallbackFirst = localFallbackFirst;
        this.maxAgentSteps = Math.max(1, Math.min(maxAgentSteps, 6));
    }

    public ChatExecutionResult runLoop(AiProviderService.ActiveChatClient activeClient,
                                       String systemPrompt,
                                       AssembledContext assembled,
                                       String requestId,
                                       FallbackResponder fallbackResponder) {
        AiProviderService.ActiveChatClient currentClient = activeClient;
        if (localFallbackFirst) {
            LocalSkillFallbackService.LocalSkillResult contextAwareResult = contextAwareSupport.tryContextAwareLocalResult(assembled);
            if (contextAwareResult != null) {
                return buildLocalExecutionResult(
                        systemPrompt,
                        assembled,
                        contextAwareResult,
                        "命中上下文感知本地执行路线，跳过模型计划阶段。",
                        "已基于当前会话真实状态生成答复。"
                );
            }
            LocalSkillFallbackService.LocalSkillResult localResult = tryControlPlaneLocalResult(assembled.question());
            if (localResult != null) {
                return buildLocalExecutionResult(
                        systemPrompt,
                        assembled,
                        localResult,
                        "命中本地执行路线，跳过模型计划阶段。",
                        "本地执行完成，已整理真实结果。"
                );
            }
            LocalSkillFallbackService.LocalSkillResult aiControlResult = tryAiAssistedModelControl(activeClient, assembled, requestId);
            if (aiControlResult != null) {
                return buildLocalExecutionResult(
                        systemPrompt,
                        assembled,
                        aiControlResult,
                        "模型识别到模型控制意图，跳过完整计划阶段。",
                        "已由本地执行器完成控制/查询。"
                );
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
            PlanCallResult planCall = runPlan(currentClient, systemPrompt, assembled, requestId, steps, stepNo);
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

            ActionCallResult actionCall = runAction(currentClient, systemPrompt, assembled, plan, requestId, steps, stepNo);
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

    public String renderMetaRepairPrompt(AssembledContext context, String plan, String action, String badAnswer) {
        return promptSupport.renderMetaRepairPrompt(context, plan, action, badAnswer);
    }

    public String narrateLocalExecution(String systemPrompt,
                                        AssembledContext assembled,
                                        LocalSkillFallbackService.LocalSkillResult localResult) {
        AiProviderService.ActiveChatClient narrationClient = aiProviderService.activeClient();
        return localExecutionNarrator.narrate(
                systemPrompt,
                assembled,
                localResult,
                narrationClient,
                modelTransportGuardService.isModelCallEnabled(narrationClient)
        );
    }

    public LocalSkillFallbackService.LocalSkillResult tryLocalFallbackResult(String question) {
        if (!localFallbackEnabled) {
            return null;
        }
        try {
            return localSkillFallbackService.tryHandleStructured(question).orElse(null);
        } catch (Exception ex) {
            log.warn("本地技能兜底失败，reason={}", ex.getMessage());
            return null;
        }
    }

    private LocalSkillFallbackService.LocalSkillResult tryControlPlaneLocalResult(String question) {
        if (!localFallbackEnabled) {
            return null;
        }
        try {
            return localSkillFallbackService.tryHandleControlPlane(question).orElse(null);
        } catch (Exception ex) {
            log.warn("控制面本地执行失败，reason={}", ex.getMessage());
            return null;
        }
    }

    private PlanCallResult runPlan(AiProviderService.ActiveChatClient activeClient,
                                   String systemPrompt,
                                   AssembledContext assembled,
                                   String requestId,
                                   List<AgentStep> history,
                                   int stepNo) {
        try {
            String planPrompt = promptSupport.renderPlanPrompt(
                    assembled,
                    renderHistory(history),
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
                                       int stepNo) {
        Object[] tools = toolOrchestrator.selectTools(
                assembled.channel(),
                assembled.userId(),
                assembled.question(),
                plan.planText()
        );
        boolean allowFailover = isSafeToRetry(tools);
        ToolExecutionContext context = new ToolExecutionContext(
                assembled.sessionKey(),
                assembled.channel(),
                assembled.userId(),
                requestId,
                "ACT-" + stepNo
        );

        try (ToolExecutionContextHolder.Scope ignored = ToolExecutionContextHolder.open(context)) {
            String actionPrompt = promptSupport.renderActionPrompt(assembled, plan.planText(), renderHistory(history), stepNo);
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
                        var response = conversationAdvisorSupport.apply(
                                        client.chatClient().prompt()
                                                .system(systemPrompt)
                                                .user(actionPrompt)
                                                .tools(tools),
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
                    .append("PLAN: ").append(truncate(step.plan().planText(), 220)).append('\n')
                    .append("ACTION: ").append(truncate(step.action().output(), 260)).append('\n');
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
                                                    FallbackResponder fallbackResponder) {
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

    private LocalSkillFallbackService.LocalSkillResult tryAiAssistedModelControl(AiProviderService.ActiveChatClient activeClient,
                                                                                 AssembledContext assembled,
                                                                                 String requestId) {
        if (!modelTransportGuardService.isModelCallEnabled(activeClient)) {
            return null;
        }
        try {
            String response = modelControlIntentService.classify(activeClient, assembled, requestId);
            return dispatchAiModelControlCommand(response);
        } catch (Exception ex) {
            modelTransportGuardService.markFailure(activeClient.providerId(), ex);
            log.warn("模型辅助 provider 意图识别失败，reason={}", ex.getMessage());
            return null;
        }
    }

    private LocalSkillFallbackService.LocalSkillResult dispatchAiModelControlCommand(String rawCommand) {
        String command = safe(rawCommand).trim();
        String upper = command.toUpperCase();
        if ("QUERY".equals(upper)) {
            return tryLocalFallbackResult("当前模型是什么");
        }
        if ("SWITCH_PROVIDER:PRIMARY".equals(upper)) {
            return tryLocalFallbackResult("切换到 claude");
        }
        if ("SWITCH_PROVIDER:QWEN".equals(upper)) {
            return tryLocalFallbackResult("切换到千问");
        }
        if ("SWITCH_PROVIDER:CODING-PLAN".equals(upper)) {
            return tryLocalFallbackResult("切换到 coding plan");
        }
        if (upper.startsWith("SWITCH_MODEL:")) {
            String payload = command.substring("SWITCH_MODEL:".length()).trim();
            int separator = payload.indexOf(':');
            if (separator > 0 && separator < payload.length() - 1) {
                String providerId = payload.substring(0, separator).trim();
                String modelId = payload.substring(separator + 1).trim();
                if (StringUtils.hasText(providerId) && StringUtils.hasText(modelId)) {
                    return tryLocalFallbackResult("切换到 " + providerId + " 的 " + modelId);
                }
            }
        }
        return null;
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private boolean isSafeToRetry(Object[] tools) {
        if (tools == null || tools.length == 0) {
            return true;
        }
        for (Object tool : tools) {
            if (tool instanceof FileToolPack || tool instanceof ScriptSkillToolPack) {
                return false;
            }
        }
        return true;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    @FunctionalInterface
    public interface FallbackResponder {
        String respond(String reason, AssembledContext context);
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
