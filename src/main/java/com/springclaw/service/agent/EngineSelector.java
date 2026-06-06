package com.springclaw.service.agent;

import com.springclaw.service.chat.impl.ChatContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 引擎选择器：注入所有 AgentEngine 实现，按优先级选择第一个匹配的引擎。
 * 替代原先 ChatServiceImpl 中分散的 shouldUseXxx() 硬编码判断。
 */
@Component
public class EngineSelector {

    private static final Logger log = LoggerFactory.getLogger(EngineSelector.class);

    private final List<AgentEngine> engines;

    public EngineSelector(List<AgentEngine> engines) {
        this.engines = engines == null
                ? List.of()
                : engines.stream()
                .sorted(Comparator.comparingInt(AgentEngine::priority))
                .toList();
        log.info("EngineSelector 已初始化 {} 个引擎: {}",
                this.engines.size(),
                this.engines.stream().map(e -> e.name() + "(p=" + e.priority() + ")")
                        .reduce((a, b) -> a + ", " + b).orElse("无"));
    }

    /**
     * 选择第一个支持当前上下文的引擎。
     *
     * @param ctx 聊天上下文
     * @return 匹配的引擎
     * @throws IllegalStateException 如果没有引擎支持该上下文
     */
    public AgentEngine select(ChatContext ctx) {
        for (AgentEngine engine : engines) {
            if (engine.supports(ctx)) {
                log.debug("引擎选择: {} (priority={}) for requestId={}", engine.name(), engine.priority(), ctx.requestId());
                return engine;
            }
        }
        throw new IllegalStateException("没有可用的 Agent 引擎支持当前请求。requestId="
                + (ctx == null ? "null" : ctx.requestId()));
    }

    /** 列出所有已注册的引擎信息（用于调试和管理面板） */
    public List<AgentEngine> listAll() {
        return engines;
    }
}
