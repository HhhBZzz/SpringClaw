package com.springclaw.service.chat.impl;

import com.springclaw.service.context.ledger.RunMessageLedger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 原生账本 prompt 入口(spec 2026-11-09 §3.3):引擎模型调用的唯一改面。
 *
 * <p>native 路径: system(SOUL) + messages(账本快照)——历史/工具轮/briefing
 * 全部以原生消息进入请求,前缀跨步稳定。
 * legacy 路径(开关关闭或账本缺席): system + user(引擎原有渲染文本),
 * 各引擎的渲染函数原样保留(回退路径与既有测试锚点)。</p>
 */
@Component
public class LedgerPromptSupport {

    private final boolean nativeContextEnabled;

    public LedgerPromptSupport(
            @Value("${springclaw.runtime.native-context-enabled:true}") boolean nativeContextEnabled) {
        this.nativeContextEnabled = nativeContextEnabled;
    }

    /** native 路径当前是否可用(引擎据此决定是否走账本初始化/步间追加)。 */
    public boolean nativeAvailable(ChatContext ctx) {
        return nativeContextEnabled && ctx != null && ctx.messageLedger() != null;
    }

    /**
     * run 初始化:把 briefing(范式指令)与当前问题合并为账本首条任务消息。
     * 账本在 ChatContextFactory 已完成种子(历史+记忆块),此处只追加任务;
     * 幂等——stream 失败 fallback 重入 execute 时不会重复追加。
     * legacy 路径 no-op。
     */
    public void initRun(ChatContext ctx, String briefing) {
        if (!nativeAvailable(ctx)) {
            return;
        }
        RunMessageLedger ledger = ctx.messageLedger();
        if (!ledger.markRunInitialized()) {
            return;
        }
        String question = TypedContextPromptRenderer.question(ctx);
        String combined = StringUtils.hasText(briefing)
                ? briefing.strip() + "\n\n# 任务\n" + (question == null ? "" : question.strip())
                : (question == null ? "" : question.strip());
        ledger.appendUser(combined);
    }

    /**
     * 应用 prompt:native → messages(账本);legacy → user(引擎渲染文本)。
     * systemPrompt 由引擎传入(legacy 重载路径的 system 可能来自调用方而非 ctx);
     * 空白时回退 ctx.systemPrompt()。引擎每步调用;步间演化通过账本 append 表达,
     * legacyPrompt 仅回退时使用。
     */
    public ChatClient.ChatClientRequestSpec applyPrompt(
            ChatClient.ChatClientRequestSpec spec,
            String systemPrompt,
            ChatContext ctx,
            String legacyPrompt) {
        String system = StringUtils.hasText(systemPrompt) ? systemPrompt : systemOf(ctx);
        RunMessageLedger ledger = ctx == null ? null : ctx.messageLedger();
        if (nativeContextEnabled && ledger != null && ledger.size() > 0) {
            return spec.system(system).messages(ledger.view());
        }
        return spec.system(system).user(legacyPrompt == null ? "" : legacyPrompt);
    }

    private static String systemOf(ChatContext ctx) {
        return ctx == null || ctx.systemPrompt() == null ? "" : ctx.systemPrompt();
    }
}
