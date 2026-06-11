package com.springclaw.service.chat.impl;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.runtime.ToolOrchestrator;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 简化版 Agent 执行器：简单问题直答，需要时再让模型自行调用工具。
 */
@Service
public class SimplifiedOparEngine implements AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(SimplifiedOparEngine.class);

    private final AiProviderService aiProviderService;
    private final ToolOrchestrator toolOrchestrator;
    private final LocalSkillFallbackService localSkillFallbackService;
    private final LocalExecutionNarrator localExecutionNarrator;
    private final ModelTransportGuardService modelTransportGuardService;
    private final ModelCallExecutor modelCallExecutor;
    private final OparContextAwareSupport contextAwareSupport;
    private final ConversationAdvisorSupport conversationAdvisorSupport;
    private final ChatResponsePolicyService chatResponsePolicyService;
    private final LocalExecutionSupport localExecutionSupport;

    @org.springframework.beans.factory.annotation.Autowired
    public SimplifiedOparEngine(AiProviderService aiProviderService,
                                ToolOrchestrator toolOrchestrator,
                                LocalSkillFallbackService localSkillFallbackService,
                                LocalExecutionNarrator localExecutionNarrator,
                                ModelTransportGuardService modelTransportGuardService,
                                ModelCallExecutor modelCallExecutor,
                                OparContextAwareSupport contextAwareSupport,
                                ConversationAdvisorSupport conversationAdvisorSupport,
                                ChatResponsePolicyService chatResponsePolicyService,
                                LocalExecutionSupport localExecutionSupport) {
        this.aiProviderService = aiProviderService;
        this.toolOrchestrator = toolOrchestrator;
        this.localSkillFallbackService = localSkillFallbackService;
        this.localExecutionNarrator = localExecutionNarrator;
        this.modelTransportGuardService = modelTransportGuardService;
        this.modelCallExecutor = modelCallExecutor;
        this.contextAwareSupport = contextAwareSupport;
        this.conversationAdvisorSupport = conversationAdvisorSupport;
        this.chatResponsePolicyService = chatResponsePolicyService;
        this.localExecutionSupport = localExecutionSupport;
    }

    @Override
    public String name() {
        return "simplified";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean supports(ChatContext ctx) {
        return true; // 兜底引擎，总是可用
    }

    @Override
    public ChatExecutionResult execute(ChatContext ctx, AgentEngine.FallbackResponder fallbackResponder) {
        return run(
                ctx.activeClient(),
                ctx.systemPrompt(),
                ctx.assembled(),
                ctx.requestId(),
                fallbackResponder,
                ctx.decision()
        );
    }

    public ChatExecutionResult run(AiProviderService.ActiveChatClient activeClient,
                                   String systemPrompt,
                                   AssembledContext assembled,
                                   String requestId,
                                   AgentEngine.FallbackResponder fallbackResponder) {
        return run(activeClient, systemPrompt, assembled, requestId, fallbackResponder, null);
    }

    public ChatExecutionResult run(AiProviderService.ActiveChatClient activeClient,
                                   String systemPrompt,
                                   AssembledContext assembled,
                                   String requestId,
                                   AgentEngine.FallbackResponder fallbackResponder,
                                   AgentDecision decision) {
        // 本地短路三件套（context-aware / control-plane / priority structured）内部最终会通过
        // Spring AOP 反射调用 @Tool 方法（例如 SystemToolPack.now()）。
        // ToolRuntimeAspect 在拦截时读 ToolExecutionContextHolder，但本地短路路径之前没有 open()，
        // 导致 audit JSON 里 requestId/sessionKey/userId 是占位值。
        // 这里在调用本地短路前打开一个 LOCAL-SHORTCUT scope，让 audit 拿到真实上下文。
        // 见 docs/TURN_CONTRACT.md §2.4 known-gap #2。
        ToolExecutionContext localCtx = new ToolExecutionContext(
                assembled.sessionKey(),
                assembled.channel(),
                assembled.userId(),
                requestId,
                "LOCAL-SHORTCUT"
        );
        try (ToolExecutionContextHolder.Scope ignored = ToolExecutionContextHolder.open(localCtx)) {
            LocalSkillFallbackService.LocalSkillResult contextAware = contextAwareSupport.tryContextAwareLocalResult(assembled);
            if (contextAware != null) {
                return buildLocalResult(systemPrompt, assembled, contextAware, "SIMPLIFIED:CONTEXT_AWARE");
            }

            LocalSkillFallbackService.LocalSkillResult controlPlane = tryControlPlaneLocalResult(assembled.question());
            if (controlPlane != null) {
                return buildLocalResult(systemPrompt, assembled, controlPlane, "SIMPLIFIED:CONTROL_PLANE");
            }

            LocalSkillFallbackService.LocalSkillResult priorityStructured = tryPriorityStructuredLocalResult(assembled.question());
            if (priorityStructured != null) {
                return buildLocalResult(systemPrompt, assembled, priorityStructured, "SIMPLIFIED:PRIORITY_STRUCTURED");
            }
        }

        if (!modelTransportGuardService.isModelCallEnabled(activeClient)) {
            return buildDisabledResult(systemPrompt, activeClient, assembled, fallbackResponder);
        }

        Object[] tools = toolOrchestrator.selectAgentTools(assembled.channel(), assembled.userId(), decision);
        ToolExecutionContext toolContext = new ToolExecutionContext(
                assembled.sessionKey(),
                assembled.channel(),
                assembled.userId(),
                requestId,
                "ACT-SIMPLIFIED"
        );
        try (ToolExecutionContextHolder.Scope ignored = ToolExecutionContextHolder.open(toolContext)) {
            ModelCallExecutor.ModelCallResult<String> result = modelCallExecutor.executeChat(
                    activeClient,
                    "simplified-answer",
                    new ModelCallExecutor.ChatRequestContext(
                            requestId,
                            assembled.sessionKey(),
                            assembled.channel(),
                            assembled.userId()
                    ),
                    true,
                    client -> {
                        var requestSpec = client.chatClient().prompt()
                                .system(systemPrompt)
                                .user(renderUserPrompt(assembled.question()));
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
            String answer = safe(result.value());
            // 检测并清洗 DeepSeek V4 Pro 在禁用原生 tool calling 时可能产生的 XML 工具调用幻觉
            if (chatResponsePolicyService.looksLikeHallucinatedXmlToolCall(answer)) {
                log.warn("简化模式检测到模型回答包含幻觉 XML 工具调用标签，执行清洗。sessionKey={}", assembled.sessionKey());
                answer = chatResponsePolicyService.stripHallucinatedXmlBlocks(answer);
            }
            if (!StringUtils.hasText(answer)) {
                LocalSkillFallbackService.LocalSkillResult localResult = tryLocalFallbackResult(assembled.question());
                if (localResult != null) {
                    return buildLocalResult(systemPrompt, assembled, localResult, "SIMPLIFIED:LOCAL_FALLBACK");
                }
                return buildDisabledResult(systemPrompt, result.client(), assembled, fallbackResponder);
            }
            return new ChatExecutionResult(
                    assembled.observePrompt(),
                    "SIMPLIFIED: 模型自行判断是否需要工具。",
                    buildActionTrace(result, tools),
                    answer,
                    true
            );
        } catch (Exception ex) {
            log.warn("简化模式回答失败，sessionKey={}, reason={}", assembled.sessionKey(), ex.getMessage());
            LocalSkillFallbackService.LocalSkillResult localResult = tryLocalFallbackResult(assembled.question());
            if (localResult != null) {
                return buildLocalResult(systemPrompt, assembled, localResult, "SIMPLIFIED:DEGRADED_LOCAL");
            }
            return new ChatExecutionResult(
                    assembled.observePrompt(),
                    "SIMPLIFIED: 模型调用失败，已停止进一步规划。",
                    "行动降级：未取得模型回答。reason=" + safe(ex.getMessage()),
                    fallbackResponder.respond(modelTransportGuardService.disabledModelReason(activeClient), assembled),
                    false
            );
        }
    }

    private ChatExecutionResult buildLocalResult(String systemPrompt,
                                                 AssembledContext assembled,
                                                 LocalSkillFallbackService.LocalSkillResult localResult,
                                                 String stage) {
        AiProviderService.ActiveChatClient narrationClient = aiProviderService.activeClient();
        String reflect = localExecutionNarrator.narrate(
                systemPrompt,
                assembled,
                localResult,
                narrationClient,
                modelTransportGuardService.isModelCallEnabled(narrationClient)
        );
        return new ChatExecutionResult(
                assembled.observePrompt(),
                stage + ": 已命中确定性本地执行路径。",
                localResult.executionDetails(),
                reflect,
                false
        );
    }

    private ChatExecutionResult buildDisabledResult(String systemPrompt,
                                                    AiProviderService.ActiveChatClient activeClient,
                                                    AssembledContext assembled,
                                                    AgentEngine.FallbackResponder fallbackResponder) {
        LocalSkillFallbackService.LocalSkillResult localResult = tryLocalFallbackResult(assembled.question());
        if (localResult != null) {
            return buildLocalResult(systemPrompt, assembled, localResult, "SIMPLIFIED:MODEL_DISABLED");
        }
        return new ChatExecutionResult(
                assembled.observePrompt(),
                "SIMPLIFIED: 模型服务当前不可用，跳过模型调用。",
                modelTransportGuardService.disabledModelActionReason(activeClient),
                fallbackResponder.respond(modelTransportGuardService.disabledModelReason(activeClient), assembled),
                false
        );
    }

    private LocalSkillFallbackService.LocalSkillResult tryControlPlaneLocalResult(String question) {
        return localExecutionSupport.tryControlPlane(question, true);
    }

    private LocalSkillFallbackService.LocalSkillResult tryLocalFallbackResult(String question) {
        return localExecutionSupport.tryFallback(question, true);
    }

    private LocalSkillFallbackService.LocalSkillResult tryPriorityStructuredLocalResult(String question) {
        return localExecutionSupport.tryPriorityStructured(question, true);
    }

    private String renderUserPrompt(String question) {
        return """
                用户问题：%s

                直接回答用户问题。
                如果仍需要更多工具，你可以自行决定调用合适的工具；如果不需要工具，就直接给出最终答案。
                不要输出阶段名、计划过程、内部系统说明。
                """.formatted(safe(question));
    }

    private String buildActionTrace(ModelCallExecutor.ModelCallResult<String> result,
                                    Object[] tools) {
        StringBuilder builder = new StringBuilder();
        builder.append("SIMPLIFIED: 已完成一次模型回答");
        if (result.failedOver()) {
            builder.append("，并执行了同 provider 模型切换");
        }
        if (tools != null && tools.length > 0) {
            builder.append("。可用工具数量=").append(tools.length);
        }
        if (result.attemptedModels() != null && !result.attemptedModels().isEmpty()) {
            builder.append("。尝试模型=").append(String.join(" -> ", result.attemptedModels()));
        }
        return builder.toString();
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }
}