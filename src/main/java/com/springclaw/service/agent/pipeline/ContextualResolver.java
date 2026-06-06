package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.exception.ContextResolutionException;
import com.springclaw.service.agent.lifecycle.ResolvedInput;
import com.springclaw.service.agent.lifecycle.TurnContext;

public interface ContextualResolver {
    ResolvedInput resolve(TurnContext context) throws ContextResolutionException;
}
