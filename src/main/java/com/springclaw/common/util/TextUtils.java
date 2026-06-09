package com.springclaw.common.util;

import java.util.Locale;

/**
 * 全局文本工具类，消除各 service 中 safe/normalize/truncate/containsAny 的重复定义。
 */
public final class TextUtils {

    private TextUtils() {}

    /**
     * null 安全的字符串，null 返回空串。
     */
    public static String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * null 安全的字符串，支持默认值。
     */
    public static String safe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /**
     * 归一化：trim + lowercase（Locale.ROOT），null 返回空串。
     */
    public static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 截断：先 trim，超过 limit 后截断并附加 TRUNCATED 标记。
     */
    public static String truncate(String text, int limit) {
        String value = text == null ? "" : text.trim();
        return value.length() <= limit ? value : value.substring(0, limit) + "\n...<TRUNCATED>";
    }

    /**
     * 空白归一化：null→空串，连续空白压缩为单空格。
     * 适用于 prompt 拼接等需要规整空白的场景。
     */
    public static String normalizeWS(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ");
    }

    /**
     * 判断 text 是否包含 keywords 中的任意一个（大小写不敏感）。
     */
    public static boolean containsAny(String text, String... keywords) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
