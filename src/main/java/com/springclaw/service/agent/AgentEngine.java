package com.springclaw.service.agent;

import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;
import com.springclaw.service.chat.impl.OparLoopEngine;

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
     * 优先级，数字越小越优先。
     * EngineSelector 按 priority 升序排列，选择第一个 supports()=true 的引擎。
     */
    int priority();

    /**
     * 判断此引擎是否适用于当前上下文。
     * 每个引擎根据自己的条件声明适用范围。
     */
    boolean supports(ChatContext ctx);

    /**
     * 执行引擎并返回结果。
     *
     * @param ctx               聊天上下文
     * @param fallbackResponder 模型不可用时的兜底回答生成器
     * @return 执行结果
     */
    ChatExecutionResult execute(ChatContext ctx, OparLoopEngine.FallbackResponder fallbackResponder);
}
