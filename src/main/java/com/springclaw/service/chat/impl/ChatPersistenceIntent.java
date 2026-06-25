package com.springclaw.service.chat.impl;

/**
 * Phase 3A1 Task 5：对话持久化意图。
 *
 * <ul>
 *   <li>{@link #TERMINAL_RESULT} —— 终端回复：写 user/assistant 消息、记忆 turn、
 *       chat:&lt;requestId&gt;:assistant:terminal 事件。</li>
 *   <li>{@link #CONFIRMATION_SUSPENSION} —— 确认挂起：只写 user 消息与
 *       chat:&lt;requestId&gt;:user / :suspension 事件，不写语义记忆、不写终端
 *       assistant 事件。</li>
 * </ul>
 */
public enum ChatPersistenceIntent {
    TERMINAL_RESULT,
    CONFIRMATION_SUSPENSION
}
