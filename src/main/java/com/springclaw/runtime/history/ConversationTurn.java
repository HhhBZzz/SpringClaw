package com.springclaw.runtime.history;

import java.time.Instant;

/**
 * 派生出的一个 LLM 对话历史条目(spec 2026-08-17-canonical-conversation-history §3.2,
 * 2026-08-18-message-event-chat-write-closure §3.2 归属富化)。
 *
 * @param role    USER / ASSISTANT(SYSTEM 诊断摘要预留给后续 payload 富化)
 * @param content 展示文本;经 {@link #canonical} 工厂截断至 220(同 legacy renderEventLine),
 *                经 {@link #canonicalUntruncated} 工厂保留原文(展示/精确查询用)
 * @param runId   来源 run(canonical 派生行的溯源)
 * @param channel 归属渠道(来自 RunState,供 scope 过滤)
 * @param userId  归属用户(来自 RunState,供 PERSONAL_SESSION 鉴权/越权校验)
 * @param at      事件时间戳(双源拼接时的时间线排序键)
 * @param source  CANONICAL(事件日志派生)/ LEGACY(message_event 补齐,双源拼接过渡)
 */
public record ConversationTurn(
        Role role,
        String content,
        String runId,
        String channel,
        String userId,
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

    /** LLM 上下文用:截断至 {@link #RENDER_LIMIT}。 */
    public static ConversationTurn canonical(
            Role role, String content, String runId, String channel, String userId, Instant at) {
        return new ConversationTurn(
                role, truncate(content), runId, channel, userId, at, Source.CANONICAL);
    }

    /** 展示/精确查询用(spec 2026-08-18 §3.3 deriveFull):保留原文不截断。 */
    public static ConversationTurn canonicalUntruncated(
            Role role, String content, String runId, String channel, String userId, Instant at) {
        return new ConversationTurn(
                role, content == null ? "" : content, runId, channel, userId, at, Source.CANONICAL);
    }

    public static String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > RENDER_LIMIT ? content.substring(0, RENDER_LIMIT) : content;
    }
}
