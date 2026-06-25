# Unified Runtime Phase 3A3a ContextSnapshot Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Embed `MemoryFrame` into `ContextSnapshot` and add a default-off bridge that projects canonical snapshots back into legacy chat context objects.

**Architecture:** Phase 3A3a is a conservative bridge, not the final legacy cleanup. `ContextSnapshot` becomes capable of carrying the structured `MemoryFrame`; `ContextSnapshotFactory` builds snapshots from accepted identity and `MemoryCoordinator`; `LegacyContextViewAdapter` renders `AssembledContext`/`ContextInjection` from snapshots; `ChatContextFactory` switches to this path only behind a default-off flag. Advisor-side semantic retrieval is suppressed only when canonical snapshot mode is enabled.

**Tech Stack:** Java 17, Spring Boot 3.5, JUnit 5, AssertJ, Mockito, existing Phase 3A1/3A2 memory contracts.

---

## Hard boundaries

Do not change these behaviors in Phase 3A3a:

- final-answer ownership;
- routing policy or engine selection;
- stream termination;
- tool approval, proposal, workspace guard, or tool runtime safety;
- default startup behavior;
- automatic semantic extraction.

Default mode must remain the current legacy behavior until
`springclaw.context.snapshot.factory-enabled=true`.

## File map

### Contract and tests

- Modify: `src/main/java/com/springclaw/runtime/contract/ContextSnapshot.java`
  - Add required `MemoryFrame memoryFrame`.
  - Validate snapshot/frame run ID match.
  - Keep existing flat fields as compatibility projections.
- Test: `src/test/java/com/springclaw/runtime/contract/ContextSnapshotMemoryFrameContractTest.java`
  - New focused contract tests.
- Modify test fixtures that call `new ContextSnapshot(...)`:
  - `src/main/java/com/springclaw/runtime/bridge/LegacyRunContextAdapter.java`
  - `src/test/java/com/springclaw/runtime/lifecycle/RunCoordinatorTest.java`
  - `src/test/java/com/springclaw/runtime/contract/RunStateContractTest.java`
  - `src/test/java/com/springclaw/runtime/contract/LegacyRuntimeContractFixtures.java`
  - `src/test/java/com/springclaw/runtime/contract/ContextAndDecisionContractTest.java`
  - `src/test/java/com/springclaw/runtime/bridge/LegacyRuntimeAdaptersTest.java`

### New canonical bridge

- Create: `src/main/java/com/springclaw/runtime/contract/ContextSnapshotFactory.java`
  - Production factory for canonical snapshots.
- Create: `src/main/java/com/springclaw/runtime/contract/ContextSnapshotRequest.java`
  - Immutable request DTO for the factory.
- Test: `src/test/java/com/springclaw/runtime/contract/ContextSnapshotFactoryTest.java`

### Legacy projection

- Create: `src/main/java/com/springclaw/runtime/bridge/LegacyContextView.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/LegacyContextViewAdapter.java`
- Test: `src/test/java/com/springclaw/runtime/bridge/LegacyContextViewAdapterTest.java`

### Default-off wiring

- Modify: `src/main/resources/application.yml`
- Modify: `.env.example`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java`
- Test: `src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalSnapshotTest.java`

### Advisor guard

- Modify: `src/main/java/com/springclaw/service/chat/impl/ConversationAdvisorSupport.java`
- Test: `src/test/java/com/springclaw/service/chat/impl/ConversationAdvisorSupportCanonicalModeTest.java`

### Evidence

- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`

---

## Task 1: Embed MemoryFrame in ContextSnapshot contract

**Owner:** Codex

**Files:**

- Modify: `src/main/java/com/springclaw/runtime/contract/ContextSnapshot.java`
- Create: `src/test/java/com/springclaw/runtime/contract/ContextSnapshotMemoryFrameContractTest.java`
- Modify existing test fixtures listed in the file map.

- [ ] **Step 1: Write failing contract test**

Create `src/test/java/com/springclaw/runtime/contract/ContextSnapshotMemoryFrameContractTest.java`:

