package com.springclaw.service.agent;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;
import com.springclaw.service.chat.impl.ChatResponsePolicyService;
import com.springclaw.service.chat.impl.ConversationAdvisorSupport;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import com.springclaw.service.chat.impl.ModelTransportGuardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Unified backend Agent loop for non-general requests.
 */
@Service
public class AgentRuntimeEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeEngine.class);
    private static final int SUMMARY_CAPABILITY_PAYLOAD_LIMIT = 1800;

    private final CapabilityExecutorRegistry capabilityExecutorRegistry;
    private final ModelCallExecutor modelCallExecutor;
    private final ConversationAdvisorSupport conversationAdvisorSupport;
    private final ModelTransportGuardService modelTransportGuardService;
    private final ChatResponsePolicyService chatResponsePolicyService;

    public AgentRuntimeEngine(CapabilityExecutorRegistry capabilityExecutorRegistry,
                              ModelCallExecutor modelCallExecutor,
                              ConversationAdvisorSupport conversationAdvisorSupport,
                              ModelTransportGuardService modelTransportGuardService,
                              ChatResponsePolicyService chatResponsePolicyService) {
        this.capabilityExecutorRegistry = capabilityExecutorRegistry;
        this.modelCallExecutor = modelCallExecutor;
        this.conversationAdvisorSupport = conversationAdvisorSupport;
        this.modelTransportGuardService = modelTransportGuardService;
        this.chatResponsePolicyService = chatResponsePolicyService;
    }

    public AgentRun run(ChatContext context) {
        long startedAt = System.currentTimeMillis();
        AgentDecision decision = context == null || context.decision() == null
                ? AgentDecision.clarify("没有生成 Agent 决策。")
                : context.decision();
        List<AgentStep> steps = new ArrayList<>();
        steps.add(new AgentStep("接收目标", "request", "success", "已收到用户输入并组装上下文。", 0L));
        steps.add(new AgentStep("判断意图", "route", "success", decision.reason(), 0L));

        CapabilityPlan plan = capabilityExecutorRegistry.plan(decision);
        steps.add(new AgentStep("选择能力", "agent", "success", renderPlanSummary(plan), elapsed(startedAt)));

        long executeStartedAt = System.currentTimeMillis();
        List<CapabilityResult> capabilityResults = capabilityExecutorRegistry.execute(decision, context.assembled(), context.requestId());
        String executeStatus = capabilityResults.stream().anyMatch(result -> !result.successful()) ? "failed" : "success";
        steps.add(new AgentStep("执行能力", "tool", executeStatus, renderExecutionSummary(capabilityResults), elapsed(executeStartedAt)));

        VerificationResult verification = verify(decision, capabilityResults);
        steps.add(new AgentStep("校验证据", "verification", verification.status(), verification.summary(), 0L));

        ChatExecutionResult executionResult = summarize(context, plan, capabilityResults, verification);
        steps.add(new AgentStep("生成最终回答", "final", "success", "已基于执行结果生成最终回答。", elapsed(startedAt)));

        return new AgentRun(context.requestId(), decision, plan, steps, capabilityResults, verification, executionResult);
    }

    private ChatExecutionResult summarize(ChatContext context,
                                          CapabilityPlan plan,
                                          List<CapabilityResult> capabilityResults,
                                          VerificationResult verification) {
        String planText = renderPlanText(plan);
        String actionText = renderActionText(capabilityResults, verification);
        AiProviderService.ActiveChatClient activeClient = context.activeClient();
        if (!modelTransportGuardService.isModelCallEnabled(activeClient)) {
            String answer = deterministicAnswer(context, capabilityResults, verification, modelTransportGuardService.disabledModelReason(activeClient));
            return new ChatExecutionResult(context.assembled().observePrompt(), planText, actionText, answer, false);
        }
        try {
            ModelCallExecutor.ModelCallResult<String> result = modelCallExecutor.executeChat(
                    activeClient,
                    "agent-runtime-summary",
                    new ModelCallExecutor.ChatRequestContext(
                            context.requestId(),
                            context.assembled().sessionKey(),
                            context.assembled().channel(),
                            context.assembled().userId()
                    ),
                    true,
                    client -> {
                        var response = conversationAdvisorSupport.apply(
                                        client.chatClient().prompt()
                                                .system(context.systemPrompt())
                                                .user(renderSummaryPrompt(context, plan, capabilityResults, verification)),
                                        context.assembled().sessionKey(),
                                        context.assembled().userId())
                                .call()
                                .chatResponse();
                        return new ModelCallExecutor.ChatOperationResult<>(ModelCallExecutor.extractText(response), response);
                    }
            );
            String answer = result.value();
            if (!StringUtils.hasText(answer)
                    || chatResponsePolicyService.looksLikeProjectAccessRefusal(answer)
                    || chatResponsePolicyService.looksLikeToolFailureRefusal(answer)
                    || chatResponsePolicyService.looksLikeMetaRefusal(answer)) {
                answer = deterministicAnswer(context, capabilityResults, verification, "模型总结不可用或输出了不合适的拒答。已直接整理工具结果。");
            }
            return new ChatExecutionResult(context.assembled().observePrompt(), planText, actionText, answer, true);
        } catch (Exception ex) {
            modelTransportGuardService.markFailure(activeClient.providerId(), ex);
            String reason = chatResponsePolicyService.simplifyFailureReason(ex.getMessage());
            log.warn("AgentRuntimeEngine 模型总结失败，requestId={}, reason={}", context.requestId(), reason);
            String answer = deterministicAnswer(context, capabilityResults, verification, reason);
            return new ChatExecutionResult(context.assembled().observePrompt(), planText, actionText, answer, false);
        }
    }

    private VerificationResult verify(AgentDecision decision, List<CapabilityResult> capabilityResults) {
        if (decision == null || decision.isGeneral()) {
            return new VerificationResult("success", true, "普通聊天不需要工具证据。");
        }
        if (decision.requiresConfirmation()) {
            return new VerificationResult("skipped", true, "该动作需要确认，确认前不执行副作用。");
        }
        if (capabilityResults == null || capabilityResults.isEmpty()) {
            return new VerificationResult("failed", false, "本轮需要 Agent 能力，但没有可用执行结果。");
        }
        long successCount = capabilityResults.stream().filter(CapabilityResult::successful).count();
        if (successCount == 0) {
            return new VerificationResult("failed", false, "能力已触发，但全部执行失败。");
        }
        if (successCount < capabilityResults.size()) {
            return new VerificationResult("success", true, "部分能力执行成功，已基于可用证据回答。");
        }
        return new VerificationResult("success", true, "能力执行完成，证据足够生成回答。");
    }

    private String renderSummaryPrompt(ChatContext context,
                                       CapabilityPlan plan,
                                       List<CapabilityResult> capabilityResults,
                                       VerificationResult verification) {
        return """
                # SpringClaw Agent Runtime Finalizer
                你是 SpringClaw Agent Runtime 的最终回答整理器。后端已经完成意图判断、能力选择、工具/skill 执行和证据校验。

                # RESPONSE_CONTRACT
                - 必须基于 DYNAMIC_REQUEST 中的真实执行结果回答。
                - 不要声称无法访问本地项目、授权文件或运行环境；如果能力结果已提供，就直接基于结果整理。
                - 如果某个能力失败，要说明失败阶段、可恢复建议和已拿到的部分证据。
                - 不要输出内部类名、SSE、OPAR、fallback、toolset registry 等实现词。
                - 用清晰中文回答，先给结论，再给依据和下一步。
                - 不要编造能力结果之外的文件、数据、日志或网络响应。
                - 如果能力结果是文件/日志/列表，不要逐项复写超过 30 项的文件列表；应先总结数量、类型和最相关的 10-20 项，需要更多时提示用户继续展开。

                # DYNAMIC_REQUEST
                用户目标：
                %s

                Agent 决策：
                intent=%s
                path=%s
                toolsets=%s
                risk=%s
                reason=%s

                后端已经真实执行的能力结果：
                %s

                校验结果：%s
                """.formatted(
                safe(context.assembled().question()),
                plan.intent(),
                plan.executionPath(),
                String.join(",", plan.toolsets()),
                plan.riskLevel(),
                plan.reason(),
                renderCapabilityContext(capabilityResults),
                verification.summary()
        );
    }

    private String deterministicAnswer(ChatContext context,
                                       List<CapabilityResult> capabilityResults,
                                       VerificationResult verification,
                                       String reason) {
        StringBuilder builder = new StringBuilder();
        builder.append("结论：我已经按 Agent 链路执行了这次请求");
        if (StringUtils.hasText(reason)) {
            builder.append("，但模型总结阶段不稳定：").append(reason);
        }
        builder.append("。下面是后端真实拿到的结果。\n\n");
        if (verification != null && StringUtils.hasText(verification.summary())) {
            builder.append("校验：").append(verification.summary()).append("\n\n");
        }
        if (capabilityResults == null || capabilityResults.isEmpty()) {
            builder.append("本轮没有拿到可用工具结果。请求：").append(safe(context.assembled().question())).append("\n");
            return builder.toString();
        }
        for (CapabilityResult result : capabilityResults) {
            builder.append("能力：").append(result.capabilityId())
                    .append("\n状态：").append(result.status())
                    .append("\n说明：").append(result.summary())
                    .append("\n结果：\n").append(truncate(result.payload(), 1600))
                    .append("\n\n");
        }
        builder.append("下一步：如果你要更深入，我可以继续基于这些结果展开分析或执行下一轮工具。 ");
        return builder.toString().trim();
    }

    private String renderCapabilityContext(List<CapabilityResult> capabilityResults) {
        if (capabilityResults == null || capabilityResults.isEmpty()) {
            return "（没有能力执行结果）";
        }
        return capabilityResults.stream()
                .map(result -> "- capability=%s, toolset=%s, status=%s, durationMs=%d, summary=%s\n%s".formatted(
                        result.capabilityId(),
                        result.toolset(),
                        result.status(),
                        result.durationMs(),
                        result.summary(),
                        truncate(result.payload(), SUMMARY_CAPABILITY_PAYLOAD_LIMIT)
                ))
                .collect(Collectors.joining("\n"));
    }

    private String renderPlanSummary(CapabilityPlan plan) {
        if (plan == null || plan.toolsets().isEmpty()) {
            return "没有选中可执行 toolset。";
        }
        return "已选择 toolset: " + String.join(", ", plan.toolsets());
    }

    private String renderExecutionSummary(List<CapabilityResult> capabilityResults) {
        if (capabilityResults == null || capabilityResults.isEmpty()) {
            return "未执行任何能力。";
        }
        long successCount = capabilityResults.stream().filter(CapabilityResult::successful).count();
        return "执行能力 " + capabilityResults.size() + " 个，成功 " + successCount + " 个。";
    }

    private String renderPlanText(CapabilityPlan plan) {
        if (plan == null) {
            return "PLAN: 未生成能力计划。";
        }
        return "PLAN: intent=%s, path=%s, toolsets=%s, risk=%s, reason=%s".formatted(
                plan.intent(),
                plan.executionPath(),
                String.join(",", plan.toolsets()),
                plan.riskLevel(),
                plan.reason()
        );
    }

    private String renderActionText(List<CapabilityResult> results, VerificationResult verification) {
        StringBuilder builder = new StringBuilder("ACTION: backend capabilities executed");
        if (verification != null) {
            builder.append("\nVERIFICATION: ").append(verification.status()).append(" - ").append(verification.summary());
        }
        if (results != null) {
            for (CapabilityResult result : results) {
                builder.append("\n- ").append(result.capabilityId())
                        .append(" [").append(result.status()).append("] ")
                        .append(result.summary())
                        .append("\n")
                        .append(truncate(result.payload(), 900));
            }
        }
        return builder.toString();
    }

    private long elapsed(long startedAt) {
        return Math.max(0L, System.currentTimeMillis() - startedAt);
    }

    private String truncate(String text, int limit) {
        String value = text == null ? "" : text.trim();
        return value.length() <= limit ? value : value.substring(0, limit) + "\n...<TRUNCATED>";
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }
}
