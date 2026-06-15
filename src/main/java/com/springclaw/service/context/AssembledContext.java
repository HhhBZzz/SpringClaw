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
        String observePrompt,
        int memoryLearningActiveCount,
        int memoryLearningFilteredCount
) {

    private static final String PROJECT_MEMORY_HEADING = "# 项目记忆（Memory Bank）";
    private static final String EVENT_CONTEXT_HEADING = "# 短期会话上下文（事件流）";
    private static final String CONTEXT_SOURCE_SCHEMA = "springclaw.context-source.v1";

    public AssembledContext(String sessionKey,
                            String channel,
                            String userId,
                            String question,
                            String eventContext,
                            String semanticContext,
                            String observePrompt) {
        this(sessionKey, channel, userId, question, eventContext, semanticContext, observePrompt, 0, 0);
    }

    public ContextSourceSummary sourceSummary() {
        String projectMemoryContext = extractProjectMemoryContext(observePrompt);
        return new ContextSourceSummary(
                CONTEXT_SOURCE_SCHEMA,
                hasText(projectMemoryContext),
                safe(projectMemoryContext).length(),
                safe(eventContext).length(),
                safe(semanticContext).length(),
                safe(observePrompt).length(),
                memoryLearningActiveCount,
                memoryLearningFilteredCount
        );
    }

    public AssembledContext withQuestion(String nextQuestion) {
        String updatedQuestion = hasText(nextQuestion) ? nextQuestion.trim() : question;
        String projectMemoryContext = extractProjectMemoryContext(observePrompt);
        return new AssembledContext(
                sessionKey,
                channel,
                userId,
                updatedQuestion,
                eventContext,
                semanticContext,
                renderObservePrompt(updatedQuestion, projectMemoryContext, eventContext, semanticContext),
                memoryLearningActiveCount,
                memoryLearningFilteredCount
        );
    }

    private static String renderObservePrompt(String question,
                                              String projectMemoryContext,
                                              String eventContext,
                                              String semanticContext) {
        return """
                # 当前问题
                %s

                # 项目记忆（Memory Bank）
                %s

                # 短期会话上下文（事件流）
                %s

                # 长期语义记忆（同会话优先）
                %s
                """.formatted(
                safe(question),
                safe(projectMemoryContext),
                safe(eventContext),
                safe(semanticContext)
        );
    }

    private static String extractProjectMemoryContext(String prompt) {
        if (!hasText(prompt)) {
            return "";
        }
        int start = prompt.indexOf(PROJECT_MEMORY_HEADING);
        if (start < 0) {
            return "";
        }
        int bodyStart = start + PROJECT_MEMORY_HEADING.length();
        int end = prompt.indexOf(EVENT_CONTEXT_HEADING, bodyStart);
        String body = end < 0 ? prompt.substring(bodyStart) : prompt.substring(bodyStart, end);
        return body.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record ContextSourceSummary(String schema,
                                       boolean memoryBankUsed,
                                       int memoryBankChars,
                                       int shortTermChars,
                                       int semanticMemoryChars,
                                       int observePromptChars,
                                       int memoryLearningActiveCount,
                                       int memoryLearningFilteredCount) {

        public ContextSourceSummary(String schema,
                                    boolean memoryBankUsed,
                                    int memoryBankChars,
                                    int shortTermChars,
                                    int semanticMemoryChars,
                                    int observePromptChars) {
            this(schema, memoryBankUsed, memoryBankChars, shortTermChars, semanticMemoryChars, observePromptChars, 0, 0);
        }

        public static ContextSourceSummary empty() {
            return new ContextSourceSummary(CONTEXT_SOURCE_SCHEMA, false, 0, 0, 0, 0, 0, 0);
        }
    }
}