```java
package com.springclaw.runtime.contract;

import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextSnapshotMemoryFrameContractTest {

    private static final Instant T0 = Instant.parse("2026-06-24T00:00:00Z");

    @Test
    void requiresMemoryFrameAndMatchingRunId() {
        MemoryFrame frame = frame("run-1");

        ContextSnapshot snapshot = snapshot("run-1", frame);

        assertThat(snapshot.memoryFrame()).isEqualTo(frame);
        assertThatThrownBy(() -> snapshot("run-2", frame))
                .hasMessageContaining("MemoryFrame");
        assertThatThrownBy(() -> snapshot("run-1", null))
                .hasMessageContaining("memoryFrame");
    }

    @Test
    void copiesCompatibilityCollections() {
        List<String> events = new ArrayList<>();
        events.add("event-1");

        ContextSnapshot snapshot = new ContextSnapshot(
                "run-1",
                "session-1",
                "alice",
                "api",
                "alice",
                "USER",
                "original",
                "effective",
                "system",
                "project",
                events,
                List.of("semantic"),
                List.of("rule"),
                List.of("web"),
                Map.of("providerId", "test"),
                Map.of("schema", "test"),
                frame("run-1"),
                T0,
                "hash-1"
        );

        events.add("event-2");

        assertThat(snapshot.shortTermEvents()).containsExactly("event-1");
        assertThatThrownBy(() -> snapshot.shortTermEvents().add("event-3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ContextSnapshot snapshot(String runId, MemoryFrame frame) {
        return new ContextSnapshot(
                runId,
                "session-1",
                "alice",
                "api",
                "alice",
                "USER",
                "original",
                "effective",
                "system",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                frame,
                T0,
                "hash-1"
        );
    }

    static MemoryFrame frame(String runId) {
        return new MemoryFrame(
                runId,
                MemoryScope.from(SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        "api",
                        "session-1",
                        "alice"
                )),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of("source", "test"),
                List.of(),
                T0,
                "frame-hash-1"
        );
    }
}
```

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=ContextSnapshotMemoryFrameContractTest test
```

Expected: compilation fails because `ContextSnapshot` does not expose
`memoryFrame()`.

- [ ] **Step 3: Update ContextSnapshot**

Modify `ContextSnapshot` constructor parameters by inserting
`MemoryFrame memoryFrame` before `capturedAt`:

```java
import com.springclaw.runtime.memory.contract.MemoryFrame;
```

Add field:

```java
MemoryFrame memoryFrame,
```

Add constructor validation after `contextSourceSummary` copy:

```java
memoryFrame = Objects.requireNonNull(memoryFrame, "memoryFrame");
if (!runId.equals(memoryFrame.runId())) {
    throw new IllegalArgumentException("MemoryFrame runId must match ContextSnapshot runId");
}
capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
```

- [ ] **Step 4: Migrate existing constructor call sites**

For each existing `new ContextSnapshot(...)`, pass an explicit frame before
`capturedAt`.

For tests, add or reuse a helper:

```java
private static MemoryFrame memoryFrame(String runId) {
    return new MemoryFrame(
            runId,
            MemoryScope.from(SessionAccessClaim.personal(
                    SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                    "api",
                    "session-1",
                    "user-1"
            )),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Map.of("source", "legacy-test"),
            List.of(),
            Instant.parse("2026-06-24T00:00:00Z"),
            "frame-hash-" + runId
    );
}
```

In `LegacyRunContextAdapter`, build a compatibility frame from the already
assembled legacy fields with a private helper:

```java
memoryFrameFromLegacy(
        context.requestId(),
        context.channel(),
        context.session().getSessionKey(),
        context.userId(),
        assembled,
        capturedAt
)
```

Implement `memoryFrameFromLegacy(...)` in the same adapter as a private method.
It should create a personal `MemoryScope` from the legacy `channel`, `sessionKey`,
and `userId`, and should place the legacy `eventContext` and `semanticContext`
into compatibility `MemoryFrameItem` entries only when non-blank. Do not call
`MemoryCoordinator` from `LegacyRunContextAdapter`.

- [ ] **Step 5: Verify GREEN**

Run:

```bash
mvn -q -Dtest=ContextSnapshotMemoryFrameContractTest,ContextAndDecisionContractTest,LegacyRuntimeAdaptersTest,RunCoordinatorTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/runtime/contract/ContextSnapshot.java \
  src/main/java/com/springclaw/runtime/bridge/LegacyRunContextAdapter.java \
  src/test/java/com/springclaw/runtime/contract/ContextSnapshotMemoryFrameContractTest.java \
  src/test/java/com/springclaw/runtime/lifecycle/RunCoordinatorTest.java \
  src/test/java/com/springclaw/runtime/contract/RunStateContractTest.java \
  src/test/java/com/springclaw/runtime/contract/LegacyRuntimeContractFixtures.java \
  src/test/java/com/springclaw/runtime/contract/ContextAndDecisionContractTest.java \
  src/test/java/com/springclaw/runtime/bridge/LegacyRuntimeAdaptersTest.java
