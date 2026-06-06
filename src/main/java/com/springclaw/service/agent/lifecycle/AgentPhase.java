package com.springclaw.service.agent.lifecycle;

public enum AgentPhase {
    INPUT_NORMALIZE,
    UTTERANCE_CLASSIFY,
    CONTROL_BYPASS,
    MEMORY_SNAPSHOT,
    CONTEXT_RESOLVE,
    INTENT_ROUTE,
    SLOT_BIND,
    CAPABILITY_PLAN,
    TOOL_EXECUTE,
    OPAR_PLAN,
    OPAR_ACT,
    OPAR_REFLECT,
    QUALITY_EVALUATE,
    FINALIZE
}
