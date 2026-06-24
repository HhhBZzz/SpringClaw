# Unified Runtime Phase 3A2 MemoryFrame Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build canonical `MemoryFrame` retrieval as disabled-by-default shadow infrastructure without changing active prompt, Advisor, route, answer, stream, or tool-safety behavior.

**Architecture:** Phase 3A2 adds immutable frame contracts, deterministic hashing/budgeting, a `MemoryCoordinator` that reads Phase 3A1 sources, and a read-only shadow comparison against the legacy context view. `ContextAssembler`, `SemanticMemoryAdvisor`, `MessageChatMemoryAdvisor`, and `ContextSnapshotFactory` activation remain out of scope.

**Tech Stack:** Java 17, Spring Boot 3.5, JUnit 5, AssertJ, Mockito, existing Phase 3A1 memory ports/stores.

---

## Hard boundaries

Do not modify these files in Phase 3A2 implementation:

- `src/main/java/com/springclaw/service/context/ContextAssembler.java`
- `src/main/java/com/springclaw/service/chat/impl/SemanticMemoryAdvisor.java`
- `src/main/java/com/springclaw/service/chat/impl/ConversationAdvisorSupport.java`
- `src/main/java/com/springclaw/runtime/contract/ContextSnapshot.java`
- `src/main/java/com/springclaw/runtime/lifecycle/RunCoordinator.java`
- any engine implementation except tests proving no behavior change

Allowed exceptions: tests may reference these classes to prove compatibility.

## Task 1: Add immutable MemoryFrame contracts

**Owner:** Codex

**Files:**

- Create: `src/main/java/com/springclaw/runtime/memory/contract/MemoryFrameLayer.java`
- Create: `src/main/java/com/springclaw/runtime/memory/contract/MemoryFrameSourceKind.java`
- Create: `src/main/java/com/springclaw/runtime/memory/contract/MemoryFrameItem.java`
- Create: `src/main/java/com/springclaw/runtime/memory/contract/MemoryFrameOmission.java`
- Create: `src/main/java/com/springclaw/runtime/memory/contract/MemoryRetrievalTrace.java`
- Create: `src/main/java/com/springclaw/runtime/memory/contract/MemoryFrame.java`
- Test: `src/test/java/com/springclaw/runtime/memory/MemoryFrameContractTest.java`

- [ ] **Step 1: Write failing contract tests**

Add `MemoryFrameContractTest` with these tests:

```java
@Test
void frameCopiesCollectionsAndRequiresStableIdentity() {
    MemoryScope scope = MemoryScope.from(personalClaim());
    List<MemoryFrameItem> shortTerm = new ArrayList<>();
    shortTerm.add(item("event-1", MemoryFrameLayer.SHORT_TERM));

    MemoryFrame frame = new MemoryFrame(
            "run-1",
            scope,
            shortTerm,
            List.of(),
            List.of(item("semantic-1", MemoryFrameLayer.SEMANTIC_FACT)),
            List.of(),
            List.of(),
            Map.of("source", "test"),
            List.of(),
            Instant.parse("2026-06-24T00:00:00Z"),
            "hash-1"
    );

    shortTerm.add(item("event-2", MemoryFrameLayer.SHORT_TERM));

    assertThat(frame.shortTermTurns())
            .extracting(MemoryFrameItem::sourceId)
            .containsExactly("event-1");
    assertThatThrownBy(() -> frame.shortTermTurns().add(
            item("event-3", MemoryFrameLayer.SHORT_TERM)
    )).isInstanceOf(UnsupportedOperationException.class);
}

@Test
void frameRejectsBlankRunScopeAndHash() {
    MemoryScope scope = MemoryScope.from(personalClaim());

    assertThatThrownBy(() -> new MemoryFrame(
            "", scope, List.of(), List.of(), List.of(), List.of(), List.of(),
            Map.of(), List.of(), Instant.parse("2026-06-24T00:00:00Z"), "hash"
    )).hasMessageContaining("runId");
    assertThatThrownBy(() -> new MemoryFrame(
            "run-1", scope, List.of(), List.of(), List.of(), List.of(), List.of(),
            Map.of(), List.of(), Instant.parse("2026-06-24T00:00:00Z"), ""
    )).hasMessageContaining("frameHash");
}

@Test
void traceDoesNotExposeOmittedPrivateContent() {
    MemoryRetrievalTrace trace = new MemoryRetrievalTrace(
            "run-1",
            MemoryScope.from(personalClaim()),
            "hash-1",
            Map.of("shortTerm", 3),
            Map.of("shortTerm", 2),
            Map.of(MemoryFrameOmission.Category.BUDGET_TRUNCATED, 1),
            List.of("vector unavailable"),
            Instant.parse("2026-06-24T00:00:00Z")
    );

    assertThat(trace.omissionCounts())
            .containsEntry(MemoryFrameOmission.Category.BUDGET_TRUNCATED, 1);
    assertThat(trace.sourceWarnings()).containsExactly("vector unavailable");
}
```

