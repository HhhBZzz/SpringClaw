package com.springclaw.runtime.contract;

/**
 * Agent 思考范式——用户可在每次请求显式选择的一等概念。
 * <p>
 * 全部 7 个范式已有引擎实现:前 3 个复用现有引擎显式化,后 4 个(REACT / PLAN_EXECUTE /
 * REFLECTION / MULTI_AGENT)分别由各自的范式引擎接入。无占位范式剩余。
 * </p>
 *
 * @see AgentEngine#paradigm()
 */
public enum AgentParadigm {
    SINGLE_TURN("单轮 Function-Calling"),
    OPAR("Observe-Plan-Act-Reflect"),
    AUTONOMOUS_LOOP("自主多步循环"),
    REACT("Thought-Action-Observation"),
    PLAN_EXECUTE("先规划再执行"),
    REFLECTION("反思改进"),
    MULTI_AGENT("多智能体");

    private final String description;

    AgentParadigm(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /**
     * 是否已有引擎实现。全部 7 个范式均已接入地基:REACT 自其 Task 1 起接入;
     * PLAN_EXECUTE 自其 Task 2 起接入(PlanExecuteEngine 骨架 + isImplemented + LEGACY_RANK 登记,
     * Plan/Execute 循环体由后续 Task 填充);REFLECTION 自其 Task 1 起接入(ReflexionEngine 骨架,
     * 循环体由后续 Task 填充);MULTI_AGENT 自其 Task 1 起接入(MultiAgentEngine 骨架,
     * 循环体由 MA-T3 填充)。无占位剩余。
     */
    public boolean isImplemented() {
        return this == SINGLE_TURN || this == OPAR || this == AUTONOMOUS_LOOP
                || this == REACT || this == PLAN_EXECUTE || this == REFLECTION
                || this == MULTI_AGENT;
    }
}
