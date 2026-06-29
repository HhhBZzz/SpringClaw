package com.springclaw.service.chat.impl;

import com.springclaw.common.util.TextUtils;
import com.springclaw.runtime.contract.ContextSnapshot;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders canonical typed context for model prompts.
 *
 * <p>When a {@link ContextSnapshot} is attached to {@link ChatContext}, prompt
 * rendering should use the typed snapshot directly. Legacy {@code ContextInjection}
 * remains the rollback fallback until all engines are migrated.
 */
final class TypedContextPromptRenderer {

    private TypedContextPromptRenderer() {
    }

    static String promptPrefix(ChatContext ctx) {
        if (ctx == null) {
            return "";
        }
        ContextSnapshot snapshot = ctx.contextSnapshot();
        if (snapshot == null) {
            return ctx.contextInjection().renderForPrompt();
        }
        String rendered = renderSnapshot(snapshot);
        return rendered.isBlank() ? "" : rendered + "\n\n";
    }

    static String question(ChatContext ctx) {
        if (ctx == null) {
            return "";
        }
        ContextSnapshot snapshot = ctx.contextSnapshot();
        if (snapshot != null) {
            return TextUtils.safe(snapshot.effectiveMessage());
        }
        return TextUtils.safe(ctx.assembled() == null ? null : ctx.assembled().question());
    }

    private static String renderSnapshot(ContextSnapshot snapshot) {
        return """
                # 当前问题
                %s

                # 项目记忆（Memory Bank）
                %s

                # 短期会话上下文（事件流）
                %s

                # 长期语义记忆（同会话优先）
                %s

                # 程序化记忆（规则/经验）
                %s
                """.formatted(
                TextUtils.safe(snapshot.effectiveMessage()),
                TextUtils.safe(snapshot.memoryBankText()),
                join(snapshot.shortTermEvents()),
                join(snapshot.semanticRecallItems()),
                join(snapshot.activeLearningRules())
        );
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining("\n"));
    }
}