Add these private helpers in the same test class:

```java
private static SessionAccessClaim personalClaim() {
    return SessionAccessClaim.personal(
            SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
            "api",
            "session-1",
            "alice"
    );
}

private static MemoryFrameItem item(String sourceId, MemoryFrameLayer layer) {
    return new MemoryFrameItem(
            sourceId,
            MemoryFrameSourceKind.MEMORY_RECORD,
            layer,
            "logical-" + sourceId,
            "version-" + sourceId,
            layer == MemoryFrameLayer.EPISODIC
                    ? MemoryType.EPISODIC
                    : MemoryType.SEMANTIC,
            MemoryScopeType.PERSONAL_SESSION,
            "api:session-1:alice",
            "content " + sourceId,
            "hash-" + sourceId,
            List.of("event-" + sourceId),
            0.7,
            0.8,
            0.9,
            1,
            Instant.parse("2026-06-24T00:00:00Z")
    );
}
```

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=MemoryFrameContractTest test
```

Expected: compilation fails because `MemoryFrame`, `MemoryFrameItem`, and related contract types do not exist.

- [ ] **Step 3: Implement minimal contract types**

Create `MemoryFrameLayer`:

```java
package com.springclaw.runtime.memory.contract;

public enum MemoryFrameLayer {
    WORKING_MEMORY,
    SHORT_TERM,
    EPISODIC,
    SEMANTIC_FACT,
    PROCEDURAL_RULE,
    PROJECT
}
```

Create `MemoryFrameSourceKind`:

```java
package com.springclaw.runtime.memory.contract;

public enum MemoryFrameSourceKind {
    MESSAGE_EVENT,
    MEMORY_RECORD,
    PROJECT_MARKDOWN,
    AGENT_LEARNING,
    VECTOR_CANDIDATE
}
```

Create `MemoryFrameOmission`:

```java
package com.springclaw.runtime.memory.contract;

import java.util.Objects;

