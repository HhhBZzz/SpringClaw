package com.springclaw.runtime.memory.contract;

public enum MemoryFrameSourceKind {
    MESSAGE_EVENT,
    /** canonical run 事件日志派生(ConversationHistoryDeriver)——写侧收口后的短期层 durable 源 */
    CANONICAL_RUN_EVENT,
    MEMORY_RECORD,
    PROJECT_MARKDOWN,
    AGENT_LEARNING,
    VECTOR_CANDIDATE
}