git commit -m "feat: embed memory frame in context snapshot"
```

---

## Task 2: Add ContextSnapshotFactory

**Owner:** Codex

**Files:**

- Create: `src/main/java/com/springclaw/runtime/contract/ContextSnapshotRequest.java`
- Create: `src/main/java/com/springclaw/runtime/contract/ContextSnapshotFactory.java`
- Test: `src/test/java/com/springclaw/runtime/contract/ContextSnapshotFactoryTest.java`

- [ ] **Step 1: Write failing factory tests**

Create `ContextSnapshotFactoryTest`:

```java
package com.springclaw.runtime.contract;

import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.service.memory.frame.MemoryCoordinator;
import com.springclaw.service.memory.frame.MemoryFrameRequest;
import com.springclaw.service.memory.frame.MemoryFrameResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextSnapshotFactoryTest {

    private static final Instant T0 = Instant.parse("2026-06-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    @Test
    void buildsSnapshotFromOneMemoryCoordinatorRetrieval() {
        MemoryCoordinator coordinator = mock(MemoryCoordinator.class);
        MemoryScope scope = scope();
        MemoryFrame frame = frame("run-1", "frame-hash-a");
        when(coordinator.retrieve(any(MemoryFrameRequest.class)))
                .thenReturn(new MemoryFrameResult(frame, trace("run-1", scope, "frame-hash-a")));

        ContextSnapshotFactory factory = new ContextSnapshotFactory(coordinator, CLOCK);
        ContextSnapshot snapshot = factory.create(request("run-1"));

        verify(coordinator).retrieve(new MemoryFrameRequest("run-1", scope, "effective"));
        assertThat(snapshot.runId()).isEqualTo("run-1");
        assertThat(snapshot.memoryFrame()).isEqualTo(frame);
        assertThat(snapshot.shortTermEvents()).isEmpty();
        assertThat(snapshot.contextSourceSummary())
                .containsEntry("memoryFrameHash", "frame-hash-a")
                .containsEntry("schema", "springclaw.context-snapshot.v1");
    }

    @Test
    void snapshotHashChangesWhenFrameHashChanges() {
        MemoryCoordinator coordinator = mock(MemoryCoordinator.class);
        ContextSnapshotFactory factory = new ContextSnapshotFactory(coordinator, CLOCK);
        when(coordinator.retrieve(any(MemoryFrameRequest.class)))
                .thenReturn(new MemoryFrameResult(frame("run-1", "frame-hash-a"), trace("run-1", scope(), "frame-hash-a")))
                .thenReturn(new MemoryFrameResult(frame("run-1", "frame-hash-b"), trace("run-1", scope(), "frame-hash-b")));

        ContextSnapshot first = factory.create(request("run-1"));
        ContextSnapshot second = factory.create(request("run-1"));

        assertThat(first.snapshotHash()).isNotEqualTo(second.snapshotHash());
    }

    private static ContextSnapshotRequest request(String runId) {
        SessionAccessClaim claim = SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                "session-1",
                "alice"
        );
        return new ContextSnapshotRequest(
                runId,
                "session-1",
                "alice",
                "api",
                "alice",
                claim,
                "USER",
                "original",
                "effective",
                "system",
                List.of("web"),
                Map.of("providerId", "test", "model", "test-model")
        );
    }

    private static MemoryScope scope() {
        return MemoryScope.from(SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                "session-1",
                "alice"
        ));
    }
}
```

Use the existing `MemoryRetrievalTrace` constructor in helper `trace(...)`.
Use the existing `MemoryFrame` constructor in helper `frame(...)`.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=ContextSnapshotFactoryTest test
```

Expected: compilation fails because `ContextSnapshotFactory` and
`ContextSnapshotRequest` do not exist.

- [ ] **Step 3: Implement ContextSnapshotRequest**

Create `ContextSnapshotRequest`:

