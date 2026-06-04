package com.springclaw.service.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import com.springclaw.service.chat.impl.ModelTransportGuardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Hybrid decision service: deterministic rules first, optional light model classification for ambiguous requests.
 */
@Service
public class AgentDecisionService {

    private static final Logger log = LoggerFactory.getLogger(AgentDecisionService.class);

    private final AgentDecisionRouter ruleRouter;
    private final AiProviderService aiProviderService;
    private final ModelCallExecutor modelCallExecutor;
    private final ModelTransportGuardService modelTransportGuardService;
    private final ObjectMapper objectMapper;
    private final boolean modelRouterEnabled;

    public AgentDecisionService(AgentDecisionRouter ruleRouter,
                                AiProviderService aiProviderService,
                                ModelCallExecutor modelCallExecutor,
                                ModelTransportGuardService modelTransportGuardService,
                                ObjectMapper objectMapper,
                                @Value("${springclaw.agent.decision.model-router-enabled:true}") boolean modelRouterEnabled) {
        this.ruleRouter = ruleRouter;
        this.aiProviderService = aiProviderService;
        this.modelCallExecutor = modelCallExecutor;
        this.modelTransportGuardService = modelTransportGuardService;
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
            return StringUtils.hasText(modelDecision.reason())
                    ? modelDecision
                    : new AgentDecision(modelDecision.intent(), modelDecision.executionPath(), modelDecision.selectedCapabilities(), modelDecision.riskLevel(), modelDecision.requiresConfirmation(), "轻量模型完成模糊意图分类。");
        } catch (Exception ex) {
            log.warn("轻量 Agent 路由模型失败，回退规则路由。requestId={}, reason={}", request.requestId(), ex.getMessage());
            return ruleDecision;
        }
    }

    private boolean shouldAskModel(AgentDecision decision, AgentDecisionRequest request) {
        return modelRouterEnabled
                && decision != null
                && "ask_clarification".equalsIgnoreCase(decision.executionPath())
                && StringUtils.hasText(request == null ? null : request.question());
    }

    private String renderRouterPrompt(String question) {
        return """
                请把用户请求分类为 JSON：
                {"intent":"general|workspace_analysis|local_files|web_research|skill_task|scheduled_task|model_control|unknown","executionPath":"basic_model|agent_tools|skill_direct|task_draft|ask_clarification","selectedCapabilities":["..."],"riskLevel":"read|write|side_effect|dangerous","requiresConfirmation":true|false,"reason":"一句中文原因"}
                规则：普通知识聊天用 general/basic_model；项目源码用 workspace_analysis/agent_tools；本地授权文件用 local_files/agent_tools；网页/天气/新闻用 web_research/agent_tools；skill/脚本用 skill_task/skill_direct；定时/周期任务用 scheduled_task/task_draft 且 requiresConfirmation=true。
                用户请求：%s
                """.formatted(question == null ? "" : question.trim());
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
                textValue(node, "reason", "轻量模型完成模糊意图分类。")
        );
    }

    private String textValue(JsonNode node, String field, String fallback) {
        return node != null && node.has(field) && StringUtils.hasText(node.get(field).asText())
                ? node.get(field).asText()
                : fallback;
    }
}
