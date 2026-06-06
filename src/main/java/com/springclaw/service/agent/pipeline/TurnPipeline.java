package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.exception.AgentExecutionException;
import com.springclaw.service.agent.lifecycle.TurnContext;
import com.springclaw.service.agent.lifecycle.TurnRequest;

public interface TurnPipeline {
    TurnContext prepare(TurnRequest request) throws AgentExecutionException;
}
