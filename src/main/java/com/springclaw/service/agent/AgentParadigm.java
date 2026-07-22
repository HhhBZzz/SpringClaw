package com.springclaw.service.agent;

/**
 * Agent 思考范式——用户可在每次请求显式选择的一等概念。
 * <p>
 * 地基阶段:前 3 个范式复用现有引擎显式化;后 4 个为占位范式,
 * 选择时返回明确的"范式未实现,待增量"降级,不静默走错引擎。
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
     * 地基阶段是否已有引擎实现。占位范式(REACT/PLAN_EXECUTE/REFLECTION/MULTI_AGENT)
     * 选择时由 EngineSelector 抛出明确的"未实现"错误,避免静默回退到别的范式。
     */
    public boolean isImplemented() {
        return this == SINGLE_TURN || this == OPAR || this == AUTONOMOUS_LOOP;
    }
}
