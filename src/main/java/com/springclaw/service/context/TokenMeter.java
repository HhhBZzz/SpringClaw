package com.springclaw.service.context;

/**
 * 请求前 token 估算器(dsh ctx.tokenMeter 的 SpringClaw 对应物)。
 *
 * <p>用途:工具结果剪枝/上下文预算的计量事实源。事后记账
 * (LlmUsageRecord 的 promptTokens)只在响应后可用,无法支撑
 * "超预算前行动"的决策。</p>
 *
 * <p>估算策略:保守近似——CJK 字符按 1:1 计,其余按 4 字符≈1 token 计
 * (BPE 对英文代码/文本的平均压缩比;中文 token 化普遍 1 字≥1 token)。
 * 只需相对正确(预算比较),无需绝对精确。</p>
 */
public class TokenMeter {

    /** 估算文本 token 数(CJK 1:1,其余 4:1)。 */
    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (other + 3) / 4;
    }

    /** 文本是否在预算内。 */
    public boolean withinBudget(String text, int budgetTokens) {
        return estimate(text) <= budgetTokens;
    }

    /** 文本占预算的比例(0..1+);预算非正或文本空返回 0。 */
    public double pressure(String text, int budgetTokens) {
        if (budgetTokens <= 0) {
            return 0;
        }
        return (double) estimate(text) / budgetTokens;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS;
    }
}
