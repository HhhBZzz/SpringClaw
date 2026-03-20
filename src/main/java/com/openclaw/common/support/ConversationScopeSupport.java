package com.openclaw.common.support;

import org.springframework.util.StringUtils;

/**
 * 会话作用域辅助工具。
 *
 * 设计说明：
 * 1. 将渠道侧的会话类型（例如飞书 p2p/group）收敛成统一判断，避免各处散落字符串判断。
 * 2. 群聊保留群共享上下文，但切断跨会话个人语义记忆，避免把私聊记忆污染到群里。
 */
public final class ConversationScopeSupport {

    private static final String FEISHU_PREFIX = "feishu:";
    private static final String FEISHU_P2P_PREFIX = "feishu:p2p:";
    private static final String FEISHU_GROUP_PREFIX = "feishu:group:";

    private ConversationScopeSupport() {
    }

    public static String buildFeishuSessionKey(String chatType, String chatId) {
        if (!StringUtils.hasText(chatId)) {
            return "";
        }
        String safeChatId = chatId.trim();
        String normalizedChatType = normalizeChatType(chatType);
        if ("p2p".equals(normalizedChatType)) {
            return FEISHU_P2P_PREFIX + safeChatId;
        }
        if ("group".equals(normalizedChatType)) {
            return FEISHU_GROUP_PREFIX + safeChatId;
        }
        return FEISHU_PREFIX + safeChatId;
    }

    public static boolean isFeishuGroupSession(String sessionKey) {
        return StringUtils.hasText(sessionKey) && sessionKey.trim().startsWith(FEISHU_GROUP_PREFIX);
    }

    public static String resolveFeishuChatId(String sessionKey) {
        if (!StringUtils.hasText(sessionKey)) {
            return "";
        }
        String normalized = sessionKey.trim();
        if (normalized.startsWith(FEISHU_GROUP_PREFIX)) {
            return normalized.substring(FEISHU_GROUP_PREFIX.length()).trim();
        }
        if (normalized.startsWith(FEISHU_P2P_PREFIX)) {
            return normalized.substring(FEISHU_P2P_PREFIX.length()).trim();
        }
        if (normalized.startsWith(FEISHU_PREFIX)) {
            return normalized.substring(FEISHU_PREFIX.length()).trim();
        }
        return "";
    }

    public static boolean shouldUseCrossSessionUserMemory(String channel, String sessionKey) {
        if (!"feishu".equalsIgnoreCase(safe(channel))) {
            return true;
        }
        return !isFeishuGroupSession(sessionKey);
    }

    public static boolean shouldUseCrossSessionUserMemory(String sessionKey) {
        return !isFeishuGroupSession(sessionKey);
    }

    public static String renderSpeakerRole(String channel, String sessionKey, String role, String userId) {
        String safeRole = safe(role).toUpperCase();
        if (!"USER".equals(safeRole)) {
            return safeRole;
        }
        if (!"feishu".equalsIgnoreCase(safe(channel)) || !isFeishuGroupSession(sessionKey) || !StringUtils.hasText(userId)) {
            return safeRole;
        }
        return safeRole + "(" + userId.trim() + ")";
    }

    private static String normalizeChatType(String chatType) {
        String normalized = safe(chatType).toLowerCase();
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        return "p2p".equals(normalized) ? "p2p" : "group";
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
