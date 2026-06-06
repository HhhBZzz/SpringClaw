package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.exception.AgentExecutionException;
import com.springclaw.service.agent.lifecycle.AgentPhase;
import com.springclaw.service.agent.lifecycle.TurnContext;

public interface TurnStage {
    AgentPhase phase();

    boolean supports(TurnContext context);

    TurnContext apply(TurnContext context) throws AgentExecutionException;
}
