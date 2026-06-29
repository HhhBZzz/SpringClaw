package com.springclaw.service.chat.impl;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 元话术检测与重试执行器。
 * 负责最终回答生成、身份/阶段元话术检测、自动修正重试。
 */
@Component
public class MetaGuardExecutor {

    private static final Logger log = LoggerFactory.getLogger(MetaGuardExecutor.class);

    private final AiProviderService aiProviderService;
    private final OparLoopEngine oparLoopEngine;
    private final ChatResponsePolicyService chatResponsePolicyService;
    private final ModelTransportGuardService modelTransportGuardService;
    private final ModelCallExecutor modelCallExecutor;
    private final ConversationAdvisorSupport conversationAdvisorSupport;
    private final boolean metaGuardEnabled;
    private final int metaGuardRetryTimes;

    public MetaGuardExecutor(AiProviderService aiProviderService,
                             OparLoopEngine oparLoopEngine,
                             ChatResponsePolicyService chatResponsePolicyService,
                             ModelTransportGuardService modelTransportGuardService,
                             ModelCallExecutor modelCallExecutor,
                             ConversationAdvisorSupport conversationAdvisorSupport,
                             @Value("${springclaw.chat.meta-guard.enabled:true}") boolean metaGuardEnabled,
                             @Value("${springclaw.chat.meta-guard.retry-times:1}") int metaGuardRetryTimes) {
        this.aiProviderService = aiProviderService;
        this.oparLoopEngine = oparLoopEngine;
        this.chatResponsePolicyService = chatResponsePolicyService;
        this.modelTransportGuardService = modelTransportGuardService;
        this.modelCallExecutor = modelCallExecutor;
        this.conversationAdvisorSupport = conversationAdvisorSupport;
        this.metaGuardEnabled = metaGuardEnabled;
        this.metaGuardRetryTimes = Math.max(0, metaGuardRetryTimes);
    }

    /** 执行带元话术防护的最终回答生成 */
    public String execute(ChatContext context, String plan, String action) throws Exception {
        String prompt = oparLoopEngine.renderReflectPrompt(context, plan, action);
        AiProviderService.ActiveChatClient currentClient = aiProviderService.activeClient();
        ModelCallExecutor.ModelCallResult<String> answerResult = modelCallExecutor.executeChat(
                currentClient,
                "final-answer",
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
                                            .user(prompt),
                                    context.assembled().sessionKey(),
                                    context.assembled().userId())
                            .call()
                            .chatResponse();
                    return new ModelCallExecutor.ChatOperationResult<>(
                            ModelCallExecutor.extractText(response),
                            response
                    );
                }
        );
        String answer = answerResult.value();
        currentClient = answerResult.client();

        if (!metaGuardEnabled || !chatResponsePolicyService.looksLikeMetaRefusal(answer)) {
            return normalize(context, answer);
        }

        log.warn("检测到身份/阶段元话术，触发重试。sessionKey={}", context.assembled().sessionKey());
        String repaired = answer;
        for (int i = 0; i < metaGuardRetryTimes; i++) {
            String retryPrompt = oparLoopEngine.renderMetaRepairPrompt(context, plan, action, repaired);
            ModelCallExecutor.ModelCallResult<String> retryResult = modelCallExecutor.executeChat(
                    currentClient,
                    "meta-repair",
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
                                                .user(retryPrompt),
                                        context.assembled().sessionKey(),
                                        context.assembled().userId())
                                .call()
                                .chatResponse();
                        return new ModelCallExecutor.ChatOperationResult<>(
                                ModelCallExecutor.extractText(response),
                                response
                        );
                    }
            );
            repaired = retryResult.value();
            currentClient = retryResult.client();
            if (!chatResponsePolicyService.looksLikeMetaRefusal(repaired)) {
                return normalize(context, repaired);
            }
        }

        LocalSkillFallbackService.LocalSkillResult localResult = tryLocalFallbackOrNull(context);
        if (localResult != null) {
            return oparLoopEngine.narrateLocalExecution(context.systemPrompt(), context.assembled(), localResult);
        }
        return fallbackAnswer("模型输出了身份/阶段元话术，已降级输出。", context.assembled());
    }

    /** 对回答进行规范化处理，处理空回答、拒答和 XML 幻觉情况 */
    public String normalize(ChatContext context, String answer) {
        if (!StringUtils.hasText(answer)) {
            LocalSkillFallbackService.LocalSkillResult localResult = tryLocalFallbackOrNull(context);
            if (localResult != null) {
                return oparLoopEngine.narrateLocalExecution(context.systemPrompt(), context.assembled(), localResult);
            }
            return fallbackAnswer("模型返回空回答。", context.assembled());
        }
        // 检测并清洗 XML 工具调用幻觉
        if (chatResponsePolicyService.looksLikeHallucinatedXmlToolCall(answer)) {
            log.warn("检测到模型回答中包含幻觉 XML 工具调用标签，执行清洗。sessionKey={}", context.assembled().sessionKey());
            String cleaned = chatResponsePolicyService.stripHallucinatedXmlBlocks(answer);
            if (StringUtils.hasText(cleaned)) {
                return cleaned;
            }
            // 清洗后为空，降级到本地兜底
            LocalSkillFallbackService.LocalSkillResult localResult = tryLocalFallbackOrNull(context);
            if (localResult != null) {
                return oparLoopEngine.narrateLocalExecution(context.systemPrompt(), context.assembled(), localResult);
            }
            return fallbackAnswer("模型输出格式异常，已降级输出。", context.assembled());
        }
        if (chatResponsePolicyService.looksLikeProjectAccessRefusal(answer)
                || chatResponsePolicyService.looksLikeToolFailureRefusal(answer)) {
            LocalSkillFallbackService.LocalSkillResult localResult = tryLocalFallbackOrNull(context);
            if (localResult != null) {
                return oparLoopEngine.narrateLocalExecution(context.systemPrompt(), context.assembled(), localResult);
            }
        }
        return answer;
    }

    public String fallbackAnswer(String reason, AssembledContext context) {
        return chatResponsePolicyService.buildUserFacingFailureReply(reason, context.question());
    }

    private LocalSkillFallbackService.LocalSkillResult tryLocalFallbackOrNull(ChatContext context) {
        return oparLoopEngine.tryLocalFallbackResult(context.assembled().question());
    }
}
