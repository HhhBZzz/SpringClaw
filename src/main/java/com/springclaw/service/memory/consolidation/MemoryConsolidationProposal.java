package com.springclaw.service.memory.consolidation;

import com.springclaw.service.memory.MemoryWriteCommand;

import java.util.List;
import java.util.Objects;

public record MemoryConsolidationProposal(
        MemoryWriteCommand command,
        List<String> sourceVersionIds
) {
    public MemoryConsolidationProposal {
        command = Objects.requireNonNull(command, "command");
        sourceVersionIds = sourceVersionIds == null
                ? List.of()
                : sourceVersionIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (sourceVersionIds.size() < 2) {
            throw new IllegalArgumentException(
                    "consolidation proposal requires at least two source versions"
            );
        }
    }
}
