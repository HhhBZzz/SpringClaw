package com.openclaw.service.chat.impl;

import com.openclaw.service.ai.AiProviderService;
import com.openclaw.service.chat.LocalSkillFallbackService;
import com.openclaw.service.context.AssembledContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 将确定性本地执行结果翻译为更自然的对外答复。
 */
@Service
public class LocalExecutionNarrator {

    private static final Logger log = LoggerFactory.getLogger(LocalExecutionNarrator.class);

    private final ChatResponsePolicyService chatResponsePolicyService;
    private final ModelCallExecutor modelCallExecutor;

    public LocalExecutionNarrator(ChatResponsePolicyService chatResponsePolicyService,
                                  ModelCallExecutor modelCallExecutor) {
        this.chatResponsePolicyService = chatResponsePolicyService;
        this.modelCallExecutor = modelCallExecutor;
    }

    public String narrate(String systemPrompt,
                          AssembledContext assembled,
                          LocalSkillFallbackService.LocalSkillResult localResult,
                          AiProviderService.ActiveChatClient narrationClient,
                          boolean modelCallEnabled) {
        if (!modelCallEnabled) {
            return localResult.fallbackAnswer();
        }
        try {
            ModelCallExecutor.ModelCallResult<String> narrationResult = modelCallExecutor.executeChat(
                    narrationClient,
                    "local-narration",
                    new ModelCallExecutor.ChatRequestContext(
                            "",
                            assembled.sessionKey(),
                            assembled.channel(),
                            assembled.userId()
                    ),
                    false,
                    client -> {
                        var response = client.chatClient().prompt()
                                .system(systemPrompt)
                                .user(renderPrompt(assembled, localResult))
                                .call()
                                .chatResponse();
                        return new ModelCallExecutor.ChatOperationResult<>(
                                ModelCallExecutor.extractText(response),
                                response
                        );
                    }
            );
            String response = narrationResult.value();
            if (!StringUtils.hasText(response)
                    || chatResponsePolicyService.looksLikeMetaRefusal(response)
                    || chatResponsePolicyService.looksLikeProjectAccessRefusal(response)
                    || chatResponsePolicyService.looksLikeToolFailureRefusal(response)) {
                return localResult.fallbackAnswer();
            }
            return response;
        } catch (Exception ex) {
            log.warn("本地执行结果 AI 整理失败，route={}, reason={}", localResult.route(), ex.getMessage());
            return localResult.fallbackAnswer();
        }
    }

    private String renderPrompt(AssembledContext assembled,
                                LocalSkillFallbackService.LocalSkillResult localResult) {
        PromptTemplate template = new PromptTemplate("""
                你是面向最终用户的智能体答复器。以下操作已经由系统真实执行完成，请基于真实结果给用户回复。
                要求：
                1) 只能基于已执行完成的真实结果回答，不得虚构未执行动作或未获得的数据；
                2) 不要出现“本地技能兜底、route、OPAR、provider 管理器、系统提示词、阶段”等内部词汇；
                3) 默认用自然中文回答，先给结论，再补必要依据；
                4) 如果用户没有要求详细，不要把后台面板全部展开；
                5) 如果这次结果本身偏详细，请保留关键字段、数字、文件名、模型名、provider 名，不要改错；
                6) 如果结果显示失败，请明确失败点和下一步建议；
                7) 如果这是模型/provider 状态问题，请用第一人称自然回答，但底层事实必须准确。

                用户问题：
                {question}

                执行类型：
                {route}

                是否偏详细：
                {detailed}

                真实执行结果：
                {details}
                """);
        return template.render(Map.of(
                "question", safe(assembled.question()),
                "route", safe(localResult.route()),
                "detailed", localResult.detailed() ? "YES" : "NO",
                "details", safe(localResult.executionDetails())
        ));
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }
}