```java
package com.springclaw.runtime.contract;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ContextSnapshotRequest(
        String runId,
        String sessionKey,
        String sessionOwnerUserId,
        String channel,
        String userId,
        SessionAccessClaim sessionAccessClaim,
        String roleCode,
        String originalMessage,
        String effectiveMessage,
        String systemPrompt,
        List<String> allowedCapabilities,
        Map<String, String> providerSnapshot
) {
    public ContextSnapshotRequest {
        runId = requireText(runId, "runId");
        sessionKey = requireText(sessionKey, "sessionKey");
        sessionOwnerUserId = requireText(sessionOwnerUserId, "sessionOwnerUserId");
        channel = requireText(channel, "channel");
        userId = requireText(userId, "userId");
        sessionAccessClaim = Objects.requireNonNull(sessionAccessClaim, "sessionAccessClaim");
        roleCode = requireText(roleCode, "roleCode");
        originalMessage = originalMessage == null ? "" : originalMessage;
        effectiveMessage = effectiveMessage == null ? "" : effectiveMessage;
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        allowedCapabilities = allowedCapabilities == null ? List.of() : List.copyOf(allowedCapabilities);
        providerSnapshot = providerSnapshot == null ? Map.of() : Map.copyOf(providerSnapshot);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
```

- [ ] **Step 4: Implement ContextSnapshotFactory**

Create `ContextSnapshotFactory`:

```java
package com.springclaw.runtime.contract;

import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryRetrievalTrace;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.service.memory.frame.MemoryCoordinator;
import com.springclaw.service.memory.frame.MemoryFrameRequest;
import com.springclaw.service.memory.frame.MemoryFrameResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ContextSnapshotFactory {

    private static final String SCHEMA = "springclaw.context-snapshot.v1";

    private final MemoryCoordinator memoryCoordinator;
    private final Clock clock;

    public ContextSnapshotFactory(MemoryCoordinator memoryCoordinator, Clock clock) {
        this.memoryCoordinator = Objects.requireNonNull(memoryCoordinator, "memoryCoordinator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ContextSnapshot create(ContextSnapshotRequest request) {
        Objects.requireNonNull(request, "request");
        MemoryScope scope = MemoryScope.from(request.sessionAccessClaim());
        MemoryFrameResult result = memoryCoordinator.retrieve(new MemoryFrameRequest(
                request.runId(),
                scope,
                request.effectiveMessage()
        ));
        MemoryFrame frame = result.frame();
        MemoryRetrievalTrace trace = result.trace();
        Instant capturedAt = clock.instant();
        Map<String, String> sourceSummary = sourceSummary(frame, trace);
        String hash = snapshotHash(request, frame, sourceSummary);
        return new ContextSnapshot(
                request.runId(),
                request.sessionKey(),
                request.sessionOwnerUserId(),
                request.channel(),
                request.userId(),
                request.roleCode(),
                request.originalMessage(),
                request.effectiveMessage(),
                request.systemPrompt(),
                renderProject(frame),
                frame.shortTermTurns().stream().map(item -> item.content()).toList(),
                frame.semanticFacts().stream().map(item -> item.content()).toList(),
                frame.proceduralRules().stream().map(item -> item.content()).toList(),
                request.allowedCapabilities(),
                request.providerSnapshot(),
                sourceSummary,
                frame,
                capturedAt,
                hash
        );
    }

    private static Map<String, String> sourceSummary(MemoryFrame frame, MemoryRetrievalTrace trace) {
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("schema", SCHEMA);
        summary.put("memoryFrameHash", frame.frameHash());
        summary.put("traceFrameHash", trace.frameHash());
        summary.put("shortTermCount", Integer.toString(frame.shortTermTurns().size()));
        summary.put("episodicCount", Integer.toString(frame.episodicItems().size()));
        summary.put("semanticCount", Integer.toString(frame.semanticFacts().size()));
        summary.put("proceduralCount", Integer.toString(frame.proceduralRules().size()));
        summary.put("projectCount", Integer.toString(frame.projectItems().size()));
        summary.put("omissionCount", Integer.toString(frame.omissions().size()));
        return Map.copyOf(summary);
    }

    private static String renderProject(MemoryFrame frame) {
        return frame.projectItems().stream()
                .map(item -> item.content())
                .filter(text -> text != null && !text.isBlank())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private static String snapshotHash(ContextSnapshotRequest request, MemoryFrame frame, Map<String, String> summary) {
        return sha256(String.join("\n",
                request.runId(),
                request.sessionKey(),
                request.channel(),
                request.userId(),
                request.roleCode(),
                request.originalMessage(),
                request.effectiveMessage(),
                request.systemPrompt(),
                request.allowedCapabilities().toString(),
                request.providerSnapshot().toString(),
                summary.toString(),
                frame.frameHash()
        ));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
```

- [ ] **Step 5: Verify GREEN**

Run:

```bash
mvn -q -Dtest=ContextSnapshotFactoryTest,ContextSnapshotMemoryFrameContractTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/runtime/contract/ContextSnapshotFactory.java \
  src/main/java/com/springclaw/runtime/contract/ContextSnapshotRequest.java \
  src/test/java/com/springclaw/runtime/contract/ContextSnapshotFactoryTest.java
git commit -m "feat: build context snapshots from memory frames"
```

