package com.springclaw.service.agent;

import java.util.Set;

public record AgentDecisionRequest(String sessionKey,
                                   String channel,
                                   String userId,
                                   String roleCode,
                                   String requestId,
                                   String question,
                                   String responseMode,
                                   Set<String> allowedToolPacks) {
}
