package com.springclaw.service.chat.impl;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.LocalSkillFallbackService;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;

/**
 * 简化版 Agent 执行器：简单问题直答，需要时再让模型自行调用工具。
 */
@Service
public class SimplifiedOparEngine {

    private static final Logger log = LoggerFactory.getLogger(SimplifiedOparEngine.class);

    private final AiProviderService aiProviderService;
    private final ToolOrchestrator toolOrchestrator;
    private final LocalSkillFallbackService localSkillFallbackService;
    private final LocalExecutionNarrator localExecutionNarrator;
    private final ModelTransportGuardService modelTransportGuardService;
    private final ModelCallExecutor modelCallExecutor;
    private final OparContextAwareSupport contextAwareSupport;
    private final ConversationAdvisorSupport conversationAdvisorSupport;
    private final MessageEventService messageEventService;

    public SimplifiedOparEngine(AiProviderService aiProviderService,
                                ToolOrchestrator toolOrchestrator,
                                LocalSkillFallbackService localSkillFallbackService,
                                LocalExecutionNarrator localExecutionNarrator,
                                ModelTransportGuardService modelTransportGuardService,
                                ModelCallExecutor modelCallExecutor,
                                OparContextAwareSupport contextAwareSupport,
                                ConversationAdvisorSupport conversationAdvisorSupport,
                                MessageEventService messageEventService) {
        this.aiProviderService = aiProviderService;
        this.toolOrchestrator = toolOrchestrator;
        this.localSkillFallbackService = localSkillFallbackService;
        this.localExecutionNarrator = localExecutionNarrator;
        this.modelTransportGuardService = modelTransportGuardService;
        this.modelCallExecutor = modelCallExecutor;
        this.contextAwareSupport = contextAwareSupport;
        this.conversationAdvisorSupport = conversationAdvisorSupport;
        this.messageEventService = messageEventService;
    }

