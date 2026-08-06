package com.springclaw.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动时集中告警核心能力降级状态。
 *
 * embedding / db-persistence / chat-memory / memory-core 默认全部关闭——开箱即用时若不显式提示，
 * 用户会误以为"有记忆/RAG/持久化"，实际只有进程内最小运行模式（重启即失）。此组件在应用就绪后打一条
 * WARN，把"名实不符"变成显式可见。这是审计发现"默认配置名实不符"的最小非侵入修复（不改默认值，避免
 * 无 API Key 时启动失败）。
 */
@Component
public class CoreCapabilityStatusLogger {

    private static final Logger log = LoggerFactory.getLogger(CoreCapabilityStatusLogger.class);

    private final boolean embeddingEnabled;
    private final boolean dbEnabled;
    private final boolean chatMemoryEnabled;
    private final boolean memoryCoreEnabled;

    public CoreCapabilityStatusLogger(
            @Value("${springclaw.embedding.enabled:false}") boolean embeddingEnabled,
            @Value("${springclaw.persistence.db-enabled:false}") boolean dbEnabled,
            @Value("${springclaw.chat.spring-ai-chat-memory-enabled:false}") boolean chatMemoryEnabled,
            @Value("${springclaw.memory.core.enabled:false}") boolean memoryCoreEnabled) {
        this.embeddingEnabled = embeddingEnabled;
        this.dbEnabled = dbEnabled;
        this.chatMemoryEnabled = chatMemoryEnabled;
        this.memoryCoreEnabled = memoryCoreEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnDegradedCapabilities() {
        List<String> off = new ArrayList<>();
        if (!embeddingEnabled) {
            off.add("embedding(向量召回)");
        }
        if (!dbEnabled) {
            off.add("db-persistence(消息事件持久化)");
        }
        if (!chatMemoryEnabled) {
            off.add("chat-memory(Spring AI ChatMemory)");
        }
        if (!memoryCoreEnabled) {
            off.add("memory-core(核心记忆)");
        }
        if (off.isEmpty()) {
            log.info("核心能力均已启用: embedding / db / chat-memory / memory-core。");
            return;
        }
        log.warn("核心能力降级: {} → 记忆/持久化/向量召回不可用，仅最小运行模式。"
                        + "完整 Agent 能力需开启对应环境变量: "
                        + "SPRINGCLAW_EMBEDDING_ENABLED / SPRINGCLAW_PERSISTENCE_DB_ENABLED / "
                        + "SPRINGCLAW_CHAT_SPRING_AI_CHAT_MEMORY_ENABLED / SPRINGCLAW_MEMORY_CORE_ENABLED。",
                String.join(", ", off));
    }
}
