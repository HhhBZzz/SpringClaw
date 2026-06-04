package com.springclaw.service.context;

/**
 * 上下文组装结果。
 */
public record AssembledContext(
        String sessionKey,
        String channel,
        String userId,
        String question,
        String eventContext,
        String semanticContext,
        String observePrompt
) {

    public AssembledContext withQuestion(String nextQuestion) {
        String updatedQuestion = hasText(nextQuestion) ? nextQuestion.trim() : question;
        return new AssembledContext(
                sessionKey,
                channel,
                userId,
                updatedQuestion,
                eventContext,
                semanticContext,
                renderObservePrompt(updatedQuestion, eventContext, semanticContext)
        );
    }

    private static String renderObservePrompt(String question, String eventContext, String semanticContext) {
        return """
                # 当前问题
                %s

                # 短期会话上下文（事件流）
                %s

                # 长期语义记忆（同会话优先）
                %s
                """.formatted(
                safe(question),
                safe(eventContext),
                safe(semanticContext)
        );
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
