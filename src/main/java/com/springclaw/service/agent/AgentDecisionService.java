package com.springclaw.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import com.springclaw.service.chat.impl.ModelTransportGuardService;
import com.springclaw.tool.runtime.CapabilityRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.Locale;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Hybrid decision service: deterministic rules first, model classification for
 * ambiguous or low-confidence keyword matches.
 *
 * Routing strategy:
 * 1. Rule router runs first — fast deterministic path for clear keyword matches.
 * 2. If keyword match is high-confidence (≥2 keywords, single dominant toolset), accept rule decision.
 * 3. If keyword match is low-confidence or ambiguous, model classifies intent.
 * 4. If no keywords match but query suggests a capability request, model classifies.
 * 5. If model fails, fall back to rule decision.
 *
 * This design avoids fragile keyword expansion — the model handles edge cases
 * based on semantic understanding, not substring matching.
 */
@Service
public class AgentDecisionService {

    private static final Logger log = LoggerFactory.getLogger(AgentDecisionService.class);

    private final AgentDecisionRouter ruleRouter;
    private final AiProviderService aiProviderService;
    private final ModelCallExecutor modelCallExecutor;
    private final ModelTransportGuardService modelTransportGuardService;
    private final CapabilityRegistry capabilityRegistry;
    private final ObjectMapper objectMapper;
    private final boolean modelRouterEnabled;

    public AgentDecisionService(AgentDecisionRouter ruleRouter,
                                AiProviderService aiProviderService,
                                ModelCallExecutor modelCallExecutor,
                                ModelTransportGuardService modelTransportGuardService,
                                CapabilityRegistry capabilityRegistry,
                                ObjectMapper objectMapper,
                                @Value("${springclaw.agent.decision.model-router-enabled:true}") boolean modelRouterEnabled) {
        this.ruleRouter = ruleRouter;
        this.aiProviderService = aiProviderService;
        this.modelCallExecutor = modelCallExecutor;
        this.modelTransportGuardService = modelTransportGuardService;
        this.capabilityRegistry = capabilityRegistry;
        this.objectMapper = objectMapper;
        this.modelRouterEnabled = modelRouterEnabled;
    }

    public AgentDecision decide(AgentDecisionRequest request) {
        AgentDecision ruleDecision = ruleRouter.routeByRules(request);
        if (!shouldAskModel(ruleDecision, request)) {
            return ruleDecision;
        }
        try {
            AiProviderService.ActiveChatClient activeClient = aiProviderService.activeClient();
            if (!modelTransportGuardService.isModelCallEnabled(activeClient)) {
                return ruleDecision;
            }
            ModelCallExecutor.ModelCallResult<String> result = modelCallExecutor.executeChat(
                    activeClient,
                    "agent-decision-router",
                    new ModelCallExecutor.ChatRequestContext(
                            request.requestId(), request.sessionKey(), request.channel(), request.userId()
                    ),
                    true,
                    client -> {
                        var response = client.chatClient().prompt()
                                .system("你是 Agent 路由器。只输出 JSON，不要解释。")
                                .user(renderRouterPrompt(request.question()))
                                .call()
                                .chatResponse();
                        return new ModelCallExecutor.ChatOperationResult<>(ModelCallExecutor.extractText(response), response);
                    }
            );
            AgentDecision modelDecision = parseModelDecision(result.value(), ruleDecision);
            log.info("模型路由完成: requestId={}, ruleIntent={}, modelIntent={}, reason={}",
                    request.requestId(), ruleDecision.intent(), modelDecision.intent(), modelDecision.reason());
            return StringUtils.hasText(modelDecision.reason())
                    ? modelDecision
                    : new AgentDecision(modelDecision.intent(), modelDecision.executionPath(), modelDecision.selectedCapabilities(), modelDecision.riskLevel(), modelDecision.requiresConfirmation(), "模型完成意图分类（关键词匹配置信度不足）。");
        } catch (Exception ex) {
            log.warn("模型意图路由失败，回退规则路由。requestId={}, reason={}", request.requestId(), ex.getMessage());
            return ruleDecision;
        }
    }

