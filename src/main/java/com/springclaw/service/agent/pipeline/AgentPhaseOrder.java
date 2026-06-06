package com.springclaw.service.agent.pipeline;

final class AgentPhaseOrder {
    static final int INPUT_NORMALIZE = 10;
    static final int UTTERANCE_CLASSIFY = 20;
    static final int CONTROL_BYPASS = 30;
    static final int CONTEXT_RESOLVE = 50;

    private AgentPhaseOrder() {
    }
}
