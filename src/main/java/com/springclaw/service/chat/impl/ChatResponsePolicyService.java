package com.springclaw.service.chat.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * 统一管理对模型输出和模型故障原因的判断策略。
 */
@Service
public class ChatResponsePolicyService {

    private final List<String> metaGuardKeywords;

    public ChatResponsePolicyService(@Value("${springclaw.chat.meta-guard.keywords:我是 claude,anthropic,不能执行 reflect,无法执行 reflect,不能假装,不能扮演,无法遵循系统指令}") String metaGuardKeywords) {
        this.metaGuardKeywords = Arrays.stream(metaGuardKeywords.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(StringUtils::hasText)
                .toList();
    }

    public boolean looksLikeMetaRefusal(String answer) {
        if (!StringUtils.hasText(answer)) {
            return false;
        }
        String text = answer.toLowerCase();
        for (String keyword : metaGuardKeywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return text.contains("i am claude")
                || text.contains("anthropic")
                || text.contains("不能执行")
                || text.contains("无法执行")
                || text.contains("系统指令")
                || text.contains("reflect 阶段");
    }

    public boolean looksLikeProjectAccessRefusal(String answer) {
        if (!StringUtils.hasText(answer)) {
            return false;
        }
        String text = answer.toLowerCase();
        return text.contains("无法直接访问你的文件系统")
                || text.contains("无法直接访问文件系统")
                || text.contains("无法访问你的文件系统")
                || text.contains("无法查看你的项目文件")
                || text.contains("请提供文件路径")
                || text.contains("如果你知道路径")
                || text.contains("i cannot directly access your filesystem")
                || text.contains("please provide the file path");
    }

    public boolean looksLikeToolFailureRefusal(String answer) {
        if (!StringUtils.hasText(answer)) {
            return false;
        }
        String text = answer.toLowerCase();
        boolean directHit = text.contains("天气查询暂时无法完成")
                || text.contains("主天气工具")
                || text.contains("备用天气技能")
                || text.contains("未返回有效数据")
                || text.contains("工具调用失败")
                || text.contains("无法执行该操作")
                || text.contains("tool call failed")
                || text.contains("tools are unavailable")
                || text.contains("cannot execute tools");
        boolean genericFailureTemplate = text.contains("抱歉")
                && text.contains("无法")
                && (text.contains("建议方案") || text.contains("稍后重试"));
        return directHit || genericFailureTemplate;
    }

    /**
     * 检测模型输出中是否包含幻觉的 XML/MCP 格式工具调用标签。
     * 当 DeepSeek V4 Pro 原生 function calling 被禁用时，模型有时会在回答文本中
     * 嵌入 XML 格式的工具调用标签（如 &lt;use_mcp_tool&gt;、&lt;invoke&gt;、&lt;function_call&gt;），
     * 这些标签不会被后端拦截执行，会原样泄漏给用户。
     */
    public boolean looksLikeHallucinatedXmlToolCall(String answer) {
        if (!StringUtils.hasText(answer)) {
            return false;
        }
        String lower = answer.toLowerCase();
        // MCP/Anthropic-style tool call markers
        if (lower.contains("<use_mcp_tool>")
                || lower.contains("<invoke name=")
                || lower.contains("<function_call>")
                || lower.contains("<tool_call>")
                || lower.contains("<tool_calls>")
                || lower.contains("<parameter name=")
                || lower.contains("<invoke>")) {
            return true;
        }
        // Claude Code XML tool-call block patterns
        if (lower.contains("</invoke>")
                || lower.contains("</function_call>")
                || lower.contains("</tool_call>")
                || lower.contains("</use_mcp_tool>")) {
            return true;
        }
        return false;
    }

    /**
     * 去除回答中幻觉的 XML 工具调用标签块，保留正常文本。
     * 当检测到 XML 工具调用泄漏时，截取第一个 XML 标签之前的内容作为安全回答。
     */
    public String stripHallucinatedXmlBlocks(String answer) {
        if (!StringUtils.hasText(answer)) {
            return answer;
        }
        // 尝试去除完整的 XML 工具调用块
        String cleaned = answer
                .replaceAll("(?s)<use_mcp_tool>.*?</use_mcp_tool>", "")
                .replaceAll("(?s)<function_call>.*?</function_call>", "")
                .replaceAll("(?s)<invoke[^>]*>.*?</invoke>", "")
                .replaceAll("(?s)<tool_call>.*?</tool_call>", "")
                .trim();
        // 如果清洗后变空了，截取第一个 XML 标签之前的内容
        if (!StringUtils.hasText(cleaned)) {
            int firstXmlTag = findFirstXmlTagIndex(answer);
            if (firstXmlTag > 0) {
                cleaned = answer.substring(0, firstXmlTag).trim();
            }
        }
        return StringUtils.hasText(cleaned) ? cleaned : answer;
    }

    private int findFirstXmlTagIndex(String text) {
        int idx = Integer.MAX_VALUE;
        String[] tags = {"<use_mcp_tool>", "<function_call>", "<invoke", "<tool_call>", "<tool_calls>"};
        for (String tag : tags) {
            int i = text.indexOf(tag);
            if (i >= 0 && i < idx) {
                idx = i;
            }
        }
        return idx == Integer.MAX_VALUE ? -1 : idx;
    }

    public String buildFallbackAdvice(String reason) {
        String lowerReason = safe(reason).toLowerCase();
        if (lowerReason.contains("已禁用")
                || lowerReason.contains("未配置有效 api key")
                || lowerReason.contains("未配置 base-url")
                || lowerReason.contains("未配置模型名")
                || lowerReason.contains("可用模型提供方")) {
            return "先补齐当前 provider 的 base-url、model、API Key，或切回可用 provider。";
        }
        if (lowerReason.contains("api key")) {
            return "先为当前 provider 配置有效 API Key。";
        }
        if (lowerReason.contains("/v1/v1/") || lowerReason.contains("application/octet-stream")) {
            return "当前 provider 的 OpenAI-compatible base-url 与 Spring AI 默认路径重复拼接了，需校正请求路径。";
        }
        if (lowerReason.contains("base-url")) {
            return "先为当前 provider 配置正确的 OpenAI-compatible base-url。";
        }
        if (lowerReason.contains("模型名")) {
            return "先为当前 provider 配置正确的模型名。";
        }
        if (lowerReason.contains("503")
                || lowerReason.contains("502")
                || lowerReason.contains("504")
                || lowerReason.contains("429")
                || lowerReason.contains("no available account")
                || lowerReason.contains("handshake")
                || lowerReason.contains("ssl")
                || lowerReason.contains("tls")
                || lowerReason.contains("i/o error")
                || lowerReason.contains("remote host terminated")) {
            return "这是上游模型服务故障，不是飞书、数据库或项目启动故障。可稍后重试，或切换到更稳定的模型供应方。";
        }
        return "为当前模型提供方配置正确的 API Key 与模型名后可启用真实 OPAR 链路。";
    }

    public String buildUserFacingFailureReply(String reason, String question) {
        String simplifiedReason = simplifyFailureReason(reason);
        String lowerQuestion = safe(question).trim().toLowerCase();

        if (looksLikeGreetingOrPing(lowerQuestion)) {
            return "在。我这边服务还在，但上游模型这会儿有问题：" + simplifiedReason + " 你可以稍后再试，或者先切换模型。";
        }
        if (looksLikeStatusFollowUp(lowerQuestion)) {
            return "本地服务还正常，但上游模型现在还没恢复：" + simplifiedReason;
        }
        if (looksLikeConfigurationIssue(reason)) {
            return "这次没能正常回答，因为当前模型配置还没准备好：" + simplifiedReason + " 先补齐 key、base-url 或模型名，或者切到可用模型。";
        }
        if (looksLikeUpstreamIssue(reason)) {
            return "这次没能正常回答，因为" + simplifiedReason + " 你可以稍后再试，或者先切到别的模型。";
        }
        return "这次没能正常回答，因为" + simplifiedReason;
    }

    public String simplifyFailureReason(String reason) {
        String lowerReason = safe(reason).toLowerCase();
        if (lowerReason.contains("no available account") || lowerReason.contains("channel 'kiro'")) {
            return "上游模型服务返回 503，当前账号池不可用。";
        }
        if (lowerReason.contains("503")) {
            return "上游模型服务返回 503，当前暂时不可用。";
        }
        if (lowerReason.contains("502")) {
            return "上游模型服务返回 502，网关暂时不可用。";
        }
        if (lowerReason.contains("504")) {
            return "上游模型服务返回 504，请求超时。";
        }
        if (lowerReason.contains("read timed out") || lowerReason.contains("sockettimeoutexception")) {
            return "上游模型响应超时，已超过本地等待时间。";
        }
        if (lowerReason.contains("429")) {
            return "上游模型服务限流，当前请求被拒绝。";
        }
        if (lowerReason.contains("handshake") || lowerReason.contains("ssl") || lowerReason.contains("tls")) {
            return "上游模型服务 TLS/网络握手异常。";
        }
        if (looksLikeConnectionClosedEarly(lowerReason)) {
            return "上游模型服务连接中断。";
        }
        if (lowerReason.contains("/v1/v1/")) {
            return "当前 provider 的 OpenAI-compatible base-url 路径重复，实际请求成了 /v1/v1/...。";
        }
        if (!StringUtils.hasText(reason)) {
            return "模型服务调用失败。";
        }
        return reason;
    }

    public String buildPartialAnswerFromAction(String action, String reason) {
        String cleaned = sanitizeActionTrace(action);
        if (!StringUtils.hasText(cleaned)) {
            return "";
        }
        String lower = cleaned.toLowerCase();
        if (lower.contains("行动降级")
                || lower.contains("跳过行动阶段")
                || lower.contains("计划阶段失败")
                || lower.contains("未进入工具执行")) {
            return "";
        }
        if (cleaned.length() < 40) {
            return "";
        }
        return "模型总结阶段超时，但我已经拿到部分结果：\n" + cleaned;
    }

    private boolean looksLikeGreetingOrPing(String lowerQuestion) {
        return lowerQuestion.equals("在么")
                || lowerQuestion.equals("在吗")
                || lowerQuestion.equals("在不在")
                || lowerQuestion.equals("在？")
                || lowerQuestion.equals("在")
                || lowerQuestion.equals("hello")
                || lowerQuestion.equals("hi");
    }

    private boolean looksLikeStatusFollowUp(String lowerQuestion) {
        return lowerQuestion.equals("现在呢")
                || lowerQuestion.equals("现在呢？")
                || lowerQuestion.equals("恢复了吗")
                || lowerQuestion.equals("恢复了么")
                || lowerQuestion.equals("还不行吗")
                || lowerQuestion.equals("现在可以了吗")
                || lowerQuestion.equals("现在怎么样");
    }

    private boolean looksLikeConfigurationIssue(String reason) {
        String lowerReason = safe(reason).toLowerCase();
        return lowerReason.contains("已禁用")
                || lowerReason.contains("未配置有效 api key")
                || lowerReason.contains("未配置 base-url")
                || lowerReason.contains("未配置模型名")
                || lowerReason.contains("可用模型提供方")
                || lowerReason.contains("api key")
                || lowerReason.contains("base-url")
                || lowerReason.contains("模型名");
    }

    private boolean looksLikeUpstreamIssue(String reason) {
        String lowerReason = safe(reason).toLowerCase();
        return lowerReason.contains("503")
                || lowerReason.contains("502")
                || lowerReason.contains("504")
                || lowerReason.contains("429")
                || lowerReason.contains("no available account")
                || lowerReason.contains("handshake")
                || lowerReason.contains("ssl")
                || lowerReason.contains("tls")
                || lowerReason.contains("i/o error")
                || lowerReason.contains("remote host terminated")
                || looksLikeConnectionClosedEarly(lowerReason)
                || lowerReason.contains("timeout")
                || lowerReason.contains("请求超时");
    }

    private boolean looksLikeConnectionClosedEarly(String lowerReason) {
        return lowerReason.contains("eof reached while reading")
                || lowerReason.contains("premature eof")
                || lowerReason.contains("premature end")
                || lowerReason.contains("premature close")
                || lowerReason.contains("prematurely closed")
                || lowerReason.contains("unexpected end of file");
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    private String sanitizeActionTrace(String action) {
        if (!StringUtils.hasText(action)) {
            return "";
        }
        String cleaned = action
                .replaceAll("(?m)^\\[STEP\\s+\\d+\\](?:\\s+DEGRADED)?\\s*$", "")
                .replaceAll("(?m)^ACT=", "")
                .replaceAll("(?m)^PLAN=", "")
                .replaceAll("(?m)^\\s*$[\r\n]+", "\n")
                .trim();
        return cleaned;
    }
}
