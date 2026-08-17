package com.springclaw.service.agent.kernel;

import com.springclaw.service.chat.impl.ChatContext;

import java.util.List;

/**
 * 范式策略:只表达差异点(步语义/终止条件/答案组装),
 * 步进、边界事件、max-steps 兜底、假完成守护由 AgentLoopKernel 承担。
 *
 * @param <S> 引擎步进状态(在 nextStep 间演化,终局时经 LoopOutcome.finalState 交回)
 * @param <T> 单步模型产出的类型(String 文本 / 结构化 Plan 等)
 */
public interface LoopSpec<S, T> {

    int maxSteps();

    String stepKind();

    S initState(ChatContext ctx);

    /** 单步语义:实现内部经 ctx.kernel().callModel(...) 发起模型调用。 */
    S nextStep(LoopContext ctx, S state, int stepIndex);

    /** 步内产出转 KernelResult(含范式终止判定,如 ReAct 无 Action 行=terminal)。 */
    KernelResult<T> evaluate(S state, int stepIndex);

    /** N 段:终局答案组装(如 OparLoop 的 reflect 文本、Reflexion 的 memory 综合)。 */
    String composeAnswer(ChatContext ctx, S state, List<KernelResult<T>> steps);
}
