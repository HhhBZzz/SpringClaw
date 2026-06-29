package com.springclaw.service.memory.consolidation;

import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.port.MemoryRecordStore;
import com.springclaw.service.memory.MemoryManagementService;
import com.springclaw.service.memory.MemoryWriteCommand;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class MemoryConsolidationService {

    private static final int MAX_EPISODES = 500;

    private final MemoryRecordStore recordStore;
    private final MemoryManagementService memoryManagementService;
    private final MemoryConsolidationProposer proposer;

    public MemoryConsolidationService(
            MemoryRecordStore recordStore,
            MemoryManagementService memoryManagementService,
            MemoryConsolidationProposer proposer
    ) {
        this.recordStore = Objects.requireNonNull(recordStore, "recordStore");
        this.memoryManagementService = Objects.requireNonNull(
                memoryManagementService,
                "memoryManagementService"
        );
        this.proposer = Objects.requireNonNull(proposer, "proposer");
    }

    public MemoryConsolidationRunResult consolidate(
            MemoryScope scope,
            int episodeLimit
    ) {
        Objects.requireNonNull(scope, "scope");
        int safeLimit = Math.max(2, Math.min(episodeLimit, MAX_EPISODES));
        List<MemoryRecordVersion> episodes = recordStore.findActiveByScope(
                scope,
                Set.of(MemoryType.EPISODIC),
                safeLimit
        );
        Optional<MemoryConsolidationProposal> proposal = proposer.propose(episodes);
        if (proposal.isEmpty()) {
            return MemoryConsolidationRunResult.skipped();
        }

        MemoryWriteCommand command = proposal.get().command();
        Optional<MemoryRecordVersion> existing = recordStore.findByAutomaticSourceCurrent(
                command.sourceKind(),
                command.sourceIdentity(),
                command.extractionPolicyVersion(),
                command.memoryType()
        );
        if (existing.isPresent()) {
            return new MemoryConsolidationRunResult(
                    false,
                    existing.get(),
                    proposal.get().sourceVersionIds()
            );
        }

        MemoryRecordVersion candidate = memoryManagementService.create(command);
        return new MemoryConsolidationRunResult(
                true,
                candidate,
                proposal.get().sourceVersionIds()
        );
    }
}
