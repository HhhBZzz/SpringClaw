package com.openclaw.service.chat.impl;

import com.openclaw.service.ai.AiProviderService;
import com.openclaw.service.context.AssembledContext;
import com.openclaw.service.usage.LlmUsageRecordService;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 负责识别“模型查询/切换”意图。
 */
@Service
public class ModelControlIntentService {

    private final AiProviderService aiProviderService;
    private final LlmUsageRecordService llmUsageRecordService;

    public ModelControlIntentService(AiProviderService aiProviderService) {
        this(aiProviderService, null);
    }

    @Autowired
    public ModelControlIntentService(AiProviderService aiProviderService,
                                     LlmUsageRecordService llmUsageRecordService) {
        this.aiProviderService = aiProviderService;
        this.llmUsageRecordService = llmUsageRecordService;
    }

    public boolean looksLikeProviderIntentCandidate(String question, String eventContext) {
        String lower = safe(question).toLowerCase();
        if (!StringUtils.hasText(lower)) {
            return false;
        }
        boolean mentionsModelDomain = lower.contains("模型")
                || lower.contains("provider")
                || lower.contains("claude")
                || lower.contains("qwen")
                || lower.contains("千问")
                || lower.contains("coder")
                || lower.contains("plus")
                || lower.contains("max")
                || lower.contains("deepseek")
                || lower.contains("深度求索")
                || lower.contains("reasoner")
                || lower.contains("glm")
                || lower.contains("kimi")
                || lower.contains("minimax")
                || lower.contains("codeplan")
                || lower.contains("coding plan")
                || lower.contains("阿里")
                || lower.contains("通义")
                || lower.contains("主模型")
                || lower.contains("默认模型");
        boolean mentionsControl = lower.contains("切")
                || lower.contains("换")
                || lower.contains("改用")
                || lower.contains("当前")
                || lower.contains("现在")
                || lower.contains("哪个")
                || lower.contains("什么")
                || lower.contains("试试");
        if (mentionsModelDomain && mentionsControl) {
            return true;
        }
        return hasRecentModelControlContext(eventContext) && looksLikeAmbiguousModelFollowUp(lower);
    }

    public String classify(AiProviderService.ActiveChatClient activeClient, AssembledContext assembled) {
        return classify(activeClient, assembled, "");
    }

    public String classify(AiProviderService.ActiveChatClient activeClient,
                           AssembledContext assembled,
                           String requestId) {
        if (!looksLikeProviderIntentCandidate(assembled.question(), assembled.eventContext())) {
            return null;
        }
        String switchOptions = renderModelControlOptions();
        String recentContext = renderRecentModelIntentContext(assembled.eventContext());
        ChatResponse response = activeClient.chatClient().prompt()
                .user("""
                        你是模型切换意图分类器。请判断用户是否在“查询当前模型”“请求切换模型提供方”或“请求切换具体模型”。
                        仅允许输出以下格式之一，不要输出其他文字：
                        QUERY
                        SWITCH_PROVIDER:<providerId>
                        SWITCH_MODEL:<providerId>:<modelId>
                        NONE

                        规则：
                        1) 只有用户明确表达“查看当前模型 / 切换模型”时才输出 QUERY 或 SWITCH_*。
                        2) 只是讨论模型优缺点、比较模型、提到模型名字但没有控制意图时，输出 NONE。
                        3) “默认模型 / 主模型 / claude” 且是切换意图时，映射为 SWITCH_PROVIDER:primary。
                        4) “阿里 / 通义 / codeplan / coding plan” 且是切换意图时，优先映射为 coding-plan。
                        5) “千问 / qwen” 且没有明确到具体 model 时，映射为 SWITCH_PROVIDER:qwen 或 SWITCH_PROVIDER:coding-plan，但必须结合下面白名单。
                        6) “deepseek / 深度求索” 且没有明确到具体 model 时，映射为 SWITCH_PROVIDER:deepseek。
                        7) 只有命中白名单中的具体 model 时，才允许输出 SWITCH_MODEL。
                        8) 如果用户当前说法里出现“那个 / 这个 / 切回去 / 换回去 / 就它 / 上一个”，允许结合最近会话上下文判断真实目标。

                        当前可切换 provider 与 model 白名单：
                        %s

                        最近会话上下文（仅供解析指代）：
                        %s

                        用户当前输入：
                        %s
                        """.formatted(switchOptions, recentContext, safe(assembled.question())))
                .call()
                .chatResponse();
        recordUsage(activeClient, assembled, requestId, response);
        return normalizeCommand(ModelCallExecutor.extractText(response));
    }

    private String normalizeCommand(String rawCommand) {
        String command = safe(rawCommand).trim();
        return StringUtils.hasText(command) ? command : null;
    }

    private String renderModelControlOptions() {
        @SuppressWarnings("unchecked")
        List<AiProviderService.ProviderView> providers =
                (List<AiProviderService.ProviderView>) aiProviderService.summary().get("providers");
        StringBuilder builder = new StringBuilder();
        for (AiProviderService.ProviderView provider : providers) {
            builder.append("- ").append(provider.providerId())
                    .append(" => ")
                    .append(provider.availableModels() == null || provider.availableModels().isEmpty()
                            ? provider.model()
                            : String.join(", ", provider.availableModels()))
                    .append('\n');
        }
        return builder.toString().trim();
    }

    private boolean hasRecentModelControlContext(String eventContext) {
        String lower = safe(eventContext).toLowerCase();
        if (!StringUtils.hasText(lower)) {
            return false;
        }
        return lower.contains("当前活动 provider")
                || lower.contains("当前模型:")
                || lower.contains("已切换模型提供方")
                || lower.contains("切换到千问")
                || lower.contains("切换到 claude")
                || lower.contains("coding-plan")
                || lower.contains("qwen3")
                || lower.contains("claude-opus")
                || lower.contains("模型控制意图")
                || lower.contains("provider:");
    }

    private boolean looksLikeAmbiguousModelFollowUp(String lower) {
        boolean mentionsPronoun = lower.contains("那个")
                || lower.contains("这个")
                || lower.contains("它")
                || lower.contains("上一个")
                || lower.contains("刚才")
                || lower.contains("前面");
        boolean mentionsControl = lower.contains("切")
                || lower.contains("换")
                || lower.contains("改")
                || lower.contains("用")
                || lower.contains("回")
                || lower.contains("就")
                || lower.contains("还是");
        return mentionsPronoun && mentionsControl;
    }

    private String renderRecentModelIntentContext(String eventContext) {
        if (!hasRecentModelControlContext(eventContext)) {
            return "（无可用上下文）";
        }
        return safe(eventContext);
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private void recordUsage(AiProviderService.ActiveChatClient activeClient,
                             AssembledContext assembled,
                             String requestId,
                             ChatResponse response) {
        if (llmUsageRecordService == null || response == null) {
            return;
        }
        llmUsageRecordService.recordChatResponse(
                new LlmUsageRecordService.ChatResponseContext(
                        safe(requestId),
                        assembled == null ? "" : safe(assembled.sessionKey()),
                        assembled == null ? "" : safe(assembled.channel()),
                        assembled == null ? "" : safe(assembled.userId()),
                        activeClient == null ? "" : safe(activeClient.providerId()),
                        activeClient == null ? "" : safe(activeClient.model()),
                        "model-control-intent"
                ),
                response
        );
    }
}
