package com.springclaw.service.agent;

import com.springclaw.service.chat.impl.ChatContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 引擎选择器：注入所有 AgentEngine 实现，按 (priority, legacyRank) 选择第一个匹配的引擎。
 * 替代原先 ChatServiceImpl 中分散的 shouldUseXxx() 硬编码判断。
 *
 * <p>Phase 2B Task 4A：在 priority 之外引入稳定的 legacyRank 次级排序键，消除
 * {@code AutonomousLoopEngine} 与 {@code AgentRuntimeEngine} 同 priority=2 时
 * 依赖 Spring bean 注入顺序的不确定性。legacyRank 按 {@link AgentEngine#name()}
 * 查表；任何未登记 name() 的引擎在初始化阶段即失败，避免运行期静默乱序。
 * 本任务不改任何引擎的 {@code priority()} 或 {@code supports()}。
 */
@Component
public class EngineSelector {

    private static final Logger log = LoggerFactory.getLogger(EngineSelector.class);

    /**
     * 引擎 name() → legacyRank 的固定映射。数字越小越优先（与 priority 同向）。
     * 新增引擎必须在此登记，否则初始化失败。
     */
    private static final Map<String, Integer> LEGACY_RANK = Map.of(
            "basic-stream", 10,
            "agent-runtime", 20,
            "autonomous-loop", 30,
            "opar-loop", 40,
            "model-led-stream", 50,
            "simplified", 60
    );

    private final List<AgentEngine> engines;

    public EngineSelector(List<AgentEngine> engines) {
        if (engines == null || engines.isEmpty()) {
            this.engines = List.of();
        } else {
            // Eagerly validate every engine has a registered legacyRank so a missing
            // name fails initialization (Phase 2B Task 4A requirement) rather than
            // silently slipping through when it happens never to be compared.
            for (AgentEngine engine : engines) {
                legacyRankOf(engine);
            }
            this.engines = engines.stream()
                    .sorted(Comparator
                            .comparingInt(AgentEngine::priority)
                            .thenComparingInt(EngineSelector::legacyRankOf))
                    .toList();
        }
        log.info("EngineSelector 已初始化 {} 个引擎: {}",
                this.engines.size(),
                this.engines.stream().map(e -> e.name() + "(p=" + e.priority() + ")")
                        .reduce((a, b) -> a + ", " + b).orElse("无"));
    }

    private static int legacyRankOf(AgentEngine engine) {
        String name = engine.name();
        Integer rank = LEGACY_RANK.get(name);
        if (rank == null) {
            throw new IllegalStateException(
                    "EngineSelector 遇到未登记 legacyRank 的引擎: " + name
                            + "；请在 EngineSelector.LEGACY_RANK 登记后再启动。");
        }
        return rank;
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