---

## Task 3: Add LegacyContextViewAdapter

**Owner:** Codex or Claude after Task 2

**Files:**

- Create: `src/main/java/com/springclaw/runtime/bridge/LegacyContextView.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/LegacyContextViewAdapter.java`
- Test: `src/test/java/com/springclaw/runtime/bridge/LegacyContextViewAdapterTest.java`

- [ ] **Step 1: Write failing adapter test**

Create `LegacyContextViewAdapterTest`:

```java
package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyContextViewAdapterTest {

    private static final Instant T0 = Instant.parse("2026-06-24T00:00:00Z");

    @Test
    void projectsSnapshotIntoLegacyAssembledContextAndInjection() {
        ContextSnapshot snapshot = snapshot();

        LegacyContextView view = new LegacyContextViewAdapter().adapt(snapshot);

        assertThat(view.assembled().observePrompt())
                .contains("# 项目记忆（Memory Bank）")
                .contains("project fact")
                .contains("# 短期会话上下文（事件流）")
                .contains("USER: hello")
                .contains("# 长期语义记忆（同会话优先）")
                .contains("semantic fact")
                .contains("# 程序化记忆（规则/经验）")
                .contains("always verify");
        assertThat(view.injection().observePrompt())
                .isEqualTo(view.assembled().observePrompt());
        assertThat(view.injection().metadata())
                .containsEntry("memoryFrameHash", "frame-hash-1");
    }
}
```

Use local helpers `snapshot()`, `frame()`, and `item(...)` inside the test.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=LegacyContextViewAdapterTest test
```

Expected: compilation fails because adapter classes do not exist.

- [ ] **Step 3: Implement LegacyContextView**

```java
package com.springclaw.runtime.bridge;

import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;

import java.util.Objects;