public record MemoryFrameOmission(
        Category category,
        MemoryFrameLayer layer,
        String sourceId,
        String reason
) {
    public enum Category {
        BUDGET_TRUNCATED,
        AUTHORIZATION_SCOPE_MISMATCH,
        CONFLICT,
        EXPIRED,
        DUPLICATE_CONTENT,
        LOW_SCORE,
        UNSUPPORTED_TYPE,
        STALE_VECTOR_HIT,
        VECTOR_UNAVAILABLE,
        SOURCE_UNAVAILABLE
    }

    public MemoryFrameOmission {
        category = Objects.requireNonNull(category, "category");
        layer = Objects.requireNonNull(layer, "layer");
        sourceId = optionalText(sourceId);
        reason = optionalText(reason);
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
```

Create `MemoryFrameItem` with required fields from the spec:

```java
package com.springclaw.runtime.memory.contract;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record MemoryFrameItem(
        String sourceId,
        MemoryFrameSourceKind sourceKind,
        MemoryFrameLayer layer,
        String logicalMemoryId,
        String memoryVersionId,
        MemoryType memoryType,
        MemoryScopeType scopeType,
        String scopeId,
        String content,
        String contentHash,
        List<String> evidenceRefs,
        double importance,
        double confidence,
        double score,
        int version,
        Instant updatedAt
) {
    public MemoryFrameItem {
        sourceId = requireText(sourceId, "sourceId");
        sourceKind = Objects.requireNonNull(sourceKind, "sourceKind");
        layer = Objects.requireNonNull(layer, "layer");
        logicalMemoryId = optionalText(logicalMemoryId);
        memoryVersionId = optionalText(memoryVersionId);
        scopeId = optionalText(scopeId);
        content = requireText(content, "content");
        contentHash = requireText(contentHash, "contentHash");
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        requireScore(importance, "importance");
        requireScore(confidence, "confidence");
        requireScore(score, "score");
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static void requireScore(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(field + " must be between 0 and 1");
        }
    }
}
```

Create `MemoryRetrievalTrace` and `MemoryFrame` with immutable collection copies and required text validation.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
mvn -q -Dtest=MemoryFrameContractTest test
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/springclaw/runtime/memory/contract \
  src/test/java/com/springclaw/runtime/memory/MemoryFrameContractTest.java
git commit -m "feat: add memory frame contracts"
```

---

## Task 2: Add deterministic hashing and budget utilities

**Owner:** Codex

**Files:**

- Create: `src/main/java/com/springclaw/service/memory/frame/MemoryFrameHasher.java`
- Create: `src/main/java/com/springclaw/service/memory/frame/MemoryFrameBudget.java`
- Test: `src/test/java/com/springclaw/service/memory/frame/MemoryFrameHasherTest.java`
- Test: `src/test/java/com/springclaw/service/memory/frame/MemoryFrameBudgetTest.java`

- [ ] **Step 1: Write failing hasher tests**

```java
@Test
void hashIsStableAndExcludesCapturedAt() {
    MemoryFrame first = frame("run-1", Instant.parse("2026-06-24T00:00:00Z"));
    MemoryFrame second = frame("run-1", Instant.parse("2026-06-24T00:10:00Z"));

    assertThat(MemoryFrameHasher.hash(first))
            .isEqualTo(MemoryFrameHasher.hash(second));
}

@Test
void hashChangesWhenOrderedItemIdentityChanges() {
    MemoryFrame first = frameWithItems("run-1", List.of(item("a"), item("b")));
    MemoryFrame second = frameWithItems("run-1", List.of(item("b"), item("a")));

    assertThat(MemoryFrameHasher.hash(first))
            .isNotEqualTo(MemoryFrameHasher.hash(second));
}
```

- [ ] **Step 2: Write failing budget tests**

```java
@Test
void layerBudgetsRespectDefaultSharesAndFiftyPercentCap() {
    MemoryFrameBudget budget = MemoryFrameBudget.of(6000);

    assertThat(budget.limitFor(MemoryFrameLayer.SHORT_TERM)).isEqualTo(2100);
    assertThat(budget.limitFor(MemoryFrameLayer.EPISODIC)).isEqualTo(900);
    assertThat(budget.limitFor(MemoryFrameLayer.SEMANTIC_FACT)).isEqualTo(1200);
    assertThat(budget.limitFor(MemoryFrameLayer.PROJECT)).isEqualTo(1200);
    assertThat(budget.limitFor(MemoryFrameLayer.PROCEDURAL_RULE)).isEqualTo(600);
    assertThat(budget.maxLayerLimit()).isEqualTo(3000);
}

@Test
void budgetRejectsTooSmallOrNonPositiveTotals() {
    assertThatThrownBy(() -> MemoryFrameBudget.of(0))
            .hasMessageContaining("total");
}
```

- [ ] **Step 3: Verify RED**

Run:

```bash
mvn -q -Dtest=MemoryFrameHasherTest,MemoryFrameBudgetTest test
```

Expected: compilation fails because utility classes do not exist.

- [ ] **Step 4: Implement utilities**

Implement `MemoryFrameBudget.of(int totalChars)` with:

- min total `1000`;
- default shares: short-term 35%, episodic 15%, semantic 20%, project 20%, procedural 10%;
- `maxLayerLimit()` = 50% of total;
- deterministic integer rounding using `Math.floor`.

Implement `MemoryFrameHasher.hash(MemoryFrame frame)` using SHA-256 over:

- runId;
- scope type and scope id;
- ordered item `sourceId`, `sourceKind`, `layer`, `memoryVersionId`, `contentHash`, `score`, `version`;
- omission category/layer/sourceId/reason;
- source summary entries sorted by key.

Do not include `capturedAt`.

- [ ] **Step 5: Verify GREEN**

Run:

```bash
mvn -q -Dtest=MemoryFrameHasherTest,MemoryFrameBudgetTest,MemoryFrameContractTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/service/memory/frame \
  src/test/java/com/springclaw/service/memory/frame
git commit -m "feat: add memory frame hashing and budgets"
```

---

## Task 3: Implement MemoryCoordinator from Phase 3A1 sources

**Owner:** Claude or Codex subagent

**Files:**

- Create: `src/main/java/com/springclaw/service/memory/frame/MemoryCoordinator.java`
- Create: `src/main/java/com/springclaw/service/memory/frame/MemoryFrameRequest.java`
- Create: `src/main/java/com/springclaw/service/memory/frame/MemoryFrameResult.java`
- Test: `src/test/java/com/springclaw/service/memory/frame/MemoryCoordinatorTest.java`

- [ ] **Step 1: Write failing coordinator tests**

Add tests for:

```java
@Test
void assemblesAllLayersFromAuthorizedSources() {
    MemoryScope scope = MemoryScope.from(personalClaim("alice"));
    InMemoryMemoryRecordStore recordStore = new InMemoryMemoryRecordStore();
    InMemoryShortTermMemoryStore shortTermStore = new InMemoryShortTermMemoryStore();
    ProjectMemorySource projectSource = ignored -> List.of(projectItem("project-brief.md"));

    shortTermStore.append(scope, shortTerm(1, "chat:req:user", "USER", "hello"));
    shortTermStore.append(scope, shortTerm(2, "chat:req:assistant:terminal", "ASSISTANT", "answer"));
    recordStore.insert(record(scope, "logical-semantic", "version-semantic", MemoryType.SEMANTIC));
    recordStore.insert(record(scope, "logical-episodic", "version-episodic", MemoryType.EPISODIC));
    recordStore.insert(record(scope, "logical-procedural", "version-procedural", MemoryType.PROCEDURAL));

    MemoryCoordinator coordinator = new MemoryCoordinator(
            recordStore,
            () -> shortTermStore,
            projectSource,
            Clock.fixed(Instant.parse("2026-06-24T00:00:00Z"), ZoneOffset.UTC),
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
}
```

Add these helpers in `MemoryCoordinatorTest`:

```java
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
            Instant.parse("2026-06-24T00:00:00Z").plusSeconds(eventId)
    );
}

private static MemoryRecordVersion record(
        MemoryScope scope,
        String logicalMemoryId,
        String versionId,
        MemoryType type
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
            "hash-" + versionId,
            "summary " + versionId,
            "run-1",
            List.of("event-" + versionId),
            List.of("evidence-" + versionId),
            List.of(),
            0.7,
            0.8,
            MemoryStatus.ACTIVE,
            Instant.parse("2026-06-24T00:00:00Z"),
            null,
            null,
            1,
            1,
            "TEST",
            "source-" + versionId,
            "policy-v1",
            1L,
            Instant.parse("2026-06-24T00:00:00Z"),
            Instant.parse("2026-06-24T00:00:00Z"),
            false
    );
}

private static ProjectMemoryItem projectItem(String sourcePath) {
    return new ProjectMemoryItem(
            sourcePath,
            ProjectMemoryItem.SourceType.PROJECT_BRIEF,
            "project content",
            "project-hash",
            ProjectMemoryItem.ReviewStatus.APPROVED,
            Instant.parse("2026-06-24T00:00:00Z")
    );
}
```

Add tests proving:

- a record with mismatched `scopeId` is not included;
- duplicate `contentHash` appears once and produces `DUPLICATE_CONTENT`;
- missing short-term store returns a frame with `SOURCE_UNAVAILABLE`;
- project `CANDIDATE` and `REJECTED` items are omitted.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=MemoryCoordinatorTest test
```

Expected: compilation fails because coordinator classes do not exist.

- [ ] **Step 3: Implement request/result records**

`MemoryFrameRequest`:

```java
public record MemoryFrameRequest(
        String runId,
        MemoryScope scope,
        String question
) {
    public MemoryFrameRequest {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        scope = Objects.requireNonNull(scope, "scope");
        question = question == null ? "" : question.trim();
    }
}
```

`MemoryFrameResult`:

```java
public record MemoryFrameResult(
        MemoryFrame frame,
        MemoryRetrievalTrace trace
) {
    public MemoryFrameResult {
        frame = Objects.requireNonNull(frame, "frame");
        trace = Objects.requireNonNull(trace, "trace");
    }
}
```

- [ ] **Step 4: Implement coordinator retrieval**

Constructor dependencies:

```java
MemoryCoordinator(
        MemoryRecordStore recordStore,
        ObjectProvider<ShortTermMemoryStore> shortTermStoreProvider,
        ProjectMemorySource projectMemorySource,
        Clock clock,
        int maxChars,
        int traceMaxWarnings
)
```

For tests, add package-private constructor accepting `Supplier<ShortTermMemoryStore>`.

Retrieval rules:

- derive lists from one accepted `MemoryScope`;
- call `shortTermStore.readRecent(scope, budget.shortTermLimit())` if store exists;
- call `recordStore.findActiveByScope(scope, Set.of(MemoryType.EPISODIC, MemoryType.SEMANTIC, MemoryType.PROCEDURAL), 500)`;
- call `projectMemorySource.read(scope)`;
- map `MemoryType.EPISODIC` to `episodicItems`;
- map `MemoryType.SEMANTIC` to `semanticFacts`;
- map `MemoryType.PROCEDURAL` to `proceduralRules`;
- map project items to `projectItems` only when review status is `APPROVED` or `ACTIVE`;
- deduplicate by `contentHash`;
- compute frame hash after building the frame, then create a final frame with that hash.

- [ ] **Step 5: Verify GREEN**

Run:

```bash
mvn -q -Dtest=MemoryCoordinatorTest,MemoryFrameHasherTest,MemoryFrameBudgetTest,MemoryFrameContractTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/service/memory/frame \
  src/test/java/com/springclaw/service/memory/frame
git commit -m "feat: assemble canonical memory frames"
```

---

## Task 4: Add disabled-by-default Spring wiring and shadow comparison

**Owner:** Codex

**Files:**

- Create: `src/main/java/com/springclaw/config/MemoryFrameConfig.java`
- Create: `src/main/java/com/springclaw/service/memory/frame/MemoryFrameShadowComparison.java`
- Create: `src/main/java/com/springclaw/service/memory/frame/MemoryFrameShadowComparator.java`
- Modify: `src/main/resources/application.yml`
- Modify: `.env.example`
- Test: `src/test/java/com/springclaw/service/memory/frame/MemoryFrameShadowComparisonTest.java`
- Test: `src/test/java/com/springclaw/service/memory/frame/MemoryFrameSpringWiringTest.java`

- [ ] **Step 1: Write failing wiring tests**

```java
@Test
void memoryFrameCoordinatorIsInactiveByDefault() {
    new ApplicationContextRunner()
            .withUserConfiguration(MemoryFrameConfig.class)
            .run(context -> assertThat(context)
                    .doesNotHaveBean(MemoryCoordinator.class));
}

@Test
void memoryFrameCoordinatorActivatesOnlyWhenEnabled() {
    new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues("springclaw.memory.frame.enabled=true")
            .run(context -> assertThat(context)
                    .hasSingleBean(MemoryCoordinator.class));
}
```

Write a shadow comparison test:

```java
@Test
void comparisonIsReadOnlyAndDoesNotChangeLegacyContextText() {
    AssembledContext legacy = new AssembledContext(
            "s1", "api", "alice", "question",
            "event text", "semantic text", "observe prompt", 1, 0
    );
    MemoryFrame frame = frameWithItems("run-1", List.of(item("semantic-1")));

    MemoryFrameShadowComparison comparison =
            new MemoryFrameShadowComparator().compare("run-1", legacy, frame);

    assertThat(comparison.runId()).isEqualTo("run-1");
    assertThat(comparison.legacyObservePromptHash()).isNotBlank();
    assertThat(comparison.frameHash()).isEqualTo(frame.frameHash());
    assertThat(legacy.observePrompt()).isEqualTo("observe prompt");
}
```

In `MemoryFrameShadowComparisonTest`, define local `item` and `frameWithItems`
helpers locally using the same `MemoryFrameItem` and `MemoryFrame` constructors
from Task 1. Do not import helper methods from another test class.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=MemoryFrameSpringWiringTest,MemoryFrameShadowComparisonTest test
```

Expected: compilation fails because config/comparator classes do not exist.

- [ ] **Step 3: Add configuration flags**

Add to `src/main/resources/application.yml`:

```yaml
springclaw:
  memory:
    frame:
      enabled: ${SPRINGCLAW_MEMORY_FRAME_ENABLED:false}
      shadow-compare-enabled: ${SPRINGCLAW_MEMORY_FRAME_SHADOW_COMPARE_ENABLED:false}
      max-chars: ${SPRINGCLAW_MEMORY_FRAME_MAX_CHARS:6000}
      trace-max-warnings: ${SPRINGCLAW_MEMORY_FRAME_TRACE_MAX_WARNINGS:20}
```

Add to `.env.example`:

```properties
SPRINGCLAW_MEMORY_FRAME_ENABLED=false
SPRINGCLAW_MEMORY_FRAME_SHADOW_COMPARE_ENABLED=false
SPRINGCLAW_MEMORY_FRAME_MAX_CHARS=6000
SPRINGCLAW_MEMORY_FRAME_TRACE_MAX_WARNINGS=20
```

- [ ] **Step 4: Implement Spring config and comparator**

`MemoryFrameConfig` should register `MemoryCoordinator` only when:

```java
@ConditionalOnProperty(
        prefix = "springclaw.memory.frame",
        name = "enabled",
        havingValue = "true"
)
```

It must not inject into `ChatContextFactory`, `ContextAssembler`, or Advisors in
this task.

`MemoryFrameShadowComparator` computes:

- SHA-256 of legacy `observePrompt`;
- `frameHash`;
- legacy active/filtered learning counts;
- frame layer counts;
- omission counts.

- [ ] **Step 5: Verify GREEN**

Run:

```bash
mvn -q -Dtest=MemoryFrameSpringWiringTest,MemoryFrameShadowComparisonTest,ContextAssemblerTest,ChatContextFactoryTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/config/MemoryFrameConfig.java \
  src/main/java/com/springclaw/service/memory/frame \
  src/main/resources/application.yml .env.example \
  src/test/java/com/springclaw/service/memory/frame
git commit -m "feat: wire memory frame shadow retrieval"
```

---

## Task 5: Add Phase 3A2 acceptance gates and ledger update

**Owner:** Codex

**Files:**

- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`
- Test-only commands; no production code unless prior tasks uncovered a regression.

- [ ] **Step 1: Run focused Phase 3A2 suite**

Run:

```bash
mvn -q -Dtest=MemoryFrameContractTest,MemoryFrameHasherTest,MemoryFrameBudgetTest,MemoryCoordinatorTest,MemoryFrameSpringWiringTest,MemoryFrameShadowComparisonTest,MemoryContractTest,InMemoryMemoryStoresTest,MemoryBankServiceTest,MarkdownProjectMemorySourceTest,AgentLearningServiceTest test
```

Required: zero failures and zero errors.

- [ ] **Step 2: Run compatibility gates**

Run with local DB env loaded if needed:

```bash
set -a
source /Users/hanbingzheng/springclaw/.env.local
set +a
mvn -q -Dtest=ContextPropagationCharacterizationTest,RuntimeRouteCharacterizationTest,FinalAnswerOwnershipCharacterizationTest,TransportParityCharacterizationTest,ChatContextFactoryTest,EngineSelectorTest,PromptInjectionTest,ToolRuntimeAspectInterceptionIT,WorkspaceGitGuardTest test
```

Required:

- no active context-input change;
- no route or answer ownership change;
- no P0 tool-safety regression.

- [ ] **Step 3: Run full suite**

Run:

```bash
set -a
source /Users/hanbingzheng/springclaw/.env.local
set +a
mvn -q test
```

Record exact test/failure/error/skip counts from Surefire XML:

```bash
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
tot=[0,0,0,0]
for p in Path('target/surefire-reports').glob('TEST-*.xml'):
    root=ET.parse(p).getroot()
    vals=[int(root.attrib.get(k,0)) for k in ('tests','failures','errors','skipped')]
    tot=[a+b for a,b in zip(tot, vals)]
print(tuple(tot))
PY
```

- [ ] **Step 4: Update collaboration ledger**

Append a Phase 3A2 section recording:

- all Phase 3A2 commit SHAs;
- owners and modified paths;
- active prompt path unchanged;
- frame flags and default disabled behavior;
- focused suite counts;
- compatibility gate counts;
- full suite counts;
- known limitations:
  - `ContextSnapshotFactory` still inactive;
  - `ContextSnapshot` still does not embed `MemoryFrame`;
  - Advisors still perform legacy retrieval until Phase 3A3;
  - vector candidates remain optional and authority-checked;
- rollback:
  - disable `SPRINGCLAW_MEMORY_FRAME_ENABLED`;
  - disable `SPRINGCLAW_MEMORY_FRAME_SHADOW_COMPARE_ENABLED`;
  - revert shadow wiring;
  - revert coordinator;
  - revert contracts.

- [ ] **Step 5: Commit ledger**

```bash
git add docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
git commit -m "docs: record phase 3a2 memory frame evidence"
```

---

## Final Phase 3A2 gate

Phase 3A2 is complete only when:

- `MemoryFrame` contracts exist and pass contract tests;
- `MemoryCoordinator` builds deterministic authorized frames from Phase 3A1 sources;
- frame retrieval is disabled by default;
- shadow comparison is read-only;
- compatibility gates pass;
- full suite passes;
- collaboration ledger records evidence and rollback.

Phase 3A3 must be a separate plan. Do not activate `ContextSnapshotFactory` or
projection-only Advisors in this plan.
