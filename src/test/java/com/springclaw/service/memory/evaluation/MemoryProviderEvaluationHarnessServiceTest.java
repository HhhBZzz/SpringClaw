package com.springclaw.service.memory.evaluation;

import com.springclaw.service.memory.extraction.SemanticMemoryCandidate;
import com.springclaw.service.memory.extraction.SemanticMemoryExtractionResult;
import com.springclaw.service.memory.extraction.SemanticMemoryExtractor;
import com.springclaw.service.memory.extraction.SemanticMemoryJudge;
import com.springclaw.service.memory.extraction.SemanticMemoryJudgeVerdict;
import com.springclaw.service.memory.extraction.TerminalMemoryExtractionContext;
import com.springclaw.service.memory.extraction.TerminalReflectionGenerator;
import com.springclaw.service.memory.extraction.TerminalReflectionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class MemoryProviderEvaluationHarnessServiceTest {

    @Test
    void disabledHarnessSkipsProviderBackedChecksWithoutCallingProviders() {
        SemanticMemoryExtractor extractor = mock(SemanticMemoryExtractor.class);
        SemanticMemoryJudge judge = mock(SemanticMemoryJudge.class);
        TerminalReflectionGenerator reflectionGenerator = mock(TerminalReflectionGenerator.class);
        MemoryProviderEvaluationHarnessService service =
                new MemoryProviderEvaluationHarnessService(
                        extractor,
                        judge,
                        reflectionGenerator,
                        false
                );

        MemoryProviderEvaluationReport report = service.evaluate();

        assertThat(report.schema()).isEqualTo("springclaw.memory-provider-evaluation.v1");
        assertThat(report.enabled()).isFalse();
        assertThat(report.total()).isEqualTo(3);
        assertThat(report.passed()).isZero();
        assertThat(report.failed()).isZero();
        assertThat(report.skipped()).isEqualTo(3);
        assertThat(report.cases())
                .extracting(MemoryProviderEvaluationCase::status)
                .containsOnly("SKIPPED");
        verifyNoInteractions(extractor, judge, reflectionGenerator);
    }

    @Test
    void enabledHarnessRunsExtractorJudgeAndReflectionChecks() {
        FakeExtractor extractor = new FakeExtractor();
        FakeJudge judge = new FakeJudge();
        FakeReflectionGenerator reflectionGenerator = new FakeReflectionGenerator();
        MemoryProviderEvaluationHarnessService service =
                new MemoryProviderEvaluationHarnessService(
                        extractor,
                        judge,
                        reflectionGenerator,
                        true
                );

        MemoryProviderEvaluationReport report = service.evaluate();

        assertThat(report.enabled()).isTrue();
        assertThat(report.total()).isEqualTo(3);
        assertThat(report.passed()).isEqualTo(3);
        assertThat(report.failed()).isZero();
        assertThat(report.skipped()).isZero();
        assertThat(report.cases())
                .extracting(MemoryProviderEvaluationCase::caseId)
                .containsExactly(
                        "provider_semantic_extraction_json",
                        "provider_semantic_judge_grounding",
                        "provider_terminal_reflection_grounding"
                );
        assertThat(extractor.context.runId()).isEqualTo("provider-eval-run-1");
        assertThat(judge.candidate.content())
                .contains("short Chinese progress summaries");
        assertThat(reflectionGenerator.context.events())
                .extracting(event -> event.eventKey())
                .contains("chat:provider-eval-run-1:user");
    }

    private static final class FakeExtractor implements SemanticMemoryExtractor {
        private TerminalMemoryExtractionContext context;

        @Override
        public SemanticMemoryExtractionResult extract(
                TerminalMemoryExtractionContext context
        ) {
            this.context = context;
            return new SemanticMemoryExtractionResult(
                    "springclaw.semantic-memory-extraction.v1",
                    List.of(new SemanticMemoryCandidate(
                            "USER_PREFERENCE",
                            "User prefers short Chinese progress summaries.",
                            "user",
                            "PERSONAL_USER",
                            0.82,
                            0.88,
                            List.of("chat:provider-eval-run-1:user"),
                            "provider-eval-run-1",
                            "The user explicitly asked for concise Chinese updates.",
                            false
                    ))
            );
        }
    }

    private static final class FakeJudge implements SemanticMemoryJudge {
        private SemanticMemoryCandidate candidate;

        @Override
        public SemanticMemoryJudgeVerdict judge(
                SemanticMemoryCandidate candidate,
                TerminalMemoryExtractionContext context
        ) {
            this.candidate = candidate;
            return new SemanticMemoryJudgeVerdict(
                    "springclaw.semantic-memory-judge.v1",
                    "ACCEPT",
                    0.93,
                    true,
                    false,
                    false,
                    "The candidate is grounded in the user event."
            );
        }
    }

    private static final class FakeReflectionGenerator implements TerminalReflectionGenerator {
        private TerminalMemoryExtractionContext context;

        @Override
        public TerminalReflectionResult reflect(
                TerminalMemoryExtractionContext context
        ) {
            this.context = context;
            return new TerminalReflectionResult(
                    "springclaw.terminal-reflection.v1",
                    "SUCCESS",
                    "When users request progress updates, keep them concise and in Chinese.",
                    "future progress-update tasks",
                    "",
                    List.of("run:provider-eval-run-1", "event:chat:provider-eval-run-1:user"),
                    0.84
            );
        }
    }
}
