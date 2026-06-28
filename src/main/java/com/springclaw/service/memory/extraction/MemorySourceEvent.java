package com.springclaw.service.memory.extraction;

public record MemorySourceEvent(
        String eventKey,
        String role,
        String eventType,
        String content
) {
}
