package com.springclaw.service.agent;

import java.util.Map;

public record AgentActionProposal(String proposalId,
                                  String requestId,
                                  String sessionKey,
                                  String userId,
                                  String actionType,
                                  String title,
                                  String summary,
                                  String riskLevel,
                                  Map<String, Object> payload,
                                  long expiresAt,
                                  String status) {
}
