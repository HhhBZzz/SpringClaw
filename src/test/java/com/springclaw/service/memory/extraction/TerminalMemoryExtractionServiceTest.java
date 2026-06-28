package com.springclaw.service.memory.extraction;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.runtime.memory.contract.MemoryIndexOperation;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.store.InMemoryMemoryIndexOutboxStore;
import com.springclaw.runtime.memory.store.InMemoryMemoryRecordStore;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.MemoryManagementService;
import com.springclaw.service.memory.MemoryVersionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TerminalMemoryExtractionServiceTest {

    private static final Instant T0 = Instant.parse("2026-06-28T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private MessageEventService messageEventService;
    private InMemoryMemoryRecordStore recordStore;
    private InMemoryMemoryIndexOutboxStore outboxStore;
    private FakeExtractor extractor;
    private FakeJudge judge;
    private FakeReflectionGenerator reflectionGenerator;
    private TerminalMemoryExtractionService service;

    @BeforeEach
    void setUp() {
        messageEventService = mock(MessageEventService.class);
        recordStore = new InMemoryMemoryRecordStore();
        outboxStore = new InMemoryMemoryIndexOutboxStore(CLOCK);
        extractor = new FakeExtractor();
        judge = new FakeJudge(new SemanticMemoryJudgeVerdict(
                "springclaw.semantic-memory-judge.v1",
                "ACCEPT",
                0.94,
                true,
                false,
                false,
                "grounded"
        ));
        reflectionGenerator = new FakeReflectionGenerator(null);
        service = new TerminalMemoryExtractionService(
                messageEventService,
                new MemoryManagementService(
                        recordStore,
                        outboxStore,
                        new MemoryVersionFactory(CLOCK)
                ),
                extractor,
                judge,
                reflectionGenerator,
                new MemoryExtractionPolicy(12, 0.75, 0.82, CLOCK)
        );
    }

    @Test
    void promotesHighConfidenceGroundedSemanticCandidateToActiveUserMemory() {
        when(messageEventService.listRequestEvents("run-1", "alice", null, null, 12, true))
                .thenReturn(events());
        extractor.result = new SemanticMemoryExtractionResult(
                "springclaw.semantic-memory-extraction.v1",
                List.of(candidate(0.82, 0.88, false))
        );

        TerminalMemoryExtractionResult result = service.extractTerminalRun("run-1", "alice");

        MemoryScope userScope = MemoryScope.user("api", "session-1", "alice");
        assertThat(result.semanticWritten()).isEqualTo(1);
        assertThat(recordStore.findActiveByScope(userScope, Set.of(MemoryType.SEMANTIC), 10))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.status()).isEqualTo(MemoryStatus.ACTIVE);
                    assertThat(record.content()).isEqualTo("User prefers concise Chinese progress summaries.");
                    assertThat(record.sourceRunId()).isEqualTo("run-1");
                    assertThat(record.sourceEventIds())
                            .containsExactly("chat:run-1:user", "chat:run-1:assistant:terminal");
                    assertThat(record.evidenceRefs())
                            .contains("run:run-1", "event:chat:run-1:user", "event:chat:run-1:assistant:terminal");
                    assertThat(record.sourceKind()).isEqualTo("MESSAGE_EVENT");
                    assertThat(record.extractionPolicyVersion()).isEqualTo("semantic-extraction-v1");
                });
        assertThat(outboxStore.findAll())
                .extracting(entry -> entry.operation())
                .containsExactly(MemoryIndexOperation.UPSERT);
    }

    @Test
    void lowConfidenceCandidateRemainsReviewableAndDoesNotWriteIndexOutbox() {
        when(messageEventService.listRequestEvents("run-1", "alice", null, null, 12, true))
                .thenReturn(events());
        extractor.result = new SemanticMemoryExtractionResult(
                "springclaw.semantic-memory-extraction.v1",
                List.of(candidate(0.7, 0.72, false))
        );

        TerminalMemoryExtractionResult result = service.extractTerminalRun("run-1", "alice");

        assertThat(result.semanticWritten()).isEqualTo(1);
        assertThat(recordStore.findByStatus(MemoryStatus.CANDIDATE, 10))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.memoryType()).isEqualTo(MemoryType.SEMANTIC);
                    assertThat(record.status()).isEqualTo(MemoryStatus.CANDIDATE);
                    assertThat(record.evidenceRefs()).contains("run:run-1");
                });
        assertThat(outboxStore.findAll()).isEmpty();
    }

    @Test
    void rejectsHypotheticalOrSensitiveCandidatesBeforeWritingMemory() {
        when(messageEventService.listRequestEvents("run-1", "alice", null, null, 12, true))
                .thenReturn(events());
        extractor.result = new SemanticMemoryExtractionResult(
                "springclaw.semantic-memory-extraction.v1",
                List.of(candidate(0.9, 0.9, true))
        );

        TerminalMemoryExtractionResult result = service.extractTerminalRun("run-1", "alice");

        assertThat(result.semanticWritten()).isZero();
        assertThat(recordStore.findByStatus(MemoryStatus.CANDIDATE, 10)).isEmpty();
        assertThat(recordStore.findActiveByScope(MemoryScope.user("api", "session-1", "alice"), null, 10)).isEmpty();
    }

    @Test
    void repeatedExtractionIsIdempotentBySourceEventsNotCandidateText() {
        when(messageEventService.listRequestEvents("run-1", "alice", null, null, 12, true))
                .thenReturn(events());
        extractor.result = new SemanticMemoryExtractionResult(
                "springclaw.semantic-memory-extraction.v1",
                List.of(candidate(0.82, 0.88, false))
        );

        TerminalMemoryExtractionResult first = service.extractTerminalRun("run-1", "alice");
        TerminalMemoryExtractionResult second = service.extractTerminalRun("run-1", "alice");

        assertThat(first.semanticWritten()).isEqualTo(1);
        assertThat(second.semanticWritten()).isEqualTo(1);
        assertThat(recordStore.findActiveByScope(MemoryScope.user("api", "session-1", "alice"), null, 10))
                .hasSize(1);
        assertThat(outboxStore.findAll()).hasSize(1);
    }

    @Test
    void terminalReflectionWritesEpisodicCandidateWithGroundedEvidence() {
        when(messageEventService.listRequestEvents("run-1", "alice", null, null, 12, true))
                .thenReturn(events());
        extractor.result = new SemanticMemoryExtractionResult(
                "springclaw.semantic-memory-extraction.v1",
                List.of()
        );
        reflectionGenerator.result = new TerminalReflectionResult(
                "springclaw.terminal-reflection.v1",
                "SUCCESS",
                "When the user asks for PR review handoff, verify PR state before planning.",
                "future GitHub PR handoff tasks",
                "",
                List.of("run:run-1", "event:chat:run-1:user"),
                0.78
        );

        TerminalMemoryExtractionResult result = service.extractTerminalRun("run-1", "alice");

        assertThat(result.reflectionWritten()).isEqualTo(1);
        assertThat(recordStore.findByStatus(MemoryStatus.CANDIDATE, 10))
                .singleElement()
                .satisfies(record -> {
                    assertThat(record.memoryType()).isEqualTo(MemoryType.EPISODIC);
                    assertThat(record.sourceKind()).isEqualTo("RUN_REFLECTION");
                    assertThat(record.sourceIdentity()).isEqualTo("run-1");
                    assertThat(record.extractionPolicyVersion()).isEqualTo("terminal-reflection-v1");
                    assertThat(record.evidenceRefs()).contains("run:run-1", "event:chat:run-1:user");
                });
    }

    private static SemanticMemoryCandidate candidate(double importance, double confidence, boolean hypothetical) {
        return new SemanticMemoryCandidate(
                "USER_PREFERENCE",
                "User prefers concise Chinese progress summaries.",
                "user",
                "PERSONAL_USER",
                importance,
                confidence,
                List.of("chat:run-1:user", "chat:run-1:assistant:terminal"),
                "run-1",
                "The user explicitly asked for concise Chinese progress updates.",
                hypothetical
        );
    }

    private static List<MessageEvent> events() {
        return List.of(
                event(10L, "session-1", "api", "alice", "USER", "CHAT", "run-1",
                        "chat:run-1:user", "以后进度用短中文说"),
                event(11L, "session-1", "api", "alice", "ASSISTANT", "CHAT", "run-1",
                        "chat:run-1:assistant:terminal", "收到，以后我会用短中文进度。")
        );
    }

    private static MessageEvent event(long id,
                                      String sessionKey,
                                      String channel,
                                      String userId,
                                      String role,
                                      String eventType,
                                      String requestId,
                                      String eventKey,
                                      String content) {
        MessageEvent event = new MessageEvent();
        event.setId(id);
        event.setSessionKey(sessionKey);
        event.setChannel(channel);
        event.setUserId(userId);
        event.setRole(role);
        event.setEventType(eventType);
        event.setRequestId(requestId);
        event.setEventKey(eventKey);
        event.setContent(content);
        return event;
    }

    private static final class FakeExtractor implements SemanticMemoryExtractor {
        private SemanticMemoryExtractionResult result =
                new SemanticMemoryExtractionResult("springclaw.semantic-memory-extraction.v1", List.of());

        @Override
        public SemanticMemoryExtractionResult extract(TerminalMemoryExtractionContext context) {
            return result;
        }
    }

    private static final class FakeJudge implements SemanticMemoryJudge {
        private final List<SemanticMemoryCandidate> judged = new ArrayList<>();
        private final SemanticMemoryJudgeVerdict verdict;

        private FakeJudge(SemanticMemoryJudgeVerdict verdict) {
            this.verdict = verdict;
        }

        @Override
        public SemanticMemoryJudgeVerdict judge(SemanticMemoryCandidate candidate,
                                                TerminalMemoryExtractionContext context) {
            judged.add(candidate);
            return verdict;
        }
    }

    private static final class FakeReflectionGenerator implements TerminalReflectionGenerator {
        private TerminalReflectionResult result;

        private FakeReflectionGenerator(TerminalReflectionResult result) {
            this.result = result;
        }

        @Override
        public TerminalReflectionResult reflect(TerminalMemoryExtractionContext context) {
            return result;
        }
    }
}
