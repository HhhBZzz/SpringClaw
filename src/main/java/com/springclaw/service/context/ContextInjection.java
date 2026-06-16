package com.springclaw.service.context;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 干路上下文注入：所有引擎共享同一份注入入口。
 * <p>
 * P0 阶段只用 observePrompt（来自 ContextAssembler 现有产出）。
 * policyPrompt / pendingProposalPrompt 是 P1 占位字段，先空字符串；
 * 这样 P1 接 PolicyService / Proposal 提示时不用再改引擎。
 * <p>
 * metadata 当前只承载 contextSummary（来自 AssembledContext.sourceSummary），
 * 由 ChatContextFactory 写入，P1 阶段会被 PolicyService / 监控指标读取。
 * P0 暂未读取，但保留以减少 P1 时的 ChatContextFactory 改动。
 */
public record ContextInjection(
        String observePrompt,
        String policyPrompt,
        String pendingProposalPrompt,
        Map<String, Object> metadata
) {
    public ContextInjection {
        observePrompt = observePrompt == null ? "" : observePrompt;
        policyPrompt = policyPrompt == null ? "" : policyPrompt;
        pendingProposalPrompt = pendingProposalPrompt == null ? "" : pendingProposalPrompt;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public static ContextInjection empty() {
        return new ContextInjection("", "", "", Map.of());
    }

    /**
     * 渲染为 prompt 前缀。空注入返回 ""，非空注入返回 "{joined}\n\n"，
     * 由本方法独占管理与后续模板之间的分隔符 — 调用方模板首行紧跟注入即可，
     * 不要再在模板里写 "%s\n\n..." 或 ".stripLeading()"。
     */
    public String renderForPrompt() {
        String joined = Stream.of(observePrompt, policyPrompt, pendingProposalPrompt)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n\n"));
        return joined.isEmpty() ? "" : joined + "\n\n";
    }
}
