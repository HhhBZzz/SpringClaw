package com.springclaw.service.agent;

import java.util.Map;

public record AgentActionProposalResult(String proposalId,
                                        String status,
                                        String message,
                                        Map<String, Object> result) {
}
