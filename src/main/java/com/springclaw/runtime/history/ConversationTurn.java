package com.springclaw.runtime.history;

import java.time.Instant;

/**
 * 派生出的一个 LLM 对话历史条目(spec 2026-08-17-canonical-conversation-history §3.2)。
 *
 * @param role    USER / ASSISTANT(SYSTEM 诊断摘要预留给 T2 后续)
 * @param content 已按 legacy renderEventLine 截断规则(220 字符)截断的展示文本
 * @param runId   来源 run(canonical 派生行的溯源)
 * @param at      事件时间戳(双源拼接时的时间线排序键)
 * @param source  CANONICAL(事件日志派生)/ LEGACY(message_event 补齐,双源拼接过渡)
 */
public record ConversationTurn(
        Role role,
        String content,
        String runId,
        Instant at,
        Source source
) {

    public enum Role {
        USER, ASSISTANT, SYSTEM
    }

    public enum Source {
        CANONICAL, LEGACY
    }

    /** legacy 渲染行截断上限(与 ContextAssembler.renderEventLine 一致)。 */
    public static final int RENDER_LIMIT = 220;

    public static ConversationTurn canonical(Role role, String content, String runId, Instant at) {
        return new ConversationTurn(role, truncate(content), runId, at, Source.CANONICAL);
    }

    public static String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > RENDER_LIMIT ? content.substring(0, RENDER_LIMIT) : content;
    }
}
