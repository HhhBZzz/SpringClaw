package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.exception.RoutingDecisionException;
import com.springclaw.service.agent.lifecycle.IntentDecision;
import com.springclaw.service.agent.lifecycle.TurnContext;

public interface IntentRouter {
    IntentDecision route(TurnContext context) throws RoutingDecisionException;
}
