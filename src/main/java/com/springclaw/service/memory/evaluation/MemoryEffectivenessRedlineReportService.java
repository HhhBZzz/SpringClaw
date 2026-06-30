package com.springclaw.service.memory.evaluation;

import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryFrameItem;
import com.springclaw.runtime.memory.contract.MemoryFrameLayer;
import com.springclaw.runtime.memory.contract.MemoryFrameOmission;
import com.springclaw.runtime.memory.contract.MemoryFrameSourceKind;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.port.ShortTermMemoryStore;
import com.springclaw.runtime.memory.store.InMemoryMemoryIndexOutboxStore;
import com.springclaw.runtime.memory.store.InMemoryMemoryRecordStore;
import com.springclaw.service.memory.MemoryManagementService;
import com.springclaw.service.memory.MemoryVersionFactory;
import com.springclaw.service.memory.MemoryWriteCommand;
import com.springclaw.service.memory.frame.MemoryCoordinator;
import com.springclaw.service.memory.frame.MemoryFrameRequest;
import com.springclaw.service.memory.frame.MemoryFrameResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class MemoryEffectivenessRedlineReportService {

    public static final String SCHEMA = "springclaw.memory-effectiveness-redline.v1";
    private static final Instant T0 = Instant.parse("2026-06-29T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    public MemoryEffectivenessRedlineReport evaluate() {
        List<MemoryEffectivenessRedlineReportCase> cases = List.of(
                crossSessionPreferenceRecall(),
                conflictReplacement(),
                irrelevantMemoryRejection(),
                selectiveForgetting(),
                tokenBudgetSaturation(),
                sourceEvidencePreservation(),
                hypotheticalStatementRejection(),
                sensitiveDataNonWrite(),
                staleVectorHitRejection(),
                injectedMemoryUsageTrace()
        );
        int passed = (int) cases.stream()
                .filter(MemoryEffectivenessRedlineReportCase::passed)
                .count();
        return new MemoryEffectivenessRedlineReport(
                SCHEMA,
                cases.size(),
                passed,
                cases.size() - passed,
                cases,
                Instant.now()
        );
    }

    private MemoryEffectivenessRedlineReportCase crossSessionPreferenceRecall() {
        String caseId = "cross_session_preference_recall";
        try {
            Fixture fixture = new Fixture();
            MemoryScope userScope = MemoryScope.user("api", "old-session", "alice");
            fixture.service.create(command(
                    "pref:alice:summary-style",
                    MemoryType.SEMANTIC,
                    userScope,
                    "Alice prefers short Chinese progress summaries.",
                    "run-old",
                    List.of("chat:run-old:user"),
                    List.of("message_event:run-old:user"),
                    List.of("preference", "summary"),
                    MemoryStatus.ACTIVE
            ));

            MemoryFrameResult result = fixture.retrieve(
                    MemoryScope.from(personalClaim("alice", "new-session")),
                    "How should you update Alice about progress?"
            );
            boolean passed = result.frame().semanticFacts().size() == 1
                    && "Alice prefers short Chinese progress summaries."
                    .equals(result.frame().semanticFacts().get(0).content())
                    && result.frame().semanticFacts().get(0).evidenceRefs()
                    .equals(List.of("message_event:run-old:user"));
            return reportCase(
                    caseId,
                    "Cross-session preference recall",
                    passed,
                    passed
                            ? "Recalled an active user preference across sessions with source evidence."
                            : "Failed to recall the expected cross-session user preference.",
                    result.frame().semanticFacts().isEmpty()
                            ? List.of("expected:message_event:run-old:user")
                            : result.frame().semanticFacts().get(0).evidenceRefs()
            );
        } catch (RuntimeException ex) {
            return failedCase(caseId, "Cross-session preference recall", ex);
        }
    }

    private MemoryEffectivenessRedlineReportCase conflictReplacement() {
        String caseId = "conflict_replacement";
        try {
            Fixture fixture = new Fixture();
            MemoryScope scope = MemoryScope.user("api", "old-session", "alice");
            MemoryRecordVersion old = fixture.service.create(command(
                    "pref:alice:language",
                    MemoryType.SEMANTIC,
                    scope,
                    "Alice prefers English answers.",
                    "run-1",
                    List.of("chat:run-1:user"),
                    List.of("message_event:run-1:user"),
                    List.of("preference", "language"),
                    MemoryStatus.ACTIVE
            ));
            fixture.service.supersede(old.memoryVersionId(), command(
                    "pref:alice:language",
                    MemoryType.SEMANTIC,
                    scope,
                    "Alice prefers Chinese answers.",
                    "run-2",
                    List.of("chat:run-2:user"),
                    List.of("message_event:run-2:user"),
                    List.of("preference", "language"),
                    MemoryStatus.ACTIVE
            ));

            MemoryFrameResult result = fixture.retrieve(
                    MemoryScope.from(personalClaim("alice", "new-session")),
                    "Which language should you use for Alice?"
            );
            boolean passed = result.frame().semanticFacts().size() == 1
                    && "Alice prefers Chinese answers."
                    .equals(result.frame().semanticFacts().get(0).content());
            return reportCase(
                    caseId,
                    "Conflict replacement",
                    passed,
                    passed
                            ? "Retrieved only the latest active fact after supersede."
                            : "Old or conflicting fact remained visible after supersede.",
                    List.of("message_event:run-1:user", "message_event:run-2:user")
            );
        } catch (RuntimeException ex) {
            return failedCase(caseId, "Conflict replacement", ex);
        }
    }

    private MemoryEffectivenessRedlineReportCase irrelevantMemoryRejection() {
        String caseId = "irrelevant_memory_rejection";
        try {
            Fixture fixture = new Fixture();
            MemoryScope scope = MemoryScope.user("api", "old-session", "alice");
            fixture.service.create(command(
                    "pref:alice:summary-style",
                    MemoryType.SEMANTIC,
                    scope,
                    "Alice prefers short Chinese progress summaries.",
                    "run-1",
                    List.of("chat:run-1:user"),
                    List.of("message_event:run-1:user"),
                    List.of("preference", "summary"),
                    MemoryStatus.ACTIVE
            ));
            fixture.service.create(command(
                    "pref:alice:restaurant",
                    MemoryType.SEMANTIC,
                    scope,
                    "Alice likes quiet Italian restaurants.",
                    "run-2",
                    List.of("chat:run-2:user"),
                    List.of("message_event:run-2:user"),
                    List.of("food", "restaurant"),
                    MemoryStatus.ACTIVE
            ));

            MemoryFrameResult result = fixture.retrieve(
                    MemoryScope.from(personalClaim("alice", "new-session")),
                    "Give Alice a short progress summary."
            );
            boolean recalledRelevant = result.frame().semanticFacts().size() == 1
                    && "Alice prefers short Chinese progress summaries."
                    .equals(result.frame().semanticFacts().get(0).content());
            boolean omittedIrrelevant = result.frame().omissions().stream()
                    .anyMatch(omission -> omission.category() == MemoryFrameOmission.Category.LOW_SCORE
                            && omission.reason().contains("irrelevant"));
            return reportCase(
                    caseId,
                    "Irrelevant memory rejection",
                    recalledRelevant && omittedIrrelevant,
                    recalledRelevant && omittedIrrelevant
                            ? "Kept the relevant summary preference and omitted the restaurant memory as low score."
                            : "Relevant/irrelevant memory filtering did not match expectation.",
                    List.of("message_event:run-1:user", "message_event:run-2:user")
            );
        } catch (RuntimeException ex) {
            return failedCase(caseId, "Irrelevant memory rejection", ex);
        }
    }

    private MemoryEffectivenessRedlineReportCase selectiveForgetting() {
        String caseId = "selective_forgetting";
        try {
            Fixture fixture = new Fixture();
            MemoryScope scope = MemoryScope.user("api", "old-session", "alice");
            MemoryRecordVersion active = fixture.service.create(command(
                    "pref:alice:obsolete",
                    MemoryType.SEMANTIC,
                    scope,
                    "Alice prefers very verbose updates.",
                    "run-1",
                    List.of("chat:run-1:user"),
                    List.of("message_event:run-1:user"),
                    List.of("preference", "verbose"),
                    MemoryStatus.ACTIVE
            ));
            fixture.service.transition(
                    active.memoryVersionId(),
                    MemoryStatus.ACTIVE,
                    MemoryStatus.REJECTED,
                    T0.plusSeconds(10)
            );

            MemoryFrameResult result = fixture.retrieve(
                    MemoryScope.from(personalClaim("alice", "new-session")),
                    "How should you update Alice?"
            );
            boolean passed = result.frame().semanticFacts().isEmpty();
            return reportCase(
                    caseId,
                    "Selective forgetting",
                    passed,
                    passed
                            ? "Rejected memory was excluded from retrieval."
                            : "Rejected memory remained visible in retrieval.",
                    List.of("message_event:run-1:user")
            );
        } catch (RuntimeException ex) {
            return failedCase(caseId, "Selective forgetting", ex);
        }
    }

    private MemoryEffectivenessRedlineReportCase tokenBudgetSaturation() {
        String caseId = "token_budget_saturation";
        try {
            Fixture fixture = new Fixture(1_000);
            MemoryScope scope = MemoryScope.user("api", "old-session", "alice");
            for (int i = 0; i < 8; i++) {
                fixture.service.create(command(
                        "pref:alice:summary:" + i,
                        MemoryType.SEMANTIC,
                        scope,
                        "Alice progress summary memory " + i + " " + "x".repeat(180),
                        "run-" + i,
                        List.of("chat:run-" + i + ":user"),
                        List.of("message_event:run-" + i + ":user"),
                        List.of("progress", "summary"),
                        MemoryStatus.ACTIVE
                ));
            }

            MemoryFrameResult result = fixture.retrieve(
                    MemoryScope.from(personalClaim("alice", "new-session")),
                    "Give Alice a progress summary."
            );
            int totalChars = result.frame().semanticFacts().stream()
                    .mapToInt(item -> item.content().length())
                    .sum();
            boolean budgeted = totalChars <= 200
                    && result.frame().omissions().stream()
                    .map(MemoryFrameOmission::category)
                    .anyMatch(MemoryFrameOmission.Category.BUDGET_TRUNCATED::equals);
            return reportCase(
                    caseId,
                    "Token budget saturation",
                    budgeted,
                    budgeted
                            ? "Truncated low-score semantic items when the memory budget saturated."
                            : "Semantic facts exceeded the redline budget or lacked truncation evidence.",
                    List.of("memory-frame:maxChars=1000", "semanticChars=" + totalChars)
            );
        } catch (RuntimeException ex) {
            return failedCase(caseId, "Token budget saturation", ex);
        }
    }

    private MemoryEffectivenessRedlineReportCase sourceEvidencePreservation() {
        String caseId = "source_evidence_preservation";
        try {
            Fixture fixture = new Fixture();
            MemoryScope scope = MemoryScope.user("api", "old-session", "alice");
            List<String> expectedEvidence = List.of(
                    "message_event:run-evidence:user",
                    "message_event:run-evidence:assistant"
            );
            fixture.service.create(command(
                    "pref:alice:review-style",
                    MemoryType.SEMANTIC,
                    scope,
                    "Alice wants review comments tied to source evidence.",
                    "run-evidence",
                    List.of("chat:run-evidence:user"),
                    expectedEvidence,
                    List.of("preference", "evidence"),
                    MemoryStatus.ACTIVE
            ));

            MemoryFrameResult result = fixture.retrieve(
                    MemoryScope.from(personalClaim("alice", "new-session")),
                    "How should review comments be written for Alice?"
            );
            boolean passed = result.frame().semanticFacts().size() == 1
                    && result.frame().semanticFacts().get(0).evidenceRefs()
                    .equals(expectedEvidence);
            return reportCase(
                    caseId,
                    "Source evidence preservation",
                    passed,
                    passed
                            ? "Retrieved memory preserved all source evidence refs."
                            : "Retrieved memory lost or changed source evidence refs.",
                    expectedEvidence
            );
        } catch (RuntimeException ex) {
            return failedCase(caseId, "Source evidence preservation", ex);
        }
    }

    private MemoryEffectivenessRedlineReportCase hypotheticalStatementRejection() {
        String caseId = "hypothetical_statement_rejection";
        try {
            Fixture fixture = new Fixture();
            MemoryScope scope = MemoryScope.user("api", "old-session", "alice");
            fixture.service.create(command(
                    "hypothetical:alice:dark-mode",
                    MemoryType.SEMANTIC,
                    scope,
                    "Alice might prefer dark mode someday.",
                    "run-hypothetical",
                    List.of("chat:run-hypothetical:user"),
                    List.of("message_event:run-hypothetical:user"),
                    List.of("hypothetical", "preference"),
                    MemoryStatus.CANDIDATE
            ));

            MemoryFrameResult result = fixture.retrieve(
                    MemoryScope.from(personalClaim("alice", "new-session")),
                    "Does Alice prefer dark mode?"
            );
            boolean passed = result.frame().semanticFacts().isEmpty();
            return reportCase(
                    caseId,
                    "Hypothetical statement rejection",
                    passed,
                    passed
                            ? "Hypothetical candidate memory was not injected."
                            : "Hypothetical candidate memory became visible.",
                    List.of("message_event:run-hypothetical:user")
            );
        } catch (RuntimeException ex) {
            return failedCase(caseId, "Hypothetical statement rejection", ex);
        }
    }

    private MemoryEffectivenessRedlineReportCase sensitiveDataNonWrite() {
        String caseId = "sensitive_data_non_write";
        try {
            Fixture fixture = new Fixture();
            MemoryScope scope = MemoryScope.user("api", "old-session", "alice");
            fixture.service.create(command(
                    "sensitive:alice:token",
                    MemoryType.SEMANTIC,
                    scope,
                    "Alice's API token is sk-live-redline-secret.",
                    "run-sensitive",
                    List.of("chat:run-sensitive:user"),
                    List.of("message_event:run-sensitive:user"),
                    List.of("sensitive", "secret"),
                    MemoryStatus.CANDIDATE
            ));

            MemoryFrameResult result = fixture.retrieve(
                    MemoryScope.from(personalClaim("alice", "new-session")),
                    "What API token should be used for Alice?"
            );
            boolean passed = result.frame().semanticFacts().isEmpty();
            return reportCase(
                    caseId,
                    "Sensitive data non-write",
                    passed,
                    passed
                            ? "Sensitive candidate memory was not injected."
                            : "Sensitive candidate memory became visible.",
                    List.of("message_event:run-sensitive:user")
            );
        } catch (RuntimeException ex) {
            return failedCase(caseId, "Sensitive data non-write", ex);
        }
    }

    private MemoryEffectivenessRedlineReportCase staleVectorHitRejection() {
        String caseId = "stale_vector_hit_rejection";
        try {
            Fixture fixture = new Fixture();
            MemoryScope scope = MemoryScope.user("api", "old-session", "alice");
            MemoryRecordVersion old = fixture.service.create(command(
                    "pref:alice:report-format",
                    MemoryType.SEMANTIC,
                    scope,
                    "Alice prefers raw JSON reports.",
                    "run-stale-1",
                    List.of("chat:run-stale-1:user"),
                    List.of("message_event:run-stale-1:user"),
                    List.of("preference", "report"),
                    MemoryStatus.ACTIVE
            ));
            fixture.service.supersede(old.memoryVersionId(), command(
                    "pref:alice:report-format",
                    MemoryType.SEMANTIC,
                    scope,
                    "Alice prefers concise bullet reports.",
                    "run-stale-2",
                    List.of("chat:run-stale-2:user"),
                    List.of("message_event:run-stale-2:user"),
                    List.of("preference", "report"),
                    MemoryStatus.ACTIVE
            ));

            MemoryFrameResult result = fixture.retrieve(
                    MemoryScope.from(personalClaim("alice", "new-session")),
                    "Which report format should Alice receive?"
            );
            List<String> contents = result.frame().semanticFacts().stream()
                    .map(MemoryFrameItem::content)
                    .toList();
            boolean passed = contents.contains("Alice prefers concise bullet reports.")
                    && !contents.contains("Alice prefers raw JSON reports.");
            return reportCase(
                    caseId,
                    "Stale vector hit rejection",
                    passed,
                    passed
                            ? "Superseded stale fact was excluded while latest fact remained visible."
                            : "Superseded stale fact was still visible or latest fact was missing.",
                    List.of("message_event:run-stale-1:user", "message_event:run-stale-2:user")
            );
        } catch (RuntimeException ex) {
            return failedCase(caseId, "Stale vector hit rejection", ex);
        }
    }

    private MemoryEffectivenessRedlineReportCase injectedMemoryUsageTrace() {
        String caseId = "injected_memory_usage_trace";
        try {
            Instant capturedAt = T0.plusSeconds(30);
            MemoryFrameItem item = new MemoryFrameItem(
                    "memory:alice:summary-style:v1",
                    MemoryFrameSourceKind.MEMORY_RECORD,
                    MemoryFrameLayer.SEMANTIC_FACT,
                    "pref:alice:summary-style",
                    "memory-version-1",
                    MemoryType.SEMANTIC,
                    MemoryScope.user("api", "old-session", "alice").scopeType(),
                    MemoryScope.user("api", "old-session", "alice").scopeId(),
                    "Alice prefers short Chinese progress summaries.",
                    "hash-summary-style",
                    List.of("message_event:run-usage:user"),
                    0.85,
                    0.9,
                    0.95,
                    1,
                    capturedAt
            );
            MemoryFrame frame = new MemoryFrame(
                    "run-usage",
                    MemoryScope.from(personalClaim("alice", "new-session")),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(item),
                    List.of(),
                    List.of(),
                    Map.of("semantic", "1"),
                    List.of(),
                    capturedAt,
                    "frame-hash-usage"
            );
            MemoryUsageTrace trace = new MemoryUsageTraceEvaluator().evaluate(
                    frame,
                    "Alice prefers short Chinese progress summaries."
            );
            boolean passed = trace.memoryInjected()
                    && trace.memoryReferencedInAnswer()
                    && trace.memoryReferenceKind() == MemoryUsageTrace.ReferenceKind.EXPLICIT
                    && trace.referencedSourceIds().equals(List.of(item.sourceId()));
            return reportCase(
                    caseId,
                    "Injected-memory usage trace",
                    passed,
                    passed
                            ? "Deterministic evaluator detected explicit use of injected memory."
                            : "Injected memory was not traced as referenced by the answer.",
                    List.of("message_event:run-usage:user", item.sourceId())
            );
        } catch (RuntimeException ex) {
            return failedCase(caseId, "Injected-memory usage trace", ex);
        }
    }

    private static MemoryEffectivenessRedlineReportCase reportCase(
            String caseId,
            String title,
            boolean passed,
            String summary,
            List<String> evidence
    ) {
        return new MemoryEffectivenessRedlineReportCase(
                caseId,
                title,
                passed,
                summary,
                evidence
        );
    }

    private static MemoryEffectivenessRedlineReportCase failedCase(
            String caseId,
            String title,
            RuntimeException ex
    ) {
        return reportCase(
                caseId,
                title,
                false,
                ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                List.of("exception:" + ex.getClass().getName())
        );
    }

    private static SessionAccessClaim personalClaim(String userId, String sessionKey) {
        return SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                sessionKey,
                userId
        );
    }

    private static MemoryWriteCommand command(
            String logicalMemoryId,
            MemoryType type,
            MemoryScope scope,
            String content,
            String runId,
            List<String> sourceEventIds,
            List<String> evidenceRefs,
            List<String> tags,
            MemoryStatus status
    ) {
        return new MemoryWriteCommand(
                logicalMemoryId,
                type,
                scope,
                content,
                content,
                runId,
                sourceEventIds,
                evidenceRefs,
                tags,
                0.85,
                0.9,
                status,
                T0,
                null,
                "MESSAGE_EVENT",
                runId + ":" + String.join("+", sourceEventIds),
                "redline-fixture-v1"
        );
    }

    private static final class Fixture {
        private final InMemoryMemoryRecordStore recordStore =
                new InMemoryMemoryRecordStore();
        private final MemoryManagementService service = new MemoryManagementService(
                recordStore,
                new InMemoryMemoryIndexOutboxStore(),
                new MemoryVersionFactory(CLOCK)
        );
        private final MemoryCoordinator coordinator;

        private Fixture() {
            this(6_000);
        }

        private Fixture(int maxChars) {
            this.coordinator = new MemoryCoordinator(
                    recordStore,
                    emptyShortTermProvider(),
                    ignored -> List.of(),
                    CLOCK,
                    maxChars,
                    20
            );
        }

        private MemoryFrameResult retrieve(MemoryScope scope, String question) {
            return coordinator.retrieve(new MemoryFrameRequest(
                    "run-current",
                    scope,
                    question
            ));
        }

        private static ObjectProvider<ShortTermMemoryStore> emptyShortTermProvider() {
            return new ObjectProvider<>() {
                @Override
                public ShortTermMemoryStore getObject(Object... args) {
                    throw new IllegalStateException("No short-term store");
                }

                @Override
                public ShortTermMemoryStore getIfAvailable() {
                    return null;
                }

                @Override
                public ShortTermMemoryStore getIfUnique() {
                    return null;
                }

                @Override
                public ShortTermMemoryStore getObject() {
                    throw new IllegalStateException("No short-term store");
                }

                @Override
                public Iterator<ShortTermMemoryStore> iterator() {
                    return List.<ShortTermMemoryStore>of().iterator();
                }

                @Override
                public Stream<ShortTermMemoryStore> stream() {
                    return Stream.empty();
                }
            };
        }
    }
}
