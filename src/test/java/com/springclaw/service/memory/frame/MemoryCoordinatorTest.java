package com.springclaw.service.memory.frame;

import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryFrameOmission;
import com.springclaw.runtime.memory.contract.MemoryFrameSourceKind;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.contract.ProjectMemoryItem;
import com.springclaw.runtime.memory.contract.ShortTermMemoryEntry;
import com.springclaw.runtime.memory.port.ProjectMemorySource;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.runtime.memory.store.InMemoryMemoryRecordStore;
import com.springclaw.runtime.memory.store.InMemoryShortTermMemoryStore;
import com.springclaw.service.event.MessageEventService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryCoordinatorTest {

    private static final Instant T0 = Instant.parse("2026-06-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    @Test
    void assemblesAllLayersFromAuthorizedSources() {
        MemoryScope scope = MemoryScope.from(personalClaim("alice"));
        InMemoryMemoryRecordStore recordStore = new InMemoryMemoryRecordStore();
        InMemoryShortTermMemoryStore shortTermStore = new InMemoryShortTermMemoryStore();
        ProjectMemorySource projectSource = ignored -> List.of(projectItem(
                "project-brief.md",
                ProjectMemoryItem.ReviewStatus.APPROVED
        ));

        shortTermStore.append(scope, shortTerm(1, "chat:req:user", "USER", "hello"));
        shortTermStore.append(scope, shortTerm(2, "chat:req:assistant:terminal", "ASSISTANT", "answer"));
        recordStore.insert(record(scope, "logical-semantic", "version-semantic", MemoryType.SEMANTIC, "hash-semantic"));
        recordStore.insert(record(scope, "logical-episodic", "version-episodic", MemoryType.EPISODIC, "hash-episodic"));
        recordStore.insert(record(scope, "logical-procedural", "version-procedural", MemoryType.PROCEDURAL, "hash-procedural"));

        MemoryCoordinator coordinator = new MemoryCoordinator(
                recordStore,
                () -> shortTermStore,
                projectSource,
                CLOCK,
                6000,
                20
        );

        MemoryFrameResult result = coordinator.retrieve(new MemoryFrameRequest(
                "run-1", scope, "current question"
        ));

        assertThat(result.frame().shortTermTurns()).hasSize(2);
        assertThat(result.frame().semanticFacts()).hasSize(1);
        assertThat(result.frame().episodicItems()).hasSize(1);
        assertThat(result.frame().proceduralRules()).hasSize(1);
        assertThat(result.frame().projectItems()).hasSize(1);
        assertThat(result.trace().includedCounts())
                .containsEntry("shortTerm", 2)
                .containsEntry("semantic", 1)
                .containsEntry("episodic", 1)
                .containsEntry("procedural", 1)
                .containsEntry("project", 1);
        assertThat(result.frame().frameHash()).hasSize(64);
        assertThat(result.trace().frameHash()).isEqualTo(result.frame().frameHash());
    }

    @Test
    void excludesRecordsOutsideAuthorizedScope() {
        MemoryScope alice = MemoryScope.from(personalClaim("alice"));
        MemoryScope bob = MemoryScope.from(personalClaim("bob"));
        InMemoryMemoryRecordStore recordStore = new InMemoryMemoryRecordStore();
        recordStore.insert(record(bob, "logical-bob", "version-bob", MemoryType.SEMANTIC, "hash-bob"));

        MemoryCoordinator coordinator = new MemoryCoordinator(
                recordStore,
                () -> null,
                ignored -> List.of(),
                CLOCK,
                6000,
                20
        );

        MemoryFrameResult result = coordinator.retrieve(new MemoryFrameRequest(
                "run-1", alice, "question"
        ));

        assertThat(result.frame().semanticFacts()).isEmpty();
    }

    @Test
    void includesCrossSessionUserMemoriesForPersonalScope() {
        MemoryScope currentSession = MemoryScope.from(personalClaim("alice"));
        MemoryScope userScope = MemoryScope.user("api", "session-previous", "alice");
        InMemoryMemoryRecordStore recordStore = new InMemoryMemoryRecordStore();
        recordStore.insert(record(userScope, "logical-user-pref", "version-user-pref", MemoryType.SEMANTIC, "hash-user-pref"));

        MemoryCoordinator coordinator = new MemoryCoordinator(
                recordStore,
                () -> null,
                ignored -> List.of(),
                CLOCK,
                6000,
                20
        );

        MemoryFrameResult result = coordinator.retrieve(new MemoryFrameRequest(
                "run-1", currentSession, "question"
        ));

        assertThat(result.frame().semanticFacts())
                .extracting(item -> item.sourceId())
                .containsExactly("version-user-pref");
    }

    @Test
    void deduplicatesByContentHashAndRecordsOmission() {
        MemoryScope scope = MemoryScope.from(personalClaim("alice"));
        InMemoryMemoryRecordStore recordStore = new InMemoryMemoryRecordStore();
        recordStore.insert(record(scope, "logical-a", "version-a", MemoryType.SEMANTIC, "same-hash"));
        recordStore.insert(record(scope, "logical-b", "version-b", MemoryType.EPISODIC, "same-hash"));

        MemoryCoordinator coordinator = new MemoryCoordinator(
                recordStore,
                () -> null,
                ignored -> List.of(),
                CLOCK,
                6000,
                20
        );

        MemoryFrameResult result = coordinator.retrieve(new MemoryFrameRequest(
                "run-1", scope, "question"
        ));

        assertThat(result.frame().semanticFacts().size()
                + result.frame().episodicItems().size()).isEqualTo(1);
        assertThat(result.frame().omissions())
                .extracting(MemoryFrameOmission::category)
                .contains(MemoryFrameOmission.Category.DUPLICATE_CONTENT);
        assertThat(result.trace().omissionCounts())
                .containsEntry(MemoryFrameOmission.Category.DUPLICATE_CONTENT, 1);
    }

    @Test
    void missingShortTermStoreReturnsFrameWithSourceUnavailableOmission() {
        MemoryScope scope = MemoryScope.from(personalClaim("alice"));
        MemoryCoordinator coordinator = new MemoryCoordinator(
                new InMemoryMemoryRecordStore(),
                () -> null,
                ignored -> List.of(),
                CLOCK,
                6000,
                20
        );

        MemoryFrameResult result = coordinator.retrieve(new MemoryFrameRequest(
                "run-1", scope, "question"
        ));

        assertThat(result.frame().shortTermTurns()).isEmpty();
        assertThat(result.frame().omissions())
                .extracting(MemoryFrameOmission::category)
                .contains(MemoryFrameOmission.Category.SOURCE_UNAVAILABLE);
    }

    @Test
    void fallsBackToPersistedChatEventsWhenShortTermStoreIsEmpty() {
        MemoryScope scope = MemoryScope.from(personalClaim("alice"));
        InMemoryShortTermMemoryStore emptyShortTermStore = new InMemoryShortTermMemoryStore();
        MessageEventService messageEventService = mock(MessageEventService.class);
        when(messageEventService.listSessionEvents("session-1", "alice", null, "CHAT", 40, true))
                .thenReturn(List.of(
                        event(10L, "chat:run-old:user", "USER", "上一轮说我的昵称是小韩", "run-old"),
                        event(11L, "chat:run-old:assistant:terminal", "ASSISTANT", "记住了，你的昵称是小韩。", "run-old")
                ));
        MemoryCoordinator coordinator = new MemoryCoordinator(
                new InMemoryMemoryRecordStore(),
                () -> emptyShortTermStore,
                ignored -> List.of(),
                messageEventService,
                CLOCK,
                6000,
                20
        );

        MemoryFrameResult result = coordinator.retrieve(new MemoryFrameRequest(
                "run-1", scope, "我刚才说我的昵称是什么？"
        ));

        assertThat(result.frame().shortTermTurns())
                .extracting(item -> item.content())
                .containsExactly(
                        "上一轮说我的昵称是小韩",
                        "记住了，你的昵称是小韩。"
                );
        assertThat(result.trace().includedCounts()).containsEntry("shortTerm", 2);
    }

    @Test
    void filtersCandidateAndRejectedProjectMemory() {
        MemoryScope scope = MemoryScope.from(personalClaim("alice"));
        ProjectMemorySource projectSource = ignored -> List.of(
                projectItem("project-brief.md", ProjectMemoryItem.ReviewStatus.APPROVED),
                projectItem("candidate.md", ProjectMemoryItem.ReviewStatus.CANDIDATE),
                projectItem("rejected.md", ProjectMemoryItem.ReviewStatus.REJECTED)
        );
        MemoryCoordinator coordinator = new MemoryCoordinator(
                new InMemoryMemoryRecordStore(),
                () -> null,
                projectSource,
                CLOCK,
                6000,
                20
        );

        MemoryFrameResult result = coordinator.retrieve(new MemoryFrameRequest(
                "run-1", scope, "question"
        ));

        assertThat(result.frame().projectItems())
                .extracting(item -> item.sourceId())
                .containsExactly("project-brief.md");
        assertThat(result.frame().omissions())
                .extracting(MemoryFrameOmission::category)
                .contains(MemoryFrameOmission.Category.AUTHORIZATION_SCOPE_MISMATCH);
    }

    @Test
    void knowledgeSourceProjectMemoryEntersProjectLayerNotMemoryRecordLayer() {
        MemoryScope scope = MemoryScope.from(personalClaim("alice"));
        ProjectMemorySource projectSource = ignored -> List.of(new ProjectMemoryItem(
                "knowledge-source",
                ProjectMemoryItem.SourceType.KNOWLEDGE_SOURCE,
                "approved runtime knowledge",
                "knowledge-source-hash",
                ProjectMemoryItem.ReviewStatus.APPROVED,
                T0
        ));
        MemoryCoordinator coordinator = new MemoryCoordinator(
                new InMemoryMemoryRecordStore(),
                () -> null,
                projectSource,
                CLOCK,
                6000,
                20
        );

        MemoryFrameResult result = coordinator.retrieve(new MemoryFrameRequest(
                "run-1", scope, "question"
        ));

        assertThat(result.frame().projectItems()).hasSize(1);
        assertThat(result.frame().projectItems().get(0).sourceKind())
                .isEqualTo(MemoryFrameSourceKind.PROJECT_MARKDOWN);
        assertThat(result.frame().semanticFacts()).isEmpty();
        assertThat(result.trace().includedCounts()).containsEntry("project", 1);
    }

    private static SessionAccessClaim personalClaim(String userId) {
        return SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                "session-1",
                userId
        );
    }

    private static ShortTermMemoryEntry shortTerm(
            long eventId,
            String eventKey,
            String role,
            String content
    ) {
        return new ShortTermMemoryEntry(
                eventId,
                eventKey,
                "run-1",
                role,
                "alice",
                content,
                T0.plusSeconds(eventId)
        );
    }

    private static MemoryRecordVersion record(
            MemoryScope scope,
            String logicalMemoryId,
            String versionId,
            MemoryType type,
            String contentHash
    ) {
        return new MemoryRecordVersion(
                null,
                logicalMemoryId,
                versionId,
                type,
                scope.scopeType(),
                scope.scopeId(),
                scope.authorizationPrincipal(),
                "content " + versionId,
                contentHash,
                "summary " + versionId,
                "run-1",
                List.of("event-" + versionId),
                List.of("evidence-" + versionId),
                List.of(),
                0.7,
                0.8,
                MemoryStatus.ACTIVE,
                T0,
                null,
                null,
                1,
                1,
                "TEST",
                "source-" + versionId,
                "policy-v1",
                1L,
                T0,
                T0,
                false
        );
    }

    private static ProjectMemoryItem projectItem(
            String sourcePath,
            ProjectMemoryItem.ReviewStatus status
    ) {
        return new ProjectMemoryItem(
                sourcePath,
                ProjectMemoryItem.SourceType.PROJECT_BRIEF,
                "project content " + sourcePath,
                "project-hash-" + sourcePath,
                status,
                T0
        );
    }

    private static MessageEvent event(long id,
                                      String eventKey,
                                      String role,
                                      String content,
                                      String requestId) {
        MessageEvent event = new MessageEvent();
        event.setId(id);
        event.setSessionKey("session-1");
        event.setChannel("api");
        event.setUserId("alice");
        event.setRole(role);
        event.setEventType("CHAT");
        event.setRequestId(requestId);
        event.setEventKey(eventKey);
        event.setContent(content);
        event.setCreateTime(LocalDateTime.ofInstant(T0.plusSeconds(id), ZoneOffset.UTC));
        return event;
    }
}
