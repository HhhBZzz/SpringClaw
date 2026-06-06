package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.exception.ParameterExtractFailedException;
import com.springclaw.service.agent.lifecycle.CapabilityContract;
import com.springclaw.service.agent.lifecycle.SlotFrame;
import com.springclaw.service.agent.lifecycle.TurnContext;

public interface SlotBinder {
    SlotFrame bind(TurnContext context, CapabilityContract contract) throws ParameterExtractFailedException;
}
