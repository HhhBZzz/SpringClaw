package com.openclaw.service.chat;

import org.springframework.util.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一处理会话事件中的兼容文本格式。
 */
public final class ConversationEventTextSupport {

    private static final Pattern OBSERVE_QUESTION_PATTERN = Pattern.compile(
            "\\[OBSERVE\\]\\s*# 当前问题\\s*(.*?)\\s*(?:# 短期会话上下文（事件流）|$)",
            Pattern.DOTALL
    );

    private ConversationEventTextSupport() {
    }

    public static String extractUserQuestion(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String trimmed = content.trim();
        Matcher matcher = OBSERVE_QUESTION_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return normalize(matcher.group(1));
        }
        return normalize(trimmed);
    }

    public static String extractAssistantAnswer(String content) {
        String normalized = normalize(content);
        if (normalized.startsWith("[REFLECT]")) {
            return normalize(normalized.substring("[REFLECT]".length()));
        }
        return normalized;
    }

    public static String normalize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }
}