    /**
     * Determine whether the model should classify intent instead of relying on
     * the deterministic rule router.
     *
     * Model routing is triggered when:
     * 1. Rule router returns ask_clarification (ambiguous action, no object)
     * 2. Keyword match is low-confidence: only 1 keyword matched
     * 3. Multiple toolsets compete with similar keyword match counts
     * 4. No keyword match but query is long enough to be a capability request
     */
    private boolean shouldAskModel(AgentDecision decision, AgentDecisionRequest request) {
        if (!modelRouterEnabled || request == null || !StringUtils.hasText(request.question())) {
            return false;
        }
        // Case 1: Rule router can't decide (ambiguous action without object)
        if ("ask_clarification".equalsIgnoreCase(decision.executionPath())) {
            return true;
        }
        if ("model_control".equalsIgnoreCase(decision.intent())) {
            return false;
        }

        String question = request.question().trim();
        String lowerQuestion = question.toLowerCase(Locale.ROOT);

        // Case 2 & 3: Keyword match is low-confidence or ambiguous
        if (capabilityRegistry != null && !"general".equals(decision.intent())) {
            List<CapabilityRegistry.CapabilityEntry> matches = capabilityRegistry.findByTriggerKeywords(question);
            if (!matches.isEmpty()) {
                int bestMatchCount = matches.get(0).countKeywordMatches(lowerQuestion);
                String bestToolset = matches.get(0).toolset();

                // Low confidence: only 1 keyword matched → ask model to verify/override
                if (bestMatchCount <= 1) {
                    log.debug("关键词匹配置信度低(bestMatchCount=1)，触发模型路由: question={}", question);
                    return true;
                }

                // Ambiguous: competing toolset with similar match count
                for (int i = 1; i < matches.size(); i++) {
                    if (!matches.get(i).toolset().equals(bestToolset)) {
                        int competingMatchCount = matches.get(i).countKeywordMatches(lowerQuestion);
                        if (competingMatchCount >= bestMatchCount - 1) {
                            log.debug("多工具集竞争(best={}, competing={})，触发模型路由: question={}",
                                    bestMatchCount, competingMatchCount, question);
                            return true;
                        }
                    }
                }

                // High confidence: ≥2 keywords from single dominant toolset → skip model
                return false;
            }
        }

        return false;
    }

    /**
     * Render the model router prompt with full capability descriptions.
     * Uses .replace() template approach to avoid IllegalFormatConversionException
     * when user input contains literal '%' characters.
     */
    private String renderRouterPrompt(String question) {
        String capabilityList = renderCapabilityList();
        String template = """
                你是 SpringClaw Agent 的意图路由器。根据用户请求的语义和可用能力描述，判断最合适的意图和执行路径。

                # 可用能力
                {{CAPABILITIES}}

                # 意图分类规则
                - general: 普通知识聊天、问答，不需要任何工具能力
                - workspace_analysis: 与项目源码相关的问题——代码审查、架构分析、代码统计、文件检索、技术栈识别等
                - local_files: 读取本地授权目录中的文件内容
                - web_research: 联网搜索、获取网页信息、查天气、查汇率等需要联网的能力
                - skill_task: 执行已注册的技能/脚本
                - model_control: 模型管理与系统控制命令
                - scheduled_task: 定时/周期执行任务
                - unknown: 意图不明确，需要用户澄清

                # 关键区分规则（特别注意）
                - "审查代码"、"分析项目"、"代码质量"、"项目架构" → workspace_analysis，不是 web_research
                - "帮我看看/查一下代码" → workspace_analysis（"看/查"在此语境是审查/了解项目，不是联网搜索）
                - "查一下价格/天气/新闻" → web_research（"查"在此语境是搜索外部信息）
                - "分析+具体代码术语" → workspace_analysis（即使不出现"项目"字样）

                # 输出格式
                只输出 JSON，不要解释：
                {"intent":"general|workspace_analysis|local_files|web_research|skill_task|model_control|scheduled_task|unknown","executionPath":"basic_model|agent_tools|skill_direct|task_draft|ask_clarification","selectedCapabilities":["workspace-search","workspace-review","file","skill-library"],"riskLevel":"read|write|side_effect|dangerous","requiresConfirmation":true|false,"reason":"一句中文原因"}

                # 用户请求
                {{QUESTION}}
                """;
        return template
                .replace("{{CAPABILITIES}}", capabilityList)
                .replace("{{QUESTION}}", question == null ? "" : question.trim());
    }

    /** Render all registered capabilities as a human-readable list for the model prompt. */
    private String renderCapabilityList() {
        if (capabilityRegistry == null) {
            return "（无能力注册信息）";
        }
        return capabilityRegistry.listAll().stream()
                .sorted(Comparator.comparing(CapabilityRegistry.CapabilityEntry::toolset)
                        .thenComparing(CapabilityRegistry.CapabilityEntry::id))
                .map(entry -> "- " + entry.id() + ": " + entry.description() + " (toolset=" + entry.toolset() + ")")
                .collect(Collectors.joining("\n"));
    }

    private AgentDecision parseModelDecision(String raw, AgentDecision fallback) throws Exception {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            text = text.substring(start, end + 1);
        }
        JsonNode node = objectMapper.readTree(text);
        List<String> capabilities = node.has("selectedCapabilities") && node.get("selectedCapabilities").isArray()
                ? objectMapper.readerForListOf(String.class).readValue(node.get("selectedCapabilities"))
                : fallback.selectedCapabilities();
        return new AgentDecision(
                textValue(node, "intent", fallback.intent()),
                textValue(node, "executionPath", fallback.executionPath()),
                capabilities,
                textValue(node, "riskLevel", fallback.riskLevel()),
                node.has("requiresConfirmation") ? node.get("requiresConfirmation").asBoolean(fallback.requiresConfirmation()) : fallback.requiresConfirmation(),
                textValue(node, "reason", "模型完成意图分类。")
        );
    }

    private String textValue(JsonNode node, String field, String fallback) {
        return node != null && node.has(field) && StringUtils.hasText(node.get(field).asText())
                ? node.get(field).asText()
                : fallback;
    }
}
