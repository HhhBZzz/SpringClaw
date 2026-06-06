package com.springclaw.service.chat.impl;

import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.usage.LlmUsageRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 最短路径引擎：处理普通聊天（general intent），不挂工具，直接调模型流式输出。
 * 从 ChatServiceImpl.streamBasicModelAnswer 提取，实现 AgentEngine 接口。
 */
@Service
public class BasicStreamEngine implements AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(BasicStreamEngine.class);

    private final ModelCallExecutor modelCallExecutor;
    private final ConversationAdvisorSupport conversationAdvisorSupport;
    private final ModelTransportGuardService modelTransportGuardService;
    private final ChatResponsePolicyService chatResponsePolicyService;
    private final LlmUsageRecordService llmUsageRecordService;
    private final OparLoopEngine oparLoopEngine;

    public BasicStreamEngine(ModelCallExecutor modelCallExecutor,
                             ConversationAdvisorSupport conversationAdvisorSupport,
                             ModelTransportGuardService modelTransportGuardService,
                             ChatResponsePolicyService chatResponsePolicyService,
                             LlmUsageRecordService llmUsageRecordService,
                             OparLoopEngine oparLoopEngine) {
        this.modelCallExecutor = modelCallExecutor;
        this.conversationAdvisorSupport = conversationAdvisorSupport;
        this.modelTransportGuardService = modelTransportGuardService;
        this.chatResponsePolicyService = chatResponsePolicyService;
        this.llmUsageRecordService = llmUsageRecordService;
        this.oparLoopEngine = oparLoopEngine;
    }

    @Override
    public String name() {
        return "basic-stream";
    }

    @Override
    public int priority() {
        return 1; // 最高优先级：普通聊天优先走最短路径
    }

    @Override
    public boolean supports(ChatContext ctx) {
        if (ctx == null) return false;
        AgentDecision decision = ctx.decision();
        return decision != null
                && decision.isGeneral()
                && modelTransportGuardService.isModelCallEnabled(ctx.activeClient());
    }

    @Override
    public ChatExecutionResult execute(ChatContext ctx, OparLoopEngine.FallbackResponder fallbackResponder) {
        try {
            ModelCallExecutor.ModelCallResult<String> result = modelCallExecutor.executeChat(
                    ctx.activeClient(),
                    "basic-answer",
                    new ModelCallExecutor.ChatRequestContext(
                            ctx.requestId(),
                            ctx.assembled().sessionKey(),
                            ctx.assembled().channel(),
                            ctx.assembled().userId()
                    ),
                    true,
                    client -> {
                        var response = conversationAdvisorSupport.apply(
                                        client.chatClient().prompt()
                                                .system(ctx.systemPrompt())
                                                .user(renderBasicChatPrompt(ctx.assembled())),
                                        ctx.assembled().sessionKey(),
                                        ctx.assembled().userId())
                                .call()
                                .chatResponse();
                        return new ModelCallExecutor.ChatOperationResult<>(
                                ModelCallExecutor.extractText(response),
                                response
                        );
                    }
            );
            String answer = result.value();
            if (!StringUtils.hasText(answer)) {
                LocalSkillFallbackService.LocalSkillResult localResult = oparLoopEngine.tryLocalFallbackResult(ctx.assembled().question());
                if (localResult != null) {
                    answer = oparLoopEngine.narrateLocalExecution(ctx.systemPrompt(), ctx.assembled(), localResult);
                } else {
                    answer = fallbackResponder.respond(modelTransportGuardService.disabledModelReason(ctx.activeClient()), ctx.assembled());
                }
            }
            return new ChatExecutionResult(
                    ctx.assembled().observePrompt(),
                    "BASIC_STREAM: 普通聊天最短路径。",
                    "未挂载工具，直接模型回答。",
                    answer,
                    true
            );
        } catch (Exception ex) {
            log.warn("BasicStream 执行失败，sessionKey={}, reason={}", ctx.assembled().sessionKey(), ex.getMessage());
            modelTransportGuardService.markFailure(ctx.activeClient().providerId(), ex);
            LocalSkillFallbackService.LocalSkillResult localResult = oparLoopEngine.tryLocalFallbackResult(ctx.assembled().question());
            if (localResult != null) {
                String answer = oparLoopEngine.narrateLocalExecution(ctx.systemPrompt(), ctx.assembled(), localResult);
                return new ChatExecutionResult(ctx.assembled().observePrompt(), "BASIC_STREAM: 降级到本地技能。", localResult.executionDetails(), answer, false);
            }
            return new ChatExecutionResult(
                    ctx.assembled().observePrompt(),
                    "BASIC_STREAM: 模型调用失败。",
                    "模型不可用: " + ex.getMessage(),
                    fallbackResponder.respond(modelTransportGuardService.disabledModelReason(ctx.activeClient()), ctx.assembled()),
                    false
            );
        }
    }

    private String renderBasicChatPrompt(AssembledContext assembled) {
        return """
                用户问题：
                %s

                直接回答用户问题，保持中文自然、完整、有重点。
                这是普通聊天路径：不要调用工具，不要输出内部阶段名，不要提到路由、OPAR、兜底或实现细节。
                如果问题本身需要外部文件、网页、项目源码或实时数据，简洁说明需要进入 Agent 工具链路。
                """.formatted(safe(assembled == null ? null : assembled.question()));
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }
}
