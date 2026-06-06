package com.springclaw.service.agent.lifecycle;

import java.util.List;

public record CapabilityContract(String capabilityId,
                                 String toolset,
                                 List<SlotRequirement> requiredSlots,
                                 List<SlotRequirement> optionalSlots,
                                 EvidenceContract evidenceContract,
                                 RiskLevel riskLevel) {
    public CapabilityContract {
        capabilityId = safe(capabilityId);
        toolset = safe(toolset);
        requiredSlots = requiredSlots == null ? List.of() : List.copyOf(requiredSlots);
        optionalSlots = optionalSlots == null ? List.of() : List.copyOf(optionalSlots);
        evidenceContract = evidenceContract == null ? EvidenceContract.none() : evidenceContract;
        riskLevel = riskLevel == null ? RiskLevel.READ : riskLevel;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
