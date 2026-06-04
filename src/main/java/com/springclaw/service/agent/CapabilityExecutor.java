package com.springclaw.service.agent;

import com.springclaw.service.context.AssembledContext;

import java.util.List;

/**
 * Pluggable backend executor for one Agent toolset.
 */
public interface CapabilityExecutor {

    String toolset();

    boolean supports(AgentDecision decision);

    List<CapabilityResult> execute(AgentDecision decision, AssembledContext assembled, String requestId);
}
