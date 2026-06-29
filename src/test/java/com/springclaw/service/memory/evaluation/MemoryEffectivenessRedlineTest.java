package com.springclaw.service.memory.evaluation;

import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryFrameOmission;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.store.InMemoryMemoryIndexOutboxStore;
import com.springclaw.runtime.memory.store.InMemoryMemoryRecordStore;
import com.springclaw.service.memory.MemoryManagementService;
import com.springclaw.service.memory.MemoryVersionFactory;
import com.springclaw.service.memory.MemoryWriteCommand;
import com.springclaw.service.memory.frame.MemoryCoordinator;
import com.springclaw.service.memory.frame.MemoryFrameRequest;
import com.springclaw.service.memory.frame.MemoryFrameResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryEffectivenessRedlineTest {

    private static final Instant T0 = Instant.parse("2026-06-29T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    @Test
    void recallsCrossSessionUserPreferenceAndPreservesEvidence() {
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
        MemoryScope newSession = MemoryScope.from(personalClaim("alice", "new-session"));

        MemoryFrameResult result = fixture.retrieve(
                newSession,
                "How should you update Alice about progress?"
        );

        assertThat(result.frame().semanticFacts())
                .extracting(item -> item.content())
                .containsExactly("Alice prefers short Chinese progress summaries.");
        assertThat(result.frame().semanticFacts().get(0).evidenceRefs())
                .containsExactly("message_event:run-old:user");
    }

    @Test
    void conflictReplacementRecallsOnlyLatestActiveFact() {
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

        assertThat(result.frame().semanticFacts())
                .extracting(item -> item.content())
                .containsExactly("Alice prefers Chinese answers.");
    }

    @Test
    void rejectsIrrelevantMemoriesAndKeepsRelevantOnes() {
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

        assertThat(result.frame().semanticFacts())
                .extracting(item -> item.content())
                .containsExactly("Alice prefers short Chinese progress summaries.");
        assertThat(result.frame().omissions())
                .anySatisfy(omission -> {
                    assertThat(omission.category()).isEqualTo(MemoryFrameOmission.Category.LOW_SCORE);
                    assertThat(omission.reason()).contains("irrelevant");
                });
    }

    @Test
    void selectiveForgettingExcludesRejectedMemory() {
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
        fixture.service.transition(active.memoryVersionId(), MemoryStatus.ACTIVE, MemoryStatus.REJECTED, T0.plusSeconds(10));

        MemoryFrameResult result = fixture.retrieve(
                MemoryScope.from(personalClaim("alice", "new-session")),
                "How should you update Alice?"
        );

        assertThat(result.frame().semanticFacts()).isEmpty();
    }

    @Test
    void tokenBudgetSaturationTruncatesLowScoreSemanticItems() {
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
        assertThat(totalChars).isLessThanOrEqualTo(200);
        assertThat(result.frame().omissions())
                .extracting(MemoryFrameOmission::category)
                .contains(MemoryFrameOmission.Category.BUDGET_TRUNCATED);
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
        private final MemoryManagementService service =
                new MemoryManagementService(
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

        private static ObjectProvider<com.springclaw.runtime.memory.port.ShortTermMemoryStore> emptyShortTermProvider() {
            return new ObjectProvider<>() {
                @Override
                public com.springclaw.runtime.memory.port.ShortTermMemoryStore getObject(Object... args) {
                    throw new IllegalStateException("No short-term store");
                }

                @Override
                public com.springclaw.runtime.memory.port.ShortTermMemoryStore getIfAvailable() {
                    return null;
                }

                @Override
                public com.springclaw.runtime.memory.port.ShortTermMemoryStore getIfUnique() {
                    return null;
                }

                @Override
                public com.springclaw.runtime.memory.port.ShortTermMemoryStore getObject() {
                    throw new IllegalStateException("No short-term store");
                }

                @Override
                public Iterator<com.springclaw.runtime.memory.port.ShortTermMemoryStore> iterator() {
                    return List.<com.springclaw.runtime.memory.port.ShortTermMemoryStore>of().iterator();
                }

                @Override
                public Stream<com.springclaw.runtime.memory.port.ShortTermMemoryStore> stream() {
                    return Stream.empty();
                }
            };
        }
    }
}
