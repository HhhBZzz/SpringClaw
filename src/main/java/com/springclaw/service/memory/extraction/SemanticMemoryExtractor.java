package com.springclaw.service.memory.extraction;

public interface SemanticMemoryExtractor {

    SemanticMemoryExtractionResult extract(TerminalMemoryExtractionContext context) throws Exception;
}