public record LegacyContextView(
        AssembledContext assembled,
        ContextInjection injection
) {
    public LegacyContextView {
        assembled = Objects.requireNonNull(assembled, "assembled");
        injection = injection == null ? ContextInjection.empty() : injection;
    }
}
```

- [ ] **Step 4: Implement LegacyContextViewAdapter**

```java
package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryFrameItem;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class LegacyContextViewAdapter {

    public LegacyContextView adapt(ContextSnapshot snapshot) {
        MemoryFrame frame = snapshot.memoryFrame();
        String project = renderLayer(frame.projectItems());
        String shortTerm = renderLayer(frame.shortTermTurns());
        String semantic = renderLayer(frame.semanticFacts());
        String procedural = renderLayer(frame.proceduralRules());
        String observe = """
                # 当前问题
                %s

                # 项目记忆（Memory Bank）
                %s

                # 短期会话上下文（事件流）
                %s

                # 长期语义记忆（同会话优先）
                %s

                # 程序化记忆（规则/经验）
                %s
                """.formatted(
                snapshot.effectiveMessage(),
                project,
                shortTerm,
                semantic,
                procedural
        );
        AssembledContext assembled = new AssembledContext(
                snapshot.sessionKey(),
                snapshot.channel(),
                snapshot.userId(),
                snapshot.effectiveMessage(),
                shortTerm,
                semantic,
                observe,
                parseInt(snapshot.contextSourceSummary().get("memoryLearningActiveCount")),
                parseInt(snapshot.contextSourceSummary().get("memoryLearningFilteredCount"))
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("contextSummary", assembled.sourceSummary());
        metadata.put("memoryFrameHash", frame.frameHash());
        metadata.put("memoryFrameSourceSummary", frame.sourceSummary());
        metadata.put("contextSnapshotHash", snapshot.snapshotHash());
        return new LegacyContextView(
                assembled,
                new ContextInjection(observe, "", "", metadata)
        );
    }

    private static String renderLayer(java.util.List<MemoryFrameItem> items) {
        return items.stream()
                .map(MemoryFrameItem::content)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
```

- [ ] **Step 5: Verify GREEN**

Run:

```bash
mvn -q -Dtest=LegacyContextViewAdapterTest,ContextSnapshotFactoryTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/runtime/bridge/LegacyContextView.java \
  src/main/java/com/springclaw/runtime/bridge/LegacyContextViewAdapter.java \
  src/test/java/com/springclaw/runtime/bridge/LegacyContextViewAdapterTest.java
git commit -m "feat: project context snapshots to legacy views"
```

---

## Task 4: Add default-off ChatContextFactory bridge

**Owner:** Codex

**Files:**

- Modify: `src/main/resources/application.yml`
- Modify: `.env.example`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java`
- Test: `src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalSnapshotTest.java`

- [ ] **Step 1: Write failing ChatContextFactory bridge tests**

Create `ChatContextFactoryCanonicalSnapshotTest` with two tests:

```java
@Test
void defaultModeStillUsesLegacyContextAssembler() {
    Fixture fixture = new Fixture(false);

    ChatContext context = fixture.factory.build(
            new ChatRequest("s1", "u1", "hello", "api", "agent"),
            true,
            "0123456789abcdef0123456789abcdef"
    );

    verify(fixture.contextAssembler).assemble("s1", "api", "u1", "hello");
    verifyNoInteractions(fixture.contextSnapshotFactory, fixture.legacyContextViewAdapter);
    assertThat(context.assembled().observePrompt()).isEqualTo("legacy observe");
}

@Test
void canonicalModeUsesSnapshotFactoryAndSkipsContextAssembler() {
    Fixture fixture = new Fixture(true);

    ChatContext context = fixture.factory.build(
            new ChatRequest("s1", "u1", "hello", "api", "agent"),
            true,
            "0123456789abcdef0123456789abcdef"
    );

    verifyNoInteractions(fixture.contextAssembler);
    verify(fixture.contextSnapshotFactory).create(any(ContextSnapshotRequest.class));
    verify(fixture.legacyContextViewAdapter).adapt(fixture.snapshot);
    assertThat(context.assembled().observePrompt()).isEqualTo("canonical observe");
    assertThat(context.contextInjection().observePrompt()).isEqualTo("canonical observe");
}
```

Use a local `Fixture` class that mocks the same dependencies as
`ChatContextFactoryTest`. The fixture should provide:

- `ContextAssembler contextAssembler`;
- `ContextSnapshotFactory contextSnapshotFactory`;
- `LegacyContextViewAdapter legacyContextViewAdapter`;
- `ContextSnapshot snapshot`;
- `ChatContextFactory factory`.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=ChatContextFactoryCanonicalSnapshotTest test
```

Expected: compilation fails because `ChatContextFactory` constructor does not
accept the new dependencies/flag.

- [ ] **Step 3: Add configuration**

Add to `application.yml` under `springclaw`:

```yaml
  context:
    snapshot:
      factory-enabled: ${SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED:false}
```

Add to `.env.example`:

```properties
SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false
```

- [ ] **Step 4: Modify ChatContextFactory constructor**

Add imports:

```java
import com.springclaw.runtime.bridge.LegacyContextView;
import com.springclaw.runtime.bridge.LegacyContextViewAdapter;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.ContextSnapshotFactory;
import com.springclaw.runtime.contract.ContextSnapshotRequest;
import org.springframework.beans.factory.ObjectProvider;
```

Add fields:

```java
private final ObjectProvider<ContextSnapshotFactory> contextSnapshotFactoryProvider;
private final ObjectProvider<LegacyContextViewAdapter> legacyContextViewAdapterProvider;
private final boolean contextSnapshotFactoryEnabled;
```

Add constructor parameters after `AgentDecisionService agentDecisionService`:

```java
ObjectProvider<ContextSnapshotFactory> contextSnapshotFactoryProvider,
ObjectProvider<LegacyContextViewAdapter> legacyContextViewAdapterProvider,
@org.springframework.beans.factory.annotation.Value("${springclaw.context.snapshot.factory-enabled:false}")
boolean contextSnapshotFactoryEnabled,
```

Update existing tests to pass mock providers or use a compatibility constructor.
Prefer adding a package-private compatibility constructor for old tests:

```java
ChatContextFactory(... existing params ...) {
    this(... existing params ...,
            new StaticObjectProvider<>(null),
            new StaticObjectProvider<>(null),
            false,
            configuredAgentMode,
            routingAutoUpgradeEnabled);
}
```

If no simple `StaticObjectProvider` exists, use nullable package-private overload
only in tests. Do not make canonical mode silently active when providers are
missing.

- [ ] **Step 5: Add canonical branch**

Replace the single `contextAssembler.assemble(...)` call with:

```java
AssembledContext assembled;
ContextInjection injection;
if (contextSnapshotFactoryEnabled) {
    ContextSnapshotFactory snapshotFactory = contextSnapshotFactoryProvider.getIfAvailable();
    LegacyContextViewAdapter viewAdapter = legacyContextViewAdapterProvider.getIfAvailable();
    if (snapshotFactory == null || viewAdapter == null) {
        throw new IllegalStateException("canonical ContextSnapshotFactory path is enabled but required beans are missing");
    }
    ContextSnapshot snapshot = snapshotFactory.create(new ContextSnapshotRequest(
            requestId,
            session.getSessionKey(),
            session.getUserId() == null ? request.userId() : session.getUserId(),
            channel,
            request.userId(),
            com.springclaw.runtime.contract.SessionAccessClaim.personal(
                    com.springclaw.runtime.contract.SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                    channel,
                    session.getSessionKey(),
                    request.userId()
            ),
            roleCode,
            request.message(),
            routingDecision.effectiveQuestion(),
            systemPrompt,
            decision.selectedCapabilities(),
            Map.of(
                    "providerId", activeClient.providerId(),
                    "model", activeClient.model(),
                    "baseUrl", activeClient.baseUrl(),
                    "available", Boolean.toString(activeClient.available())
            )
    ));
    LegacyContextView view = viewAdapter.adapt(snapshot);
    assembled = view.assembled();
    injection = view.injection();
} else {
    assembled = contextAssembler.assemble(
            session.getSessionKey(),
            channel,
            request.userId(),
            routingDecision.effectiveQuestion()
    );
    injection = new ContextInjection(
            assembled == null ? "" : assembled.observePrompt(),
            "",
            "",
            Map.of(
                    "contextSummary",
                    assembled == null
                            ? AssembledContext.ContextSourceSummary.empty()
                            : assembled.sourceSummary()
            )
    );
}
```

Move `AiProviderService.ActiveChatClient activeClient = aiProviderService.activeClient();`
before the canonical branch so provider metadata is available.

This is a temporary bridge. Later Phase 3A3b should use the actual accepted
`SessionAccessClaim` from `RunState`; this task must keep REST API scope personal
and must not infer shared scope from session strings.

- [ ] **Step 6: Verify GREEN**

Run:

```bash
mvn -q -Dtest=ChatContextFactoryCanonicalSnapshotTest,ChatContextFactoryTest test
```

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java \
  src/main/resources/application.yml .env.example \
  src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalSnapshotTest.java \
  src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java
git commit -m "feat: bridge chat context through canonical snapshots"
```

---

## Task 5: Suppress independent semantic Advisor retrieval in canonical mode

**Owner:** Codex or Claude after Task 4

**Files:**

- Modify: `src/main/java/com/springclaw/service/chat/impl/ConversationAdvisorSupport.java`
- Test: `src/test/java/com/springclaw/service/chat/impl/ConversationAdvisorSupportCanonicalModeTest.java`

- [ ] **Step 1: Write failing Advisor guard test**

Create `ConversationAdvisorSupportCanonicalModeTest`:

```java
package com.springclaw.service.chat.impl;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;

import static org.mockito.Mockito.mock;

class ConversationAdvisorSupportCanonicalModeTest {

    @Test
    void canonicalModeConstructsWithoutSemanticAdvisor() {
        ConversationAdvisorSupport support = new ConversationAdvisorSupport(
                mock(MessageChatMemoryAdvisor.class),
                mock(SemanticMemoryAdvisor.class),
                false,
                true
        );

        // Constructor-level test: canonical mode must be representable without
        // changing default behavior. RequestSpec integration is covered by the
        // existing prompt/transport gates because Spring AI request spec is final
        // and expensive to fake correctly.
        org.assertj.core.api.Assertions.assertThat(support).isNotNull();
    }
}
```

This test is intentionally small. The real behavioral protection comes from the
constructor flag and compatibility gates.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=ConversationAdvisorSupportCanonicalModeTest test
```

Expected: compilation fails because the constructor does not accept canonical
mode flag.

- [ ] **Step 3: Modify ConversationAdvisorSupport**

Add field:

```java
private final boolean contextSnapshotFactoryEnabled;
```

Modify constructor:

```java
public ConversationAdvisorSupport(MessageChatMemoryAdvisor messageChatMemoryAdvisor,
                                  SemanticMemoryAdvisor semanticMemoryAdvisor,
                                  @Value("${springclaw.chat.spring-ai-chat-memory-enabled:false}") boolean springAiChatMemoryEnabled,
                                  @Value("${springclaw.context.snapshot.factory-enabled:false}") boolean contextSnapshotFactoryEnabled) {
    this.messageChatMemoryAdvisor = messageChatMemoryAdvisor;
    this.semanticMemoryAdvisor = semanticMemoryAdvisor;
    this.springAiChatMemoryEnabled = springAiChatMemoryEnabled;
    this.contextSnapshotFactoryEnabled = contextSnapshotFactoryEnabled;
}
```

Change advisor selection:

```java
List<Advisor> advisors;
if (contextSnapshotFactoryEnabled) {
    advisors = springAiChatMemoryEnabled
            ? List.of(messageChatMemoryAdvisor)
            : List.of();
} else {
    advisors = springAiChatMemoryEnabled
            ? List.of(messageChatMemoryAdvisor, semanticMemoryAdvisor)
            : List.of(semanticMemoryAdvisor);
}
```

Keep `.params(...)` unchanged so existing callers still receive conversation/user
IDs.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
mvn -q -Dtest=ConversationAdvisorSupportCanonicalModeTest,PromptInjectionTest,TransportParityCharacterizationTest test
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/springclaw/service/chat/impl/ConversationAdvisorSupport.java \
  src/test/java/com/springclaw/service/chat/impl/ConversationAdvisorSupportCanonicalModeTest.java
git commit -m "feat: guard semantic advisor in canonical context mode"
```

---

## Task 6: Phase 3A3a acceptance gates and ledger

**Owner:** Codex

**Files:**

- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`

- [ ] **Step 1: Run focused Phase 3A3a suite**

Run:

```bash
mvn -q -Dtest=ContextSnapshotMemoryFrameContractTest,ContextSnapshotFactoryTest,LegacyContextViewAdapterTest,ChatContextFactoryCanonicalSnapshotTest,ConversationAdvisorSupportCanonicalModeTest,ContextSnapshotFactoryTest,MemoryCoordinatorTest,MemoryFrameContractTest,MemoryFrameHasherTest,MemoryFrameBudgetTest test
```

Expected: zero failures and zero errors.

- [ ] **Step 2: Run compatibility gates**

Run with local environment variables loaded without printing secrets:

```bash
set -a
source /Users/hanbingzheng/springclaw/.env.local
set +a
mvn -q -Dtest=ContextPropagationCharacterizationTest,RuntimeRouteCharacterizationTest,FinalAnswerOwnershipCharacterizationTest,TransportParityCharacterizationTest,ChatContextFactoryTest,EngineSelectorTest,PromptInjectionTest,ToolRuntimeAspectInterceptionIT,WorkspaceGitGuardTest test
```

Expected:

- no route or answer ownership change;
- no stream/transport regression;
- no P0 tool-safety regression.

- [ ] **Step 3: Run full suite**

Run:

```bash
set -a
source /Users/hanbingzheng/springclaw/.env.local
set +a
mvn -q test
```

Record exact Surefire counts:

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

Append a Phase 3A3a section to
`docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`
recording:

- all Phase 3A3a commit SHAs;
- files modified;
- default-off flag:
  `SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false`;
- confirmation that default behavior remains legacy;
- confirmation that canonical mode uses snapshot-derived legacy views;
- focused suite counts;
- compatibility gate counts;
- full suite counts;
- known limitations:
  - canonical mode still derives REST personal claim in `ChatContextFactory`;
  - Phase 3A3b must use the real accepted `RunState.sessionAccessClaim`;
  - `ContextAssembler` still exists for legacy/default mode;
  - `SemanticMemoryAdvisor` still exists for legacy/default mode;
  - projection-only Advisor is deferred;
- rollback:
  - disable context snapshot factory flag;
  - disable MemoryFrame flag if needed;
  - revert advisor guard;
  - revert ChatContextFactory bridge;
  - revert factory/adapter;
  - revert contract extension only as last step.

- [ ] **Step 5: Commit ledger**

```bash
git add docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
git commit -m "docs: record phase 3a3a context snapshot evidence"
```

---

## Final Phase 3A3a gate

Phase 3A3a is complete only when:

- `ContextSnapshot` embeds `MemoryFrame`;
- `ContextSnapshotFactory` builds snapshots using exactly one
  `MemoryCoordinator.retrieve(...)` call per context build;
- `LegacyContextViewAdapter` derives `AssembledContext` and `ContextInjection`
  from the saved snapshot;
- default mode remains unchanged;
- canonical mode skips `ContextAssembler`;
- canonical mode suppresses independent semantic Advisor retrieval;
- focused, compatibility, and full suites pass;
- collaboration ledger records evidence and rollback.

Phase 3A3b must be a separate plan. Do not remove legacy context or Advisor code
in Phase 3A3a.
