package com.springclaw.service.memory.extraction;

import java.util.List;

public record TerminalMemoryExtractionContext(
        String runId,
        String sessionKey,
        String channel,
        String userId,
        List<MemorySourceEvent> events
) {
    public TerminalMemoryExtractionContext {
        events = events == null ? List.of() : List.copyOf(events);
    }
}
