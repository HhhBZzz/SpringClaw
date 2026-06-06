package com.springclaw.service.agent.lifecycle;

import java.util.List;

public record EvidenceContract(List<String> requiredEvidenceTypes, boolean structuredRequired) {
    public EvidenceContract {
        requiredEvidenceTypes = requiredEvidenceTypes == null ? List.of() : List.copyOf(requiredEvidenceTypes);
    }

    public static EvidenceContract none() {
        return new EvidenceContract(List.of(), false);
    }
}
