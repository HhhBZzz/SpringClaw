package com.springclaw.service.memory.extraction;

public interface SemanticMemoryJudge {

    SemanticMemoryJudgeVerdict judge(
            SemanticMemoryCandidate candidate,
            TerminalMemoryExtractionContext context
    ) throws Exception;
}
