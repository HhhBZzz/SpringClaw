package com.springclaw.service.agent;

import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;
import com.springclaw.service.context.AssembledContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一 Agent 引擎接口。
 * <p>
 * 所有引擎（OPAR、Simplified、AgentRuntime、BasicStream）实现此接口，
 * 由 EngineSelector 按优先级选择，替代 ChatServiceImpl 中的硬编码 if/else 分支。
 * </p>
 */
public interface AgentEngine {

    /** 引擎名称（用于日志和 trace） */
    String name();

    /**
     * 此引擎归属的 Agent 范式。
     * <p>
     * EngineSelector 在请求显式指定 paradigm 时按此过滤选择;
     * 不指定时此值仅用于 trace/timeline 范式标注。
     * </p>
     *
     * @see AgentParadigm
     */
    AgentParadigm paradigm();

    /**
     * 优先级，数字越小越优先。
     * EngineSelector 按 priority 升序排列，选择第一个 supports()=true 的引擎。
     */
    int priority();

    /**
     * 判断此引擎是否适用于当前上下文。
     * 每个引擎根据自己的条件声明适用范围，条件须包含完整路由逻辑
     * （responseMode、executionMode、feature flag 等），确保 EngineSelector 是唯一路由决策点。
     */
    boolean supports(ChatContext ctx);

    /**
     * 执行引擎并返回结果。
     *
     * @param ctx               聊天上下文
     * @param fallbackResponder 模型不可用时的兜底回答生成器
     * @return 执行结果
     */
    ChatExecutionResult execute(ChatContext ctx, FallbackResponder fallbackResponder);

    /**
     * 模型不可用时的兜底回答生成器。
     * <p>
     * 从 OparLoopEngine 提取到接口层，消除对具体引擎类的耦合。
     * </p>
     */
    @FunctionalInterface
    interface FallbackResponder {
        String respond(String reason, AssembledContext context);
    }

    /**
     * 支持流式 SSE 输出的引擎子接口。
     * <p>
     * 实现此接口的引擎可以被流式路由路径选中，
     * 通过 {@link #stream} 方法自行管理 SSE 生命周期、锁释放和持久化。
     * </p>
     */
    interface StreamableAgentEngine extends AgentEngine {

        /**
         * 流式执行：模型直接 SSE 输出，引擎自行管理完整生命周期。
         * 成功时自行持久化并完成 SSE；完全失败时委托给 fallbackHandler 进行降级处理。
         *
         * @param context          聊天上下文
         * @param emitter          SSE emitter
         * @param lockToken        会话锁 token
         * @param lockReleased     锁释放标记（CAS）
         * @param disposableRef    流式 Disposable 引用
         * @param fallbackHandler  完全失败时的降级回调
         * @return 流式 Disposable
         */
        Disposable stream(ChatContext context,
                          SseEmitter emitter,
                          String lockToken,
                          AtomicBoolean lockReleased,
                          AtomicReference<Disposable> disposableRef,
                          OnStreamFailure fallbackHandler);
    }

    /**
     * 流式失败回调：当模型流式连接完全失败（无部分内容）时，
     * 由 ChatServiceImpl 提供降级逻辑（streamBlockingFallback）。
     */
    @FunctionalInterface
    interface OnStreamFailure {
        void handle(ChatContext ctx, Throwable error, SseEmitter emitter,
                    String lockToken, AtomicBoolean lockReleased);
    }
}
