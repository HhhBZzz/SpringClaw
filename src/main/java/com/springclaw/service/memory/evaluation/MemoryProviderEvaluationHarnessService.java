package com.springclaw.service.memory.evaluation;

import com.springclaw.service.memory.extraction.MemorySourceEvent;
import com.springclaw.service.memory.extraction.SemanticMemoryCandidate;
import com.springclaw.service.memory.extraction.SemanticMemoryExtractionResult;
import com.springclaw.service.memory.extraction.SemanticMemoryExtractor;
import com.springclaw.service.memory.extraction.SemanticMemoryJudge;
import com.springclaw.service.memory.extraction.SemanticMemoryJudgeVerdict;
import com.springclaw.service.memory.extraction.TerminalMemoryExtractionContext;
import com.springclaw.service.memory.extraction.TerminalReflectionGenerator;
import com.springclaw.service.memory.extraction.TerminalReflectionResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class MemoryProviderEvaluationHarnessService {

    static final String SCHEMA = "springclaw.memory-provider-evaluation.v1";
    private static final String SEMANTIC_EXTRACTION_SCHEMA =
            "springclaw.semantic-memory-extraction.v1";
    private static final String SEMANTIC_JUDGE_SCHEMA =
            "springclaw.semantic-memory-judge.v1";
    private static final String TERMINAL_REFLECTION_SCHEMA =
            "springclaw.terminal-reflection.v1";

    private final SemanticMemoryExtractor extractor;
    private final SemanticMemoryJudge judge;
    private final TerminalReflectionGenerator reflectionGenerator;
    private final boolean enabled;

    public MemoryProviderEvaluationHarnessService(
            SemanticMemoryExtractor extractor,
            SemanticMemoryJudge judge,
            TerminalReflectionGenerator reflectionGenerator,
            @Value("${springclaw.memory.evaluation.provider-harness-enabled:false}")
            boolean enabled
    ) {
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.judge = Objects.requireNonNull(judge, "judge");
        this.reflectionGenerator = Objects.requireNonNull(
                reflectionGenerator,
                "reflectionGenerator"
        );
        this.enabled = enabled;
    }

    public MemoryProviderEvaluationReport evaluate() {
        TerminalMemoryExtractionContext context = fixtureContext();
        if (!enabled) {
            return report(false, skippedCases());
        }
        ExtractionCheck extraction = evaluateExtraction(context);
        List<MemoryProviderEvaluationCase> cases = List.of(
                extraction.result(),
                evaluateJudge(context, extraction.candidate()),
                evaluateReflection(context)
        );
        return report(true, cases);
    }

    private ExtractionCheck evaluateExtraction(
            TerminalMemoryExtractionContext context
    ) {
        String caseId = "provider_semantic_extraction_json";
        try {
            SemanticMemoryExtractionResult result = extractor.extract(context);
            SemanticMemoryCandidate candidate = firstCandidate(result);
            boolean valid = result != null
                    && SEMANTIC_EXTRACTION_SCHEMA.equals(result.schema())
                    && validCandidate(candidate, context);
            return new ExtractionCheck(
                    new MemoryProviderEvaluationCase(
                            caseId,
                            "Provider semantic extraction JSON",
                            valid ? "PASSED" : "FAILED",
                            valid
                                    ? "Extractor returned a grounded semantic candidate with valid schema."
                                    : "Extractor did not return a valid grounded semantic candidate.",
                            candidate == null
                                    ? List.of("run:" + context.runId())
                                    : evidenceForCandidate(candidate, context)
                    ),
                    valid ? candidate : null
            );
        } catch (Exception ex) {
            return new ExtractionCheck(failed(
                    caseId,
                    "Provider semantic extraction JSON",
                    ex
            ), null);
        }
    }

    private MemoryProviderEvaluationCase evaluateJudge(
            TerminalMemoryExtractionContext context,
            SemanticMemoryCandidate candidate
    ) {
        String caseId = "provider_semantic_judge_grounding";
        if (candidate == null) {
            return new MemoryProviderEvaluationCase(
                    caseId,
                    "Provider semantic judge grounding",
                    "FAILED",
                    "Judge skipped because extraction produced no valid candidate.",
                    List.of("run:" + context.runId())
            );
        }
        try {
            SemanticMemoryJudgeVerdict verdict = judge.judge(candidate, context);
            boolean valid = verdict != null
                    && SEMANTIC_JUDGE_SCHEMA.equals(verdict.schema())
                    && "ACCEPT".equals(upper(verdict.verdict()))
                    && verdict.evidenceGrounded()
                    && !verdict.hypothetical()
                    && !verdict.sensitive()
                    && score(verdict.confidence());
            return new MemoryProviderEvaluationCase(
                    caseId,
                    "Provider semantic judge grounding",
                    valid ? "PASSED" : "FAILED",
                    valid
                            ? "Judge accepted the explicit preference and marked it evidence-grounded."
                            : "Judge verdict was not a grounded ACCEPT for the explicit preference.",
                    evidenceForCandidate(candidate, context)
            );
        } catch (Exception ex) {
            return failed(caseId, "Provider semantic judge grounding", ex);
        }
    }

    private MemoryProviderEvaluationCase evaluateReflection(
            TerminalMemoryExtractionContext context
    ) {
        String caseId = "provider_terminal_reflection_grounding";
        try {
            TerminalReflectionResult reflection =
                    reflectionGenerator.reflect(context);
            boolean valid = reflection != null
                    && TERMINAL_REFLECTION_SCHEMA.equals(reflection.schema())
                    && hasText(reflection.lesson())
                    && score(reflection.confidence())
                    && !reflection.evidenceRefs().isEmpty()
                    && allowedEvidenceRefs(context).containsAll(reflection.evidenceRefs());
            return new MemoryProviderEvaluationCase(
                    caseId,
                    "Provider terminal reflection grounding",
                    valid ? "PASSED" : "FAILED",
                    valid
                            ? "Reflection returned a reusable grounded lesson."
                            : "Reflection lacked valid schema, lesson, confidence, or grounded evidence.",
                    reflection == null
                            ? List.of("run:" + context.runId())
                            : reflection.evidenceRefs()
            );
        } catch (Exception ex) {
            return failed(caseId, "Provider terminal reflection grounding", ex);
        }
    }

    private MemoryProviderEvaluationReport report(
            boolean enabled,
            List<MemoryProviderEvaluationCase> cases
    ) {
        int passed = (int) cases.stream()
                .filter(item -> "PASSED".equals(item.status()))
                .count();
        int failed = (int) cases.stream()
                .filter(item -> "FAILED".equals(item.status()))
                .count();
        int skipped = (int) cases.stream()
                .filter(item -> "SKIPPED".equals(item.status()))
                .count();
        return new MemoryProviderEvaluationReport(
                SCHEMA,
                enabled,
                cases.size(),
                passed,
                failed,
                skipped,
                cases,
                Instant.now()
        );
    }

    private List<MemoryProviderEvaluationCase> skippedCases() {
        return List.of(
                skipped(
                        "provider_semantic_extraction_json",
                        "Provider semantic extraction JSON"
                ),
                skipped(
                        "provider_semantic_judge_grounding",
                        "Provider semantic judge grounding"
                ),
                skipped(
                        "provider_terminal_reflection_grounding",
                        "Provider terminal reflection grounding"
                )
        );
    }

    private MemoryProviderEvaluationCase skipped(String caseId, String title) {
        return new MemoryProviderEvaluationCase(
                caseId,
                title,
                "SKIPPED",
                "Provider evaluation harness is disabled by configuration.",
                List.of("springclaw.memory.evaluation.provider-harness-enabled=false")
        );
    }

    private static MemoryProviderEvaluationCase failed(
            String caseId,
            String title,
            Exception ex
    ) {
        return new MemoryProviderEvaluationCase(
                caseId,
                title,
                "FAILED",
                ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                List.of("exception:" + ex.getClass().getName())
        );
    }

    private static SemanticMemoryCandidate firstCandidate(
            SemanticMemoryExtractionResult result
    ) {
        if (result == null || result.candidates().isEmpty()) {
            return null;
        }
        return result.candidates().get(0);
    }

    private boolean validCandidate(
            SemanticMemoryCandidate candidate,
            TerminalMemoryExtractionContext context
    ) {
        if (candidate == null
                || candidate.hypothetical()
                || !hasText(candidate.kind())
                || !hasText(candidate.content())
                || !context.runId().equals(candidate.sourceRunId())
                || !score(candidate.importance())
                || !score(candidate.confidence())) {
            return false;
        }
        List<String> eventKeys = candidate.sourceEventKeys();
        return !eventKeys.isEmpty()
                && knownEventKeys(context).containsAll(eventKeys);
    }

    private static List<String> evidenceForCandidate(
            SemanticMemoryCandidate candidate,
            TerminalMemoryExtractionContext context
    ) {
        return candidate.sourceEventKeys().stream()
                .map(key -> "event:" + key)
                .filter(allowedEvidenceRefs(context)::contains)
                .toList();
    }

    private static Set<String> knownEventKeys(
            TerminalMemoryExtractionContext context
    ) {
        return context.events().stream()
                .map(MemorySourceEvent::eventKey)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static Set<String> allowedEvidenceRefs(
            TerminalMemoryExtractionContext context
    ) {
        Set<String> refs = new java.util.LinkedHashSet<>();
        refs.add("run:" + context.runId());
        context.events().stream()
                .map(event -> "event:" + event.eventKey())
                .forEach(refs::add);
        return refs;
    }

    private static TerminalMemoryExtractionContext fixtureContext() {
        String runId = "provider-eval-run-1";
        return new TerminalMemoryExtractionContext(
                runId,
                "provider-eval-session",
                "api",
                "provider-eval-user",
                List.of(
                        new MemorySourceEvent(
                                "chat:provider-eval-run-1:user",
                                "USER",
                                "CHAT",
                                "以后给我进度汇报请用简短中文。"
                        ),
                        new MemorySourceEvent(
                                "chat:provider-eval-run-1:assistant:terminal",
                                "ASSISTANT",
                                "CHAT",
                                "收到，后续进度会用简短中文说明。"
                        )
                )
        );
    }

    private static boolean score(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private record ExtractionCheck(
            MemoryProviderEvaluationCase result,
            SemanticMemoryCandidate candidate
    ) {
    }
}