    public ChatExecutionResult run(AiProviderService.ActiveChatClient activeClient,
                                   String systemPrompt,
                                   AssembledContext assembled,
                                   String requestId,
                                   OparLoopEngine.FallbackResponder fallbackResponder) {
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

        if (!modelTransportGuardService.isModelCallEnabled(activeClient)) {
            return buildDisabledResult(systemPrompt, activeClient, assembled, fallbackResponder);
        }

        Object[] tools = toolOrchestrator.selectAgentTools(assembled.channel(), assembled.userId());
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
            if (!StringUtils.hasText(answer)) {
                LocalSkillFallbackService.LocalSkillResult localResult = tryLocalFallbackResult(assembled.question());
                if (localResult != null) {
                    return buildLocalResult(systemPrompt, assembled, localResult, "SIMPLIFIED:LOCAL_FALLBACK");
                }
                String auditFallback = buildToolAuditFallback(assembled, requestId, "模型未返回可用内容");
                if (StringUtils.hasText(auditFallback)) {
                    return new ChatExecutionResult(
                            assembled.observePrompt(),
                            "SIMPLIFIED: 工具已执行，但模型未返回可用内容，已改用工具结果兜底。",
                            "行动已完成部分工具调用，使用工具审计结果直接组织答复。",
                            auditFallback,
                            false
                    );
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
            String auditFallback = buildToolAuditFallback(assembled, requestId, ex.getMessage());
            if (StringUtils.hasText(auditFallback)) {
                return new ChatExecutionResult(
                        assembled.observePrompt(),
                        "SIMPLIFIED: 工具已执行，但模型总结超时，已改用工具结果兜底。",
                        "行动已完成部分工具调用，远程模型在整理答案时失败。reason=" + safe(ex.getMessage()),
                        auditFallback,
                        false
                );
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
                                                    OparLoopEngine.FallbackResponder fallbackResponder) {
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
        try {
            return localSkillFallbackService.tryHandleControlPlane(question).orElse(null);
        } catch (Exception ex) {
            log.warn("简化模式控制面本地执行失败，reason={}", ex.getMessage());
            return null;
        }
    }

    private LocalSkillFallbackService.LocalSkillResult tryLocalFallbackResult(String question) {
        try {
            return localSkillFallbackService.tryHandleStructured(question).orElse(null);
        } catch (Exception ex) {
            log.warn("简化模式本地兜底失败，reason={}", ex.getMessage());
            return null;
        }
    }

    private LocalSkillFallbackService.LocalSkillResult tryPriorityStructuredLocalResult(String question) {
        try {
            return localSkillFallbackService.tryHandlePriorityStructured(question).orElse(null);
        } catch (Exception ex) {
            log.warn("简化模式优先结构化技能执行失败，reason={}", ex.getMessage());
            return null;
        }
    }

    private String renderUserPrompt(String question) {
        return """
                用户问题：%s

                直接回答用户问题。
                如果需要工具，你可以自行决定调用合适的工具；如果不需要工具，就直接给出最终答案。
                不要输出阶段名、计划过程、内部系统说明。
                """.formatted(safe(question));
    }

    private String buildActionTrace(ModelCallExecutor.ModelCallResult<String> result, Object[] tools) {
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

    private String buildToolAuditFallback(AssembledContext assembled, String requestId, String reason) {
        if (!StringUtils.hasText(requestId)) {
            return "";
        }
        List<ToolAuditEntry> entries = messageEventService.listSessionEvents(
                        assembled.sessionKey(),
                        "SYSTEM",
                        "TOOL",
                        24,
                        false
                ).stream()
                .filter(event -> requestId.equals(event.getRequestId()))
                .map(event -> parseToolAuditEntry(event.getContent()))
                .filter(entry -> entry != null)
                .sorted(Comparator.comparing(ToolAuditEntry::toolName))
                .toList();

        List<ToolAuditEntry> successEntries = entries.stream()
                .filter(entry -> "SUCCESS".equals(entry.status()))
                .filter(entry -> StringUtils.hasText(entry.detail()))
                .filter(entry -> !"invoke".equalsIgnoreCase(entry.detail()))
                .toList();
        if (successEntries.isEmpty()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("我已经拿到本地工具结果，先把确定信息整理如下：");
        for (ToolAuditEntry entry : successEntries.stream().limit(6).toList()) {
            builder.append("\n- ")
                    .append(friendlyToolName(entry.toolName()))
                    .append("：")
                    .append(renderFriendlyToolDetail(entry.detail()));
        }
        if (successEntries.size() > 6) {
            builder.append("\n- 还有 ").append(successEntries.size() - 6).append(" 条工具结果已收起。");
        }
        builder.append("\n如果你要继续深入，可以直接说“按后端模块展开”或“继续分析这些文件”。");
        return builder.toString();
    }

    private String friendlyToolName(String toolName) {
        String text = safe(toolName);
        if (text.contains("FileToolPack.listFiles")) {
            return "文件检索";
        }
        if (text.contains("FileToolPack.readFile")) {
            return "文件读取";
        }
        if (text.contains("LocalFilesystemToolPack")) {
            return "本地授权文件";
        }
        if (text.contains("WorkspaceSearchToolPack")) {
            return "工作区检索";
        }
        if (text.contains("ScriptSkillToolPack")) {
            return "脚本技能";
        }
        if (text.contains("WebSearchToolPack")) {
            return "网页检索";
        }
        if (text.contains("WeatherToolPack")) {
            return "天气查询";
        }
        return text.replace("ToolPack.", " ");
    }

    private String renderFriendlyToolDetail(String detail) {
        String text = safe(detail)
                .replace("[F]", "文件")
                .replace("[D]", "目录")
                .replaceAll("\\s+", " ")
                .trim();
        return truncateToolDetail(text, 320);
    }

    private ToolAuditEntry parseToolAuditEntry(String content) {
        String text = safe(content);
        if (!StringUtils.hasText(text) || !text.contains("tool=") || !text.contains("status=")) {
            return null;
        }
        String tool = extractField(text, "tool=");
        String status = extractField(text, "status=");
        String detail = extractTrailingField(text, "detail=");
        if (!StringUtils.hasText(tool) || !StringUtils.hasText(status)) {
            return null;
        }
        return new ToolAuditEntry(tool, status.toUpperCase(), detail);
    }

    private String extractField(String text, String marker) {
        int start = text.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int valueStart = start + marker.length();
        int end = text.indexOf(", ", valueStart);
        if (end < 0) {
            end = text.length();
        }
        return text.substring(valueStart, end).trim();
    }

    private String extractTrailingField(String text, String marker) {
        int start = text.indexOf(marker);
        if (start < 0) {
            return "";
        }
        return text.substring(start + marker.length()).replaceAll("\\s+", " ").trim();
    }

    private String truncateToolDetail(String detail, int maxLen) {
        String text = safe(detail).replace('\n', ' ').replace('\r', ' ').trim();
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private record ToolAuditEntry(String toolName, String status, String detail) {
    }
}
