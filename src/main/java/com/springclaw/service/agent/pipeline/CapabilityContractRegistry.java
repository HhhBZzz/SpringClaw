package com.springclaw.service.agent.pipeline;

import com.springclaw.service.agent.lifecycle.CapabilityContract;

import java.util.Optional;

public interface CapabilityContractRegistry {
    Optional<CapabilityContract> find(String capabilityId);
}
