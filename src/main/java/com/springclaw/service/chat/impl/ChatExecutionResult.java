package com.springclaw.service.chat.impl;

/**
 * 统一描述一次聊天执行结果。
 */
public record ChatExecutionResult(
        String observe,
        String plan,
        String action,
        String reflect,
        boolean modelEnabled
) {
}
