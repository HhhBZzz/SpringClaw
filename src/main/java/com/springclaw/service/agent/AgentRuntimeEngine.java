package com.springclaw.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;
import com.springclaw.service.chat.impl.ChatResponsePolicyService;
import com.springclaw.service.chat.impl.ConversationAdvisorSupport;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import com.springclaw.service.chat.impl.ModelTransportGuardService;
import com.springclaw.service.context.AssembledContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Unified backend Agent loop for non-general requests.
 */
@Service
public class AgentRuntimeEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeEngine.class);
    private static final int SUMMARY_CAPABILITY_PAYLOAD_LIMIT = 1800;
    private static final int MAX_REFLECTION_ATTEMPTS = 3;

    private final CapabilityExecutorRegistry capabilityExecutorRegistry;
    private final ModelCallExecutor modelCallExecutor;
    private final ConversationAdvisorSupport conversationAdvisorSupport;
    private final ModelTransportGuardService modelTransportGuardService;
    private final ChatResponsePolicyService chatResponsePolicyService;
    private final ObjectMapper objectMapper;

    public AgentRuntimeEngine(CapabilityExecutorRegistry capabilityExecutorRegistry,
                              ModelCallExecutor modelCallExecutor,
                              ConversationAdvisorSupport conversationAdvisorSupport,
                              ModelTransportGuardService modelTransportGuardService,
                              ChatResponsePolicyService chatResponsePolicyService) {
        this(capabilityExecutorRegistry,
                modelCallExecutor,
                conversationAdvisorSupport,
                modelTransportGuardService,
                chatResponsePolicyService,
                new ObjectMapper());
    }

    @Autowired
    public AgentRuntimeEngine(CapabilityExecutorRegistry capabilityExecutorRegistry,
                              ModelCallExecutor modelCallExecutor,
                              ConversationAdvisorSupport conversationAdvisorSupport,
                              ModelTransportGuardService modelTransportGuardService,
                              ChatResponsePolicyService chatResponsePolicyService,
                              ObjectMapper objectMapper) {
        this.capabilityExecutorRegistry = capabilityExecutorRegistry;
        this.modelCallExecutor = modelCallExecutor;
        this.conversationAdvisorSupport = conversationAdvisorSupport;
        this.modelTransportGuardService = modelTransportGuardService;
        this.chatResponsePolicyService = chatResponsePolicyService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public AgentRun run(ChatContext context) {
        long startedAt = System.currentTimeMillis();
        AgentDecision decision = context == null || context.decision() == null
                ? AgentDecision.clarify("没有生成 Agent 决策。")
                : context.decision();
        String originalQuestion = firstText(
                context == null ? "" : context.effectiveUserMessage(),
                context == null || context.assembled() == null ? "" : context.assembled().question()
        );
        String currentQuestion = originalQuestion;
        List<AgentStep> steps = new ArrayList<>();
        List<CapabilityResult> allResults = new ArrayList<>();
        CapabilityPlan plan = null;
        ReflectionResult reflection = ReflectionResult.degraded("尚未执行能力。", decision.intent());
        VerificationResult verification = new VerificationResult("failed", false, "尚未完成证据反思。");
        boolean finalDegraded = false;
        boolean sufficient = false;
        steps.add(new AgentStep("INIT", "request", "success", "已收到用户输入并组装上下文。", 0L));

        for (int attempt = 1; attempt <= MAX_REFLECTION_ATTEMPTS; attempt++) {
            long planStartedAt = System.currentTimeMillis();
            plan = capabilityExecutorRegistry.plan(decision);
            steps.add(new AgentStep("PLAN_CAPABILITY", "agent", "success",
                    "attempt=" + attempt + "，query=" + currentQuestion + "，" + renderPlanSummary(plan),
                    elapsed(planStartedAt)));

            long executeStartedAt = System.currentTimeMillis();
            AssembledContext roundContext = context.assembled().withQuestion(currentQuestion);
            List<CapabilityResult> roundResults = executeCapabilities(decision, roundContext, context.requestId(), plan);
            allResults.addAll(roundResults);
            String executeStatus = roundResults.isEmpty() || roundResults.stream().anyMatch(result -> !result.successful()) ? "failed" : "success";
            steps.add(new AgentStep("EXECUTE_CAPABILITY", "tool", executeStatus,
                    "attempt=" + attempt + "，" + renderExecutionSummary(roundResults),
                    elapsed(executeStartedAt)));

            reflection = reflectEvidence(context, decision, plan, allResults, originalQuestion, currentQuestion, attempt);
            verification = toVerification(reflection, allResults);
            steps.add(new AgentStep("REFLECT_EVIDENCE", "verification", verification.status(),
                    renderReflectionSummary(reflection), 0L));

            if (reflection.sufficient()) {
                sufficient = true;
                break;
            }
            if (!reflection.retryable() || attempt >= MAX_REFLECTION_ATTEMPTS) {
                finalDegraded = true;
                break;
            }
            String nextQuery = normalizeNextQuery(reflection.nextQuery(), currentQuestion);
            if (!StringUtils.hasText(nextQuery) || currentQuestion.equalsIgnoreCase(nextQuery)) {
                reflection = ReflectionResult.degraded("反思要求重试，但没有给出有效的新查询。", reflection.preferredIntent());
                verification = toVerification(reflection, allResults);
                finalDegraded = true;
                break;
            }
            currentQuestion = nextQuery;
            decision = rebuildDecisionForRetry(decision, reflection);
        }

        if (plan == null) {
            plan = capabilityExecutorRegistry.plan(decision);
        }
        ChatExecutionResult executionResult = finalDegraded || !sufficient
                ? buildFinalDegradedResult(context, plan, allResults, verification, reflection)
                : summarize(context, plan, allResults, verification);
        steps.add(new AgentStep(finalDegraded || !sufficient ? "FINAL_DEGRADED" : "FINAL_SUMMARIZE",
                "final",
                finalDegraded || !sufficient ? "failed" : "success",
                finalDegraded || !sufficient ? "证据不足或达到最大反思轮次，已返回确定性降级结果。" : "已基于反思通过的证据生成最终回答。",
                elapsed(startedAt)));

        return new AgentRun(context.requestId(), decision, plan, steps, allResults, verification, executionResult);
    }

    private List<CapabilityResult> executeCapabilities(AgentDecision decision,
                                                       AssembledContext roundContext,
                                                       String requestId,
                                                       CapabilityPlan plan) {
        try {
            List<CapabilityResult> results = capabilityExecutorRegistry.execute(decision, roundContext, requestId);
            return results == null ? List.of() : results;
        } catch (Exception ex) {
            return List.of(new CapabilityResult(
                    "agent-runtime.execute",
                    plan == null || plan.toolsets().isEmpty() ? "agent" : String.join(",", plan.toolsets()),
                    "failed",
                    "执行能力阶段异常",
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    0L,
                    decision == null ? "read" : decision.riskLevel()
            ));
        }
    }

    private ReflectionResult reflectEvidence(ChatContext context,
                                             AgentDecision decision,
                                             CapabilityPlan plan,
                                             List<CapabilityResult> allResults,
                                             String originalQuestion,
                                             String currentQuestion,
                                             int attempt) {
        AiProviderService.ActiveChatClient activeClient = context.activeClient();
        ReflectionResult fallback = deterministicReflection(decision, allResults, originalQuestion, currentQuestion);
        if (!fallback.sufficient() && !fallback.retryable()) {
            return fallback;
        }
        if (!modelTransportGuardService.isModelCallEnabled(activeClient)) {
            return fallback;
        }
        try {
            ModelCallExecutor.ModelCallResult<String> result = modelCallExecutor.executeChat(
                    activeClient,
                    "agent-runtime-reflection",
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
                                                .system(renderReflectionSystemPrompt())
                                                .user(renderReflectionPrompt(decision, plan, allResults, originalQuestion, currentQuestion, attempt)),
                                        context.assembled().sessionKey(),
                                        context.assembled().userId())
                                .call()
                                .chatResponse();
                        return new ModelCallExecutor.ChatOperationResult<>(ModelCallExecutor.extractText(response), response);
                    }
            );
            return parseReflectionResult(result.value(), fallback);
        } catch (Exception ex) {
            modelTransportGuardService.markFailure(activeClient.providerId(), ex);
            String reason = chatResponsePolicyService.simplifyFailureReason(ex.getMessage());
            log.warn("AgentRuntimeEngine 证据反思失败，requestId={}, reason={}", context.requestId(), reason);
            return fallback.sufficient() || fallback.retryable()
                    ? fallback
                    : ReflectionResult.degraded("证据反思模型不可用: " + reason, fallback.preferredIntent());
        }
    }

    private String renderReflectionSystemPrompt() {
        return """
                你是 SpringClaw Agent Runtime 的证据反思器。你只输出 JSON，不要解释，不要 Markdown。
                JSON 契约：
                {"sufficient":true|false,"retryable":true|false,"problem":"一句中文原因","nextQuery":"下一轮精确查询词","preferredIntent":"workspace_analysis|local_files|web_research|skill_task|model_control|unknown"}

                判断标准：
                - sufficient=true 只在能力结果足以回答原始问题时使用。
                - 如果工具失败、结果为空、结果和原始问题无关、或返回脏数据，sufficient=false。
                - retryable=true 只在换关键词、换意图或更精确查询可能改善结果时使用。
                - nextQuery 必须是给下一轮工具执行的精确查询文本，不能复述空话。
                - preferredIntent 表示下一轮更适合使用的能力倾向。
                - 【注意】如果搜索结果（CapabilityResult）中包含大量类似 'All Images News' 或者国家列表（如 Argentina, Australia），说明遭遇了搜索引擎的反爬虫或验证码拦截，属于无效脏数据。此时你必须判定 sufficient=false, retryable=true，并在 nextQuery 中尝试提供更具体的检索词（例如加上时间或换用其他关键词）。
                """;
    }

    private String renderReflectionPrompt(AgentDecision decision,
                                          CapabilityPlan plan,
                                          List<CapabilityResult> allResults,
                                          String originalQuestion,
                                          String currentQuestion,
                                          int attempt) {
        return """
                # ORIGINAL_QUESTION
                %s

                # CURRENT_QUERY
                %s

                # ATTEMPT
                %d / %d

                # CURRENT_DECISION
                intent=%s
                path=%s
                selectedCapabilities=%s
                risk=%s
                reason=%s

                # CURRENT_PLAN
                %s

                # ACCUMULATED_CAPABILITY_RESULTS
                %s
                """.formatted(
                safe(originalQuestion),
                safe(currentQuestion),
                attempt,
                MAX_REFLECTION_ATTEMPTS,
                decision == null ? "" : decision.intent(),
                decision == null ? "" : decision.executionPath(),
                decision == null ? "" : String.join(",", decision.selectedCapabilities()),
                decision == null ? "" : decision.riskLevel(),
                decision == null ? "" : decision.reason(),
                renderPlanText(plan),
                renderCapabilityContext(allResults)
        );
    }

    private ReflectionResult parseReflectionResult(String raw, ReflectionResult fallback) {
        String text = raw == null ? "" : raw.trim();
        if (!StringUtils.hasText(text)) {
            return fallback;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        try {
            JsonNode node = objectMapper.readTree(text);
            boolean sufficient = node.has("sufficient")
                    ? node.get("sufficient").asBoolean(fallback.sufficient())
                    : fallback.sufficient();
            boolean retryable = node.has("retryable")
                    ? node.get("retryable").asBoolean(fallback.retryable())
                    : fallback.retryable();
            String problem = textValue(node, "problem", fallback.problem());
            String nextQuery = textValue(node, "nextQuery", fallback.nextQuery());
            String preferredIntent = textValue(node, "preferredIntent", fallback.preferredIntent());
            if (sufficient) {
                return ReflectionResult.sufficient(problem, preferredIntent);
            }
            if (retryable) {
                return ReflectionResult.retry(problem, nextQuery, preferredIntent);
            }
            return ReflectionResult.degraded(problem, preferredIntent);
        } catch (Exception ex) {
            log.warn("AgentRuntimeEngine 证据反思 JSON 解析失败，fallback=true, reason={}", ex.getMessage());
            return fallback;
        }
    }

    private ReflectionResult deterministicReflection(AgentDecision decision,
                                                     List<CapabilityResult> allResults,
                                                     String originalQuestion,
                                                     String currentQuestion) {
        if (allResults == null || allResults.isEmpty()) {
            return ReflectionResult.degraded("本轮需要 Agent 能力，但没有拿到任何能力结果。", intentOf(decision));
        }
        long successCount = allResults.stream().filter(CapabilityResult::successful).count();
        if (expectsCapability(decision, "weather") && !hasSuccessfulCapabilityPrefix(allResults, "weather.")) {
            return ReflectionResult.degraded("天气请求没有拿到专用天气工具的结构化结果，不能仅凭搜索页摘要回答。", intentOf(decision));
        }
        if (expectsCapability(decision, "exchange") && !hasSuccessfulCapabilityPrefix(allResults, "exchange.")) {
            return ReflectionResult.degraded("汇率请求没有拿到专用汇率工具的结构化结果，不能仅凭搜索页摘要回答。", intentOf(decision));
        }
        if (successCount > 0 && hasUsefulSuccessfulResult(allResults)) {
            return ReflectionResult.sufficient("能力执行完成，已拿到非噪声证据。", intentOf(decision));
        }
        if (looksLikeSearchEngineNoise(allResults)) {
            return ReflectionResult.retry(
                    "搜索结果疑似搜索引擎前端噪声或反爬拦截，不能回答原始问题。",
                    refineNoisySearchQuery(originalQuestion, currentQuestion),
                    "web_research"
            );
        }
        if (successCount == 0) {
            return ReflectionResult.degraded("能力已触发，但全部执行失败。", intentOf(decision));
        }
        if (successCount < allResults.size()) {
            return ReflectionResult.sufficient("部分能力执行成功，已具备可用证据。", intentOf(decision));
        }
        return ReflectionResult.sufficient("能力执行完成，证据足够生成回答。", intentOf(decision));
    }

    private VerificationResult toVerification(ReflectionResult reflection, List<CapabilityResult> allResults) {
        if (reflection == null) {
            return new VerificationResult("failed", false, "证据反思未返回结果。");
        }
        if (reflection.sufficient()) {
            return new VerificationResult("success", true, StringUtils.hasText(reflection.problem())
                    ? reflection.problem()
                    : "证据反思判断当前结果足够回答用户问题。");
        }
        boolean allFailed = allResults != null && !allResults.isEmpty()
                && allResults.stream().noneMatch(CapabilityResult::successful);
        String status = allResults == null || allResults.isEmpty() || allFailed ? "failed" : "insufficient";
        return new VerificationResult(status, false, StringUtils.hasText(reflection.problem())
                ? reflection.problem()
                : "证据反思判断当前结果仍不足。");
    }

    private ChatExecutionResult buildFinalDegradedResult(ChatContext context,
                                                         CapabilityPlan plan,
                                                         List<CapabilityResult> allResults,
                                                         VerificationResult verification,
                                                         ReflectionResult reflection) {
        String reason = reflection == null || !StringUtils.hasText(reflection.problem())
                ? "证据不足或达到最大反思轮次。"
                : reflection.problem();
        String planText = renderPlanText(plan);
        String actionText = renderActionText(allResults, verification);
        String answer = deterministicAnswer(context, allResults, verification, reason);
        return new ChatExecutionResult(context.assembled().observePrompt(), planText, actionText, answer, false);
    }

    private String renderReflectionSummary(ReflectionResult reflection) {
        if (reflection == null) {
            return "证据反思未返回结果。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("sufficient=").append(reflection.sufficient())
                .append("，retryable=").append(reflection.retryable());
        if (StringUtils.hasText(reflection.problem())) {
            builder.append("，problem=").append(reflection.problem());
        }
        if (StringUtils.hasText(reflection.nextQuery())) {
            builder.append("，nextQuery=").append(reflection.nextQuery());
        }
        if (StringUtils.hasText(reflection.preferredIntent())) {
            builder.append("，preferredIntent=").append(reflection.preferredIntent());
        }
        return builder.toString();
    }

    private String normalizeNextQuery(String nextQuery, String currentQuestion) {
        String value = safe(nextQuery).replaceAll("\\s+", " ").trim();
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if (value.length() > 180) {
            value = value.substring(0, 180).trim();
        }
        return value.equalsIgnoreCase(safe(currentQuestion).trim()) ? "" : value;
    }

    private AgentDecision rebuildDecisionForRetry(AgentDecision previous, ReflectionResult reflection) {
        String intent = normalizeIntent(reflection == null ? "" : reflection.preferredIntent(), intentOf(previous));
        String executionPath = switch (intent) {
            case "general" -> "basic_model";
            case "skill_task" -> "skill_direct";
            default -> "agent_tools";
        };
        List<String> capabilities = capabilitiesForIntent(intent, previous == null ? List.of() : previous.selectedCapabilities());
        return new AgentDecision(
                intent,
                executionPath,
                capabilities,
                previous == null ? "read" : previous.riskLevel(),
                false,
                "证据反思要求重试: " + safe(reflection == null ? "" : reflection.problem())
        );
    }

    private List<String> capabilitiesForIntent(String intent, List<String> previousCapabilities) {
        return switch (normalizeIntent(intent, "unknown")) {
            case "workspace_analysis" -> List.of("workspace-search", "workspace-review", "file", "skill-library");
            case "local_files" -> List.of("local-files", "file");
            case "web_research" -> webCapabilitiesForRetry(previousCapabilities);
            case "skill_task" -> previousCapabilities == null || previousCapabilities.isEmpty()
                    ? List.of("script-skill", "skill-library")
                    : previousCapabilities;
            case "model_control" -> List.of("system", "skill-library");
            default -> previousCapabilities == null ? List.of() : previousCapabilities;
        };
    }

    private String normalizeIntent(String rawIntent, String fallback) {
        String value = StringUtils.hasText(rawIntent) ? rawIntent.trim().toLowerCase(Locale.ROOT).replace('-', '_') : "";
        return switch (value) {
            case "general", "workspace_analysis", "local_files", "web_research", "skill_task", "model_control", "unknown" -> value;
            case "weather", "news", "exchange", "web" -> "web_research";
            case "workspace", "workspace_search", "workspace_review" -> "workspace_analysis";
            case "local", "file", "files", "local_file" -> "local_files";
            case "skill", "script", "script_skill" -> "skill_task";
            case "system", "control_plane" -> "model_control";
            default -> StringUtils.hasText(fallback) ? fallback : "unknown";
        };
    }

    private String intentOf(AgentDecision decision) {
        return decision == null ? "unknown" : decision.intent();
    }

    private boolean hasUsefulSuccessfulResult(List<CapabilityResult> results) {
        return results != null && results.stream()
                .anyMatch(result -> result.successful()
                        && StringUtils.hasText(result.payload())
                        && !looksLikeSearchEngineNoise(result));
    }

    private boolean expectsCapability(AgentDecision decision, String capability) {
        if (decision == null || decision.selectedCapabilities() == null) {
            return false;
        }
        String expected = safe(capability).toLowerCase(Locale.ROOT).replace('_', '-');
        return decision.selectedCapabilities().stream()
                .map(value -> safe(value).toLowerCase(Locale.ROOT).replace('_', '-'))
                .anyMatch(expected::equals);
    }

    private boolean hasSuccessfulCapabilityPrefix(List<CapabilityResult> results, String prefix) {
        String normalizedPrefix = safe(prefix).toLowerCase(Locale.ROOT);
        return results != null && results.stream()
                .anyMatch(result -> result.successful()
                        && safe(result.capabilityId()).toLowerCase(Locale.ROOT).startsWith(normalizedPrefix)
                        && StringUtils.hasText(result.payload())
                        && !looksLikeSearchEngineNoise(result));
    }

    private List<String> webCapabilitiesForRetry(List<String> previousCapabilities) {
        if (previousCapabilities == null || previousCapabilities.isEmpty()) {
            return List.of("web");
        }
        List<String> normalized = previousCapabilities.stream()
                .map(value -> safe(value).trim().toLowerCase(Locale.ROOT).replace('_', '-'))
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        return normalized.isEmpty() ? List.of("web") : normalized;
    }

    private boolean looksLikeSearchEngineNoise(List<CapabilityResult> results) {
        if (results == null || results.isEmpty()) {
            return false;
        }
        for (CapabilityResult result : results) {
            if (looksLikeSearchEngineNoise(result)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeSearchEngineNoise(CapabilityResult result) {
        String payload = result == null ? "" : safe(result.payload()).toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(payload)) {
            return false;
        }
        boolean hasSearchTabs = payload.contains("all images news")
                || payload.contains("images news videos")
                || payload.contains("all news images");
        boolean hasCountryList = payload.contains("argentina")
                && payload.contains("australia")
                && (payload.contains("brazil") || payload.contains("canada") || payload.contains("united states"));
        return hasSearchTabs || hasCountryList;
    }

    private String refineNoisySearchQuery(String originalQuestion, String currentQuestion) {
        String base = firstText(originalQuestion, currentQuestion);
        if (!StringUtils.hasText(base)) {
            return "天气 查询 今日";
        }
        String normalized = base.replaceAll("\\s+", " ").trim();
        if (containsAny(normalized.toLowerCase(Locale.ROOT), "天气", "气温", "温度", "weather")) {
            return normalized + " 今日 天气 实时 wttr.in weather.com.cn";
        }
        return normalized + " 精确结果 官方 信息";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String textValue(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && !value.isNull() && StringUtils.hasText(value.asText())
                ? value.asText()
                : fallback;
    }

    private String firstText(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first.trim();
        }
        return StringUtils.hasText(second) ? second.trim() : "";
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
        boolean verificationFailed = verification != null && !verification.sufficient();
        if (verificationFailed) {
            builder.append("结论：校验未通过，当前证据不足以可靠回答这次请求。");
            if (StringUtils.hasText(reason)) {
                builder.append("\n原因：").append(reason);
            }
            builder.append("\n\n下面是后端真实拿到的结果。\n\n");
        } else {
            builder.append("结论：我已经按 Agent 链路执行了这次请求");
            if (StringUtils.hasText(reason)) {
                builder.append("，但模型总结阶段不稳定：").append(reason);
            }
            builder.append("。下面是后端真实拿到的结果。\n\n");
        }
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
