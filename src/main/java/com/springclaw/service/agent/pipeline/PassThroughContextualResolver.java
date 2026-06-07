package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.lifecycle.ResolvedInput;
import com.springclaw.service.agent.lifecycle.TurnContext;
import org.springframework.stereotype.Component;

/**
 * Lightweight local-agent resolver.
 *
 * It deliberately does not rewrite user input from chat history. Cross-turn
 * slot inheritance must be added later through explicit slot contracts, not by
 * guessing business parameters before routing.
 */
@Component
public class PassThroughContextualResolver implements ContextualResolver {

    @Override
    public ResolvedInput resolve(TurnContext context) {
        String original = context == null || context.rawInput() == null ? "" : context.rawInput().text();
        return ResolvedInput.unchanged(original, "追问保持原文；未启用跨轮槽位继承。");
    }
}
