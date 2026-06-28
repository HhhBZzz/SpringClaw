package com.springclaw.service.memory.extraction;

public record TerminalMemoryExtractionResult(
        int semanticWritten,
        int reflectionWritten
) {
    public static TerminalMemoryExtractionResult empty() {
        return new TerminalMemoryExtractionResult(0, 0);
    }
}
