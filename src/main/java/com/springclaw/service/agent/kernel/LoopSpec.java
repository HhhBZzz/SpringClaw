package com.springclaw.service.agent.kernel;

import com.springclaw.service.chat.impl.ChatContext;

import java.util.List;

/**
 * 范式策略:只表达差异点(步语义/终止条件/答案组装),
 * 步进、边界事件、max-steps 兜底、假完成守护由 AgentLoopKernel 承担。
 *
 * <p>已迁移引擎(Phase 2):ReAct(Task 10)/Reflexion(Task 11)/
 * PlanExecute(Task 12)/AutonomousLoop(Task 13)/OparLoop(Task 14)。
 * 各引擎的 spec 内部类是其循环语义的唯一表达处。</p>
 *
 * @param <S> 引擎步进状态(在 nextStep 间演化,终局时经 LoopOutcome.finalState 交回)
 * @param <T> 单步模型产出的类型(String 文本 / 结构化 Plan 等)
 */
public interface LoopSpec<S, T> {

    /** 步数上限(1..N)。达到上限仍无 terminal 时,kernel 以 MAX_STEPS_REACHED 收尾。 */
    int maxSteps();

    /** step 事件的 kind 标签("react"/"reflexion"/"plan-execute"/"autonomous"/"opar")。 */
    String stepKind();

    /** 循环前初始化步进状态(通常捕获引擎侧的 activeClient 与首轮 prompt)。 */
    S initState(ChatContext ctx);

    /**
     * 单步语义:实现内部经 ctx.kernel().callModel(...) 发起模型调用
     * (AutonomousLoop 每步一次 .tools() 原生往返;OparLoop 每步 Plan+Act 双调用;
     * PlanExecute 内层 Execute 子序列在此内部逐步执行,不产生独立 step 事件)。
     * checked exception 需包装为 RuntimeException 上抛,kernel 标记 failed 后传播,
     * 由引擎层降级壳捕获。
     */
    S nextStep(LoopContext ctx, S state, int stepIndex);

    /**
     * 步内产出转 KernelResult(含范式终止判定,如 ReAct 无 Action 行=terminal、
     * AutonomousLoop 的 TASK_COMPLETE(证据验证)/假完成拒绝/纯文本拦截、
     * OparLoop 的 PLAN_READY/DEGRADED)。空 String 产出由 kernel 统一守护
     * (EMPTY_MODEL_OUTPUT),无需在 evaluate 重复判定。
     */
    KernelResult<T> evaluate(S state, int stepIndex);

    /**
     * N 段:终局答案组装。已迁移引擎多保留引擎侧原收尾构造
     * (AutonomousLoop 循环外统一收尾、OparLoop 由引擎取回 state 携带结果),
     * 此时返回空串占位即可。
     */
    String composeAnswer(ChatContext ctx, S state, List<KernelResult<T>> steps);
}
