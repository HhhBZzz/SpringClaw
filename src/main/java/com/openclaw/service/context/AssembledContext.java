package com.openclaw.service.context;

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
}
