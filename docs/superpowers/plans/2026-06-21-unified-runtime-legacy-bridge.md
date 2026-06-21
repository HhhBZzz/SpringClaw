# Unified Runtime Legacy Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Propagate one accepted 32-character `runId == requestId` through synchronous REST, SSE, RabbitMQ async, trace, audit, and confirmation ownership while a feature-flagged in-memory canonical lifecycle wraps the existing blocking `ChatServiceImpl` behavior without changing routing, answers, persistence, tool safety, SSE ownership, or transport payloads.

**Architecture:** Add an internal transport-neutral `AcceptedChatCommand`, canonical identity factory, optimistic in-memory run repository, idempotent ordered event store, and a `RunCoordinator` that observes the existing blocking legacy execution boundary. `LegacyRunContextAdapter`, `LegacyExecutionDecisionAdapter`, and `LegacyRunResultAdapter` translate already-produced legacy objects into the Phase 1 contracts; they do not retrieve context, reroute, repair answers, or replace persistence. The bridge is off by default, is active only for synchronous non-streaming execution (including the RabbitMQ consumer), and leaves SSE lifecycle ownership entirely in the existing stream path while still supplying SSE with the canonical accepted ID.

**Tech Stack:** Java 17 records, Spring Boot 3.5 constructor injection and `@Value`, JUnit 5, AssertJ, Mockito, `ConcurrentHashMap`, Maven Surefire.

---

## Scope, ownership, and file map

Codex owns all production work in this plan: runtime core plus `ChatServiceImpl`, `ChatContextFactory`, ingress identity wiring, and the diagnostic trace guard. No Claude projector production work is assigned.

Create:

```text
src/main/java/com/springclaw/runtime/identity/RunIdentityFactory.java
src/main/java/com/springclaw/runtime/identity/DefaultRunIdentityFactory.java
src/main/java/com/springclaw/runtime/bridge/AcceptedChatCommand.java
src/main/java/com/springclaw/runtime/store/RunStateRepository.java
src/main/java/com/springclaw/runtime/store/InMemoryRunStateRepository.java
src/main/java/com/springclaw/runtime/store/RunEventStore.java
src/main/java/com/springclaw/runtime/store/InMemoryRunEventStore.java
src/main/java/com/springclaw/runtime/bridge/LegacyChatExecutor.java
src/main/java/com/springclaw/runtime/bridge/LegacyExecutionObserver.java
src/main/java/com/springclaw/runtime/bridge/LegacyChatExecution.java
src/main/java/com/springclaw/runtime/bridge/LegacyRunContextAdapter.java
src/main/java/com/springclaw/runtime/bridge/LegacyExecutionDecisionAdapter.java
src/main/java/com/springclaw/runtime/bridge/LegacyRunResultAdapter.java
src/main/java/com/springclaw/runtime/bridge/LegacyEngineMetadataRegistry.java
src/main/java/com/springclaw/runtime/bridge/LegacyRuntimeStrategy.java
src/main/java/com/springclaw/runtime/bridge/LegacyRunStateFactory.java
src/main/java/com/springclaw/runtime/bridge/RunCoordinator.java
src/test/java/com/springclaw/runtime/identity/DefaultRunIdentityFactoryTest.java
src/test/java/com/springclaw/runtime/store/InMemoryRunStateRepositoryTest.java
src/test/java/com/springclaw/runtime/store/InMemoryRunEventStoreTest.java
src/test/java/com/springclaw/runtime/bridge/LegacyBridgeTestFixtures.java
src/test/java/com/springclaw/runtime/bridge/LegacyRuntimeAdaptersTest.java
src/test/java/com/springclaw/runtime/bridge/RunCoordinatorTest.java
src/test/java/com/springclaw/service/chat/async/ChatMessageConsumerCanonicalIdentityTest.java
src/test/java/com/springclaw/service/chat/impl/UnifiedBridgeActivationTest.java
```

Modify:

```text
src/main/java/com/springclaw/controller/ChatController.java
src/main/java/com/springclaw/service/chat/ChatService.java
src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java
src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java
src/main/java/com/springclaw/service/chat/impl/OparLoopEngine.java
src/main/java/com/springclaw/service/chat/impl/SimplifiedOparEngine.java
src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java
src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java
src/main/java/com/springclaw/service/agent/executor/WorkspaceCapabilityExecutor.java
src/main/java/com/springclaw/service/agent/executor/LocalFilesCapabilityExecutor.java
src/main/java/com/springclaw/service/agent/executor/WebCapabilityExecutor.java
src/main/java/com/springclaw/service/agent/executor/SystemHealthCapabilityExecutor.java
src/main/java/com/springclaw/service/agent/executor/SkillCapabilityExecutor.java
src/main/java/com/springclaw/service/agent/executor/RealtimeCapabilityExecutor.java
src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java
src/main/java/com/springclaw/service/agent/AgentRunTraceService.java
src/main/resources/application.yml
.env.example
src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java
src/test/java/com/springclaw/architecture/ContextPropagationCharacterizationTest.java
src/test/java/com/springclaw/architecture/TransportParityCharacterizationTest.java
src/test/java/com/springclaw/controller/ChatControllerAuthTest.java
src/test/java/com/springclaw/service/agent/AgentRunTraceServiceTest.java
```

Do not modify:

```text
src/main/java/com/springclaw/dto/chat/ChatRequest.java
src/main/java/com/springclaw/dto/chat/ChatResponse.java
src/main/java/com/springclaw/service/chat/async/AsyncChatRequestMessage.java
src/main/java/com/springclaw/service/chat/async/AsyncChatResultPayload.java
src/main/java/com/springclaw/tool/runtime/ToolExecutionContext.java
src/main/java/com/springclaw/tool/runtime/ToolRuntimeAspect.java
src/main/java/com/springclaw/service/workspace/WorkspaceGuard.java
src/main/java/com/springclaw/service/workspace/WorkspaceGitGuard.java
src/main/java/com/springclaw/service/proposal/ToolInvocationProposalService.java
src/main/java/com/springclaw/service/proposal/ToolProposalExecutionService.java
src/main/java/com/springclaw/service/agent/EngineSelector.java
src/main/java/com/springclaw/runtime/contract/**
```

The bridge must not change engine `supports()` methods, engine priorities, route selection, final-answer composition, existing conversation/result persistence, stream completion, session-lock ownership, proposal authorization, workspace safety, tool invocation interception, or any REST/Rabbit/STOMP/SSE DTO shape.

### Task 1: Accept and propagate one canonical identity

**Files:**
- Create: `src/main/java/com/springclaw/runtime/identity/RunIdentityFactory.java`
- Create: `src/main/java/com/springclaw/runtime/identity/DefaultRunIdentityFactory.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/AcceptedChatCommand.java`
- Modify: `src/main/java/com/springclaw/service/chat/ChatService.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java`
- Test: `src/test/java/com/springclaw/runtime/identity/DefaultRunIdentityFactoryTest.java`
- Test: `src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java`
- Test: `src/test/java/com/springclaw/architecture/ContextPropagationCharacterizationTest.java`

- [ ] **Step 1: Write failing identity and context-propagation tests**

```java
package com.springclaw.runtime.identity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultRunIdentityFactoryTest {

    private final DefaultRunIdentityFactory factory = new DefaultRunIdentityFactory();

    @Test
    void generatesNormalizedUuidWithoutHyphens() {
        String runId = factory.create();

        assertThat(runId).matches("[0-9a-f]{32}");
    }

    @Test
    void acceptsOnlyNormalizedSuppliedIds() {
        assertThat(factory.accept("0123456789abcdef0123456789abcdef"))
                .isEqualTo("0123456789abcdef0123456789abcdef");
        assertThatThrownBy(() -> factory.accept("01234567-89ab-cdef-0123-456789abcdef"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 lowercase hexadecimal");
        assertThatThrownBy(() -> factory.accept("ABCDEF0123456789ABCDEF0123456789"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

Add this method to `ChatContextFactoryTest` using the class's existing fixture setup:

```java
@Test
void shouldUseAcceptedRunIdWithoutGeneratingAnotherIdentity() {
    RunIdentityFactory identityFactory = mock(RunIdentityFactory.class);
    when(identityFactory.accept("0123456789abcdef0123456789abcdef"))
            .thenReturn("0123456789abcdef0123456789abcdef");
    ChatContextFactory factory = new ChatContextFactory(
            aiProviderService,
            soulPromptService,
            agentSessionService,
            authService,
            skillService,
            skillRegistryService,
            contextAssembler,
            chatRoutingStateService,
            chatRoutingPolicyService,
            agentDecisionService,
            identityFactory,
            "simplified",
            true
    );
    AcceptedChatCommand command = new AcceptedChatCommand(
            "0123456789abcdef0123456789abcdef",
            "s1", "u1", "北京呢", "api", "agent", 1_750_464_000_000L
    );

    ChatContext context = factory.build(command, true);

    assertThat(context.requestId()).isEqualTo(command.runId());
    verify(identityFactory).accept(command.runId());
    verify(identityFactory, never()).create();
}
```

The mock variables in this method are the same types and stubs already constructed in `shouldKeepFollowUpQuestionUnchanged`; move that setup into a package-private `newFactoryFixture(RunIdentityFactory)` helper in the test class and have both tests call it. The helper's constructor call must be exactly the one shown above.

Update the production-backed characterization assertion:

```java
String acceptedId = "11111111111111111111111111111111";
ChatContext context = factory.build(new AcceptedChatCommand(
        acceptedId, "session-A", "alice", "为什么登录失败", "api", "agent",
        1_750_464_000_000L
), true);

assertThat(context.requestId()).isEqualTo(acceptedId);
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
mvn -q -Dtest=DefaultRunIdentityFactoryTest,ChatContextFactoryTest,ContextPropagationCharacterizationTest test
```

Expected: test compilation fails because `RunIdentityFactory`, `DefaultRunIdentityFactory`, `AcceptedChatCommand`, and `ChatContextFactory.build(AcceptedChatCommand, boolean)` do not exist.

- [ ] **Step 3: Implement the identity API and accepted command**

```java
package com.springclaw.runtime.identity;

public interface RunIdentityFactory {
    String create();
    String accept(String suppliedRunId);
}
```

```java
package com.springclaw.runtime.identity;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.regex.Pattern;

@Component
public final class DefaultRunIdentityFactory implements RunIdentityFactory {
    private static final Pattern NORMALIZED_UUID = Pattern.compile("[0-9a-f]{32}");

    @Override
    public String create() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public String accept(String suppliedRunId) {
        if (suppliedRunId == null || !NORMALIZED_UUID.matcher(suppliedRunId).matches()) {
            throw new IllegalArgumentException(
                    "runId must be exactly 32 lowercase hexadecimal characters"
            );
        }
        return suppliedRunId;
    }
}
```

```java
package com.springclaw.runtime.bridge;

public record AcceptedChatCommand(
        String runId,
        String sessionKey,
        String userId,
        String message,
        String channel,
        String responseMode,
        long acceptedAtEpochMs
) {
    public AcceptedChatCommand {
        requireText(runId, "runId");
        requireText(sessionKey, "sessionKey");
        requireText(userId, "userId");
        message = message == null ? "" : message;
        channel = channel == null || channel.isBlank() ? "api" : channel;
        responseMode = responseMode == null ? "" : responseMode;
        if (acceptedAtEpochMs < 1L) {
            throw new IllegalArgumentException("acceptedAtEpochMs must be positive");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
```

- [ ] **Step 4: Add internal overloads without changing the HTTP DTO**

Add to `ChatService`:

```java
ChatResponse chat(AcceptedChatCommand command);

SseEmitter stream(AcceptedChatCommand command);
```

Inject `RunIdentityFactory` into `ChatContextFactory`, move the existing body into the accepted-command overload, and keep the compatibility method as the sole local generation point:

```java
public ChatContext build(ChatRequest request, boolean persistSession) {
    String runId = runIdentityFactory.create();
    return build(new AcceptedChatCommand(
            runId,
            request.sessionKey(),
            request.userId(),
            request.message(),
            request.channel(),
            request.responseMode(),
            System.currentTimeMillis()
    ), persistSession);
}

public ChatContext build(AcceptedChatCommand command, boolean persistSession) {
    String acceptedRunId = runIdentityFactory.accept(command.runId());
    String channel = StringUtils.hasText(command.channel()) ? command.channel() : "api";
    // Keep the existing session, authorization, decision, routing, skill,
    // context-assembly, provider, and ContextInjection calls unchanged.
    // Replace every request.* accessor with command.* and set ChatContext.requestId
    // to acceptedRunId. Do not call create() in this overload.
}
```

The implementation body must retain the existing call order:

```text
session -> role -> allowed tool packs -> AgentDecisionService ->
ChatRoutingPolicyService -> visible skills -> system prompt ->
ContextAssembler -> active provider -> ChatContext
```

- [ ] **Step 5: Run the tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=DefaultRunIdentityFactoryTest,ChatContextFactoryTest,ContextPropagationCharacterizationTest test
```

Expected: all tests pass; supplied accepted IDs reach `ChatContext.requestId`, and the accepted-command overload never invokes `RunIdentityFactory.create()`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/runtime/identity \
  src/main/java/com/springclaw/runtime/bridge/AcceptedChatCommand.java \
  src/main/java/com/springclaw/service/chat/ChatService.java \
  src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java \
  src/test/java/com/springclaw/runtime/identity/DefaultRunIdentityFactoryTest.java \
  src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java \
  src/test/java/com/springclaw/architecture/ContextPropagationCharacterizationTest.java
git commit -m "feat: propagate canonical accepted run identity"
```

### Task 2: Add the optimistic in-memory run repository

**Files:**
- Create: `src/main/java/com/springclaw/runtime/store/RunStateRepository.java`
- Create: `src/main/java/com/springclaw/runtime/store/InMemoryRunStateRepository.java`
- Test: `src/test/java/com/springclaw/runtime/store/InMemoryRunStateRepositoryTest.java`

- [ ] **Step 1: Write the failing repository tests**

```java
package com.springclaw.runtime.store;

import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRunStateRepositoryTest {

    private final InMemoryRunStateRepository repository = new InMemoryRunStateRepository();

    @Test
    void rejectsDuplicateCreateAndRevisionConflict() {
        RunState created = RunStateTestFixtures.created("11111111111111111111111111111111");
        repository.create(created);

        assertThatThrownBy(() -> repository.create(created))
                .isInstanceOf(RunStateRepository.DuplicateRunException.class);

        RunState contextReady = RunStateTestFixtures.contextReady(created);
        assertThatThrownBy(() -> repository.save(created.runId(), 9L, contextReady))
                .isInstanceOf(RunStateRepository.RevisionConflictException.class);
    }

    @Test
    void validatesTransitionsAndKeepsTerminalStateImmutable() {
        RunState terminal = RunStateTestFixtures.completedLifecycle(repository);

        assertThat(repository.find(terminal.runId())).contains(terminal);
        assertThatThrownBy(() -> repository.save(
                terminal.runId(),
                terminal.revision(),
                RunStateTestFixtures.failedAfterTerminal(terminal)
        )).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("terminal run state is immutable");
    }
}
```

`RunStateTestFixtures` is a package-private nested helper in the test file. It must build complete valid `RunState` values using the Phase 1 constructors and the legal path `CREATED -> CONTEXT_READY -> DECIDED -> RUNNING -> VERIFYING -> COMPLETED`; do not add a production builder or modify `com.springclaw.runtime.contract`.

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -q -Dtest=InMemoryRunStateRepositoryTest test
```

Expected: compilation fails because the repository types do not exist.

- [ ] **Step 3: Implement exact optimistic semantics**

```java
package com.springclaw.runtime.store;

import com.springclaw.runtime.contract.RunState;

import java.util.Optional;

public interface RunStateRepository {
    RunState create(RunState initial);
    Optional<RunState> find(String runId);
    RunState save(String runId, long expectedRevision, RunState next);

    final class DuplicateRunException extends IllegalStateException {
        public DuplicateRunException(String runId) {
            super("run already exists: " + runId);
        }
    }

    final class RevisionConflictException extends IllegalStateException {
        public RevisionConflictException(String runId, long expected, long actual) {
            super("revision conflict for " + runId
                    + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
```

```java
package com.springclaw.runtime.store;

import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunTransitionPolicy;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Repository
public final class InMemoryRunStateRepository implements RunStateRepository {
    private final ConcurrentHashMap<String, RunState> states = new ConcurrentHashMap<>();

    @Override
    public RunState create(RunState initial) {
        RunState previous = states.putIfAbsent(initial.runId(), initial);
        if (previous != null) {
            throw new DuplicateRunException(initial.runId());
        }
        return initial;
    }

    @Override
    public Optional<RunState> find(String runId) {
        return Optional.ofNullable(states.get(runId));
    }

    @Override
    public RunState save(String runId, long expectedRevision, RunState next) {
        AtomicReference<RunState> saved = new AtomicReference<>();
        states.compute(runId, (key, current) -> {
            if (current == null) {
                throw new IllegalStateException("run does not exist: " + runId);
            }
            if (current.revision() != expectedRevision) {
                throw new RevisionConflictException(
                        runId, expectedRevision, current.revision()
                );
            }
            if (!runId.equals(next.runId())) {
                throw new IllegalStateException("save runId must equal next.runId");
            }
            RunTransitionPolicy.validate(current, next);
            saved.set(next);
            return next;
        });
        return saved.get();
    }
}
```

- [ ] **Step 4: Add a 32-thread same-revision race test**

Use `CountDownLatch` to release 32 tasks that all call `save(runId, 0, sameContextReadyState)`. Assert exactly one succeeds, 31 throw `RevisionConflictException`, and stored revision is `1`.

- [ ] **Step 5: Run the repository tests**

Run:

```bash
mvn -q -Dtest=InMemoryRunStateRepositoryTest test
```

Expected: duplicate create, revision conflict, transition validation, terminal immutability, and concurrent compare-and-save tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/runtime/store/RunStateRepository.java \
  src/main/java/com/springclaw/runtime/store/InMemoryRunStateRepository.java \
  src/test/java/com/springclaw/runtime/store/InMemoryRunStateRepositoryTest.java
git commit -m "feat: add optimistic in-memory run repository"
```

### Task 3: Add the ordered idempotent in-memory event store

**Files:**
- Create: `src/main/java/com/springclaw/runtime/store/RunEventStore.java`
- Create: `src/main/java/com/springclaw/runtime/store/InMemoryRunEventStore.java`
- Test: `src/test/java/com/springclaw/runtime/store/InMemoryRunEventStoreTest.java`

- [ ] **Step 1: Write failing event-store tests**

```java
package com.springclaw.runtime.store;

import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunEventType;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.identity.DefaultRunIdentityFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryRunEventStoreTest {

    private final InMemoryRunEventStore store =
            new InMemoryRunEventStore(new DefaultRunIdentityFactory());

    @Test
    void assignsMonotonicSequenceAndReturnsSameEventForSameKey() {
        RunEvent.Draft created = draft(RunEventType.RUN_CREATED, RunStatus.CREATED);
        RunEvent first = store.append("acceptance", created);
        RunEvent duplicate = store.append("acceptance", created);
        RunEvent second = store.append(
                "context-ready",
                draft(RunEventType.CONTEXT_READY, RunStatus.CONTEXT_READY)
        );

        assertThat(duplicate).isEqualTo(first);
        assertThat(store.list(first.runId())).extracting(RunEvent::sequence)
                .containsExactly(1L, 2L);
        assertThat(first.eventId()).matches("[0-9a-f]{32}");
    }

    @Test
    void rejectsReusingAnEventKeyForDifferentDraftContent() {
        store.append("stage-1", draft(RunEventType.RUN_CREATED, RunStatus.CREATED));

        assertThatThrownBy(() -> store.append(
                "stage-1",
                draft(RunEventType.RUN_FAILED, RunStatus.FAILED)
        )).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("event key collision");
    }

    private static RunEvent.Draft draft(
            RunEventType type,
            RunStatus status
    ) {
        return new RunEvent.Draft(
                "11111111111111111111111111111111",
                type,
                "test-stage",
                status,
                Instant.parse("2026-06-21T00:00:00Z"),
                0L,
                "springclaw.test.v1",
                "{}",
                null,
                "11111111111111111111111111111111"
        );
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -q -Dtest=InMemoryRunEventStoreTest test
```

Expected: compilation fails because `RunEventStore` and `InMemoryRunEventStore` do not exist.

- [ ] **Step 3: Implement the store contract aligned with `RunEvent.Draft`**

```java
package com.springclaw.runtime.store;

import com.springclaw.runtime.contract.RunEvent;

import java.util.List;

public interface RunEventStore {
    RunEvent append(String eventKey, RunEvent.Draft draft);
    List<RunEvent> list(String runId);
}
```

```java
package com.springclaw.runtime.store;

import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.identity.RunIdentityFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public final class InMemoryRunEventStore implements RunEventStore {
    private final ConcurrentHashMap<String, Journal> journals = new ConcurrentHashMap<>();
    private final RunIdentityFactory identityFactory;

    public InMemoryRunEventStore(RunIdentityFactory identityFactory) {
        this.identityFactory = identityFactory;
    }

    @Override
    public RunEvent append(String eventKey, RunEvent.Draft draft) {
        if (eventKey == null || eventKey.isBlank()) {
            throw new IllegalArgumentException("eventKey must not be blank");
        }
        return journals.computeIfAbsent(draft.runId(), ignored -> new Journal())
                .append(eventKey, draft, identityFactory);
    }

    @Override
    public List<RunEvent> list(String runId) {
        Journal journal = journals.get(runId);
        return journal == null ? List.of() : journal.snapshot();
    }

    private static final class Journal {
        private final Map<String, StoredDraft> byKey = new LinkedHashMap<>();
        private final List<RunEvent> events = new ArrayList<>();

        synchronized RunEvent append(
                String eventKey,
                RunEvent.Draft draft,
                RunIdentityFactory identityFactory
        ) {
            StoredDraft existing = byKey.get(eventKey);
            if (existing != null) {
                if (!existing.draft().equals(draft)) {
                    throw new IllegalStateException(
                            "event key collision for " + eventKey
                    );
                }
                return existing.event();
            }
            RunEvent event = draft.persisted(
                    identityFactory.create(),
                    events.size() + 1L
            );
            events.add(event);
            byKey.put(eventKey, new StoredDraft(draft, event));
            return event;
        }

        synchronized List<RunEvent> snapshot() {
            return List.copyOf(events);
        }
    }

    private record StoredDraft(RunEvent.Draft draft, RunEvent event) {
    }
}
```

- [ ] **Step 4: Add concurrency coverage**

Launch 64 tasks against one run: 32 unique keys plus 32 calls using the same `"duplicate"` key and identical draft. Assert:

```java
assertThat(store.list(runId)).hasSize(33);
assertThat(store.list(runId)).extracting(RunEvent::sequence)
        .containsExactlyElementsOf(
                java.util.stream.LongStream.rangeClosed(1, 33).boxed().toList()
        );
assertThat(duplicateResults).allMatch(event -> event.equals(duplicateResults.get(0)));
```

- [ ] **Step 5: Run the event-store tests**

Run:

```bash
mvn -q -Dtest=InMemoryRunEventStoreTest test
```

Expected: sequence assignment is per run, monotonic under concurrency, and idempotent by `(runId, eventKey, draft value)`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/runtime/store/RunEventStore.java \
  src/main/java/com/springclaw/runtime/store/InMemoryRunEventStore.java \
  src/test/java/com/springclaw/runtime/store/InMemoryRunEventStoreTest.java
git commit -m "feat: add ordered in-memory run event store"
```

### Task 4: Characterize the legacy strategy boundary and adapters

**Files:**
- Create: `src/main/java/com/springclaw/runtime/bridge/LegacyChatExecutor.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/LegacyExecutionObserver.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/LegacyChatExecution.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/LegacyRunContextAdapter.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/LegacyExecutionDecisionAdapter.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/LegacyRunResultAdapter.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/LegacyEngineMetadataRegistry.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/LegacyRuntimeStrategy.java`
- Create: `src/test/java/com/springclaw/runtime/bridge/LegacyBridgeTestFixtures.java`
- Test: `src/test/java/com/springclaw/runtime/bridge/LegacyRuntimeAdaptersTest.java`

- [ ] **Step 1: Write failing adapter and metadata tests**

```java
package com.springclaw.runtime.bridge;

import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.ExecutionDecision;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.service.chat.impl.ChatContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyRuntimeAdaptersTest {

    @Test
    void adaptersUseAlreadyProducedLegacyContextDecisionAndResponse() {
        ChatContext context = LegacyBridgeTestFixtures.context(
                "11111111111111111111111111111111"
        );
        ChatResponse response = new ChatResponse(
                "s1", "exact legacy answer", "primary:model-x", 1_750_464_123_456L
        );

        ContextSnapshot snapshot = new LegacyRunContextAdapter().adapt(context);
        ExecutionDecision decision =
                new LegacyExecutionDecisionAdapter().adapt(context);
        LegacyRunResultAdapter.TerminalProjection terminal =
                new LegacyRunResultAdapter().adapt(context.requestId(), response);

        assertThat(snapshot.runId()).isEqualTo(context.requestId());
        assertThat(snapshot.effectiveMessage())
                .isEqualTo(context.effectiveUserMessage());
        assertThat(decision.intent()).isEqualTo(context.decision().intent());
        assertThat(decision.strategyRequirements())
                .containsEntry("executionPath", context.decision().executionPath());
        assertThat(terminal.response()).isSameAs(response);
        assertThat(terminal.result().answer()).isEqualTo(response.answer());
        assertThat(terminal.result().modelId()).isEqualTo(response.model());
        assertThat(terminal.result().completedAt())
                .isEqualTo(Instant.ofEpochMilli(response.timestamp()));
        assertThat(terminal.result().status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void registryPinsVersionedLegacyRanksWithoutSelectingAnEngine() {
        LegacyEngineMetadataRegistry registry = new LegacyEngineMetadataRegistry();

        assertThat(registry.all()).containsExactly(
                new LegacyEngineMetadataRegistry.LegacyEngineMetadata(
                        "basic-stream", 1, "legacy-rank-v1"),
                new LegacyEngineMetadataRegistry.LegacyEngineMetadata(
                        "autonomous-loop", 2, "legacy-rank-v1"),
                new LegacyEngineMetadataRegistry.LegacyEngineMetadata(
                        "agent-runtime", 2, "legacy-rank-v1"),
                new LegacyEngineMetadataRegistry.LegacyEngineMetadata(
                        "opar-loop", 3, "legacy-rank-v1"),
                new LegacyEngineMetadataRegistry.LegacyEngineMetadata(
                        "model-led-stream", 5, "legacy-rank-v1"),
                new LegacyEngineMetadataRegistry.LegacyEngineMetadata(
                        "simplified", 10, "legacy-rank-v1")
        );
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -q -Dtest=LegacyRuntimeAdaptersTest test
```

Expected: compilation fails because the legacy bridge types do not exist.

- [ ] **Step 3: Add concrete legacy test fixtures**

```java
package com.springclaw.runtime.bridge;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.mockito.Mockito.mock;

public final class LegacyBridgeTestFixtures {
    private LegacyBridgeTestFixtures() {
    }

    public static ChatContext context(String runId) {
        AgentSession session = new AgentSession();
        session.setId(1L);
        session.setSessionKey("s1");
        session.setChannel("api");
        session.setUserId("u1");
        session.setStatus("ACTIVE");
        AssembledContext assembled = new AssembledContext(
                "s1", "api", "u1", "effective question",
                "event context", "semantic context", "observe prompt"
        );
        AgentDecision decision = new AgentDecision(
                "general", "basic_model", List.of(), "read", false,
                "legacy route"
        );
        return new ChatContext(
                session,
                "api",
                "u1",
                "USER",
                "original question",
                "effective question",
                runId,
                "system prompt",
                assembled,
                new AiProviderService.ActiveChatClient(
                        "primary", "model-x", "http://localhost",
                        mock(ChatClient.class), true, ""
                ),
                "simplified",
                "legacy route",
                "agent",
                "general",
                decision,
                ContextInjection.empty()
        );
    }

    public static LegacyChatExecution execution(
            ChatContext context,
            ChatResponse response,
            String engineName
    ) {
        ChatExecutionResult executionResult = new ChatExecutionResult(
                "observe", "plan", "action", response.answer(), true
        );
        return new LegacyChatExecution(
                context, executionResult, response, engineName
        );
    }
}
```

- [ ] **Step 4: Define the non-recursive legacy execution port**

```java
package com.springclaw.runtime.bridge;

@FunctionalInterface
public interface LegacyChatExecutor {
    LegacyChatExecution execute(
            AcceptedChatCommand command,
            LegacyExecutionObserver observer
    );
}
```

```java
package com.springclaw.runtime.bridge;

import com.springclaw.service.chat.impl.ChatContext;

public interface LegacyExecutionObserver {
    void onContextReady(ChatContext context);
    void onStrategySelected(String engineName);

    static LegacyExecutionObserver noop() {
        return new LegacyExecutionObserver() {
            @Override public void onContextReady(ChatContext context) { }
            @Override public void onStrategySelected(String engineName) { }
        };
    }
}
```

```java
package com.springclaw.runtime.bridge;

import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;

public record LegacyChatExecution(
        ChatContext context,
        ChatExecutionResult executionResult,
        ChatResponse response,
        String engineName
) {
}
```

`LegacyRuntimeStrategy` is deliberately a bridge adapter, not a replacement selector and not a second implementation of the Phase 1 `RuntimeStrategy` API:

```java
package com.springclaw.runtime.bridge;

import org.springframework.stereotype.Component;

@Component
public final class LegacyRuntimeStrategy {
    public static final String STRATEGY_ID = "legacy-runtime-v1";

    public String strategyId() {
        return STRATEGY_ID;
    }

    public LegacyChatExecution execute(
            AcceptedChatCommand command,
            LegacyExecutionObserver observer,
            LegacyChatExecutor executor
    ) {
        return executor.execute(command, observer);
    }
}
```

This boundary prevents `RunCoordinator -> ChatService.chat() -> RunCoordinator` recursion. `RunCoordinator` receives a method reference to a package-private `ChatServiceImpl.executeLegacyAccepted(...)` method; the legacy method never calls a public coordinator-enabled entry point.

- [ ] **Step 5: Implement characterized adapters**

`LegacyRunContextAdapter.adapt(ChatContext)` must map:

```java
return new ContextSnapshot(
        context.requestId(),
        context.session().getSessionKey(),
        hasText(context.session().getUserId())
                ? context.session().getUserId() : context.userId(),
        context.channel(),
        context.userId(),
        context.roleCode(),
        context.userMessage(),
        context.effectiveUserMessage(),
        context.systemPrompt(),
        "",
        singletonIfText(context.assembled().eventContext()),
        singletonIfText(context.assembled().semanticContext()),
        List.of(),
        context.decision() == null
                ? List.of() : context.decision().selectedCapabilities(),
        context.activeClient() == null ? Map.of() : Map.of(
                "providerId", safe(context.activeClient().providerId()),
                "model", safe(context.activeClient().model()),
                "available", Boolean.toString(context.activeClient().available())
        ),
        contextSourceSummary(context.assembled().sourceSummary()),
        Instant.now(),
        sha256(context.requestId() + "\n"
                + safe(context.assembled().observePrompt()) + "\n"
                + safe(context.systemPrompt()))
);
```

The empty `memoryBankText` and `activeLearningRules` are characterized adapter fields because the current `ChatContext` does not expose those values separately. The adapter must not call `ContextAssembler`, memory services, routing services, or any model.

`LegacyExecutionDecisionAdapter.adapt(ChatContext)` must map the existing `AgentDecision`:

```java
AgentDecision legacy = context.decision() == null
        ? AgentDecision.general(context.routingReason())
        : context.decision();
return new ExecutionDecision(
        context.requestId(),
        legacy.intent(),
        context.effectiveUserMessage(),
        hasText(context.responseMode()) ? context.responseMode() : "agent",
        legacy.riskLevel(),
        legacy.selectedCapabilities(),
        List.of(),
        Map.of(
                "executionPath", legacy.executionPath(),
                "legacyRankVersion", LegacyEngineMetadataRegistry.RANK_VERSION
        ),
        List.of(),
        1.0d,
        legacy.reason(),
        "legacy-agent-decision-v1",
        Instant.now()
);
```

`LegacyRunResultAdapter` must preserve the exact response object and values:

```java
public TerminalProjection adapt(String runId, ChatResponse response) {
    Instant completedAt = Instant.ofEpochMilli(response.timestamp());
    CompletionDecision completion = new CompletionDecision(
            runId,
            CompletionDecision.Outcome.COMPLETE,
            "legacy-response-returned",
            "The existing blocking ChatService path returned normally.",
            List.of(),
            List.of(),
            false,
            0,
            1.0d,
            completedAt
    );
    RunResult result = new RunResult(
            runId,
            RunStatus.COMPLETED,
            response.answer(),
            RunResult.AnswerKind.FINAL,
            "",
            response.model(),
            List.of(),
            List.of(),
            1.0d,
            Map.of(),
            "",
            "",
            completedAt
    );
    return new TerminalProjection(response, completion, result);
}

public ChatResponse toChatResponse(RunState state) {
    return new ChatResponse(
            state.sessionKey(),
            state.result().answer(),
            state.result().modelId(),
            state.result().completedAt().toEpochMilli()
    );
}

public record TerminalProjection(
        ChatResponse response,
        CompletionDecision completionDecision,
        RunResult result
) {
}
```

- [ ] **Step 6: Implement the fixed metadata registry**

```java
package com.springclaw.runtime.bridge;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public final class LegacyEngineMetadataRegistry {
    public static final String RANK_VERSION = "legacy-rank-v1";

    private static final List<LegacyEngineMetadata> ENGINES = List.of(
            new LegacyEngineMetadata("basic-stream", 1, RANK_VERSION),
            new LegacyEngineMetadata("autonomous-loop", 2, RANK_VERSION),
            new LegacyEngineMetadata("agent-runtime", 2, RANK_VERSION),
            new LegacyEngineMetadata("opar-loop", 3, RANK_VERSION),
            new LegacyEngineMetadata("model-led-stream", 5, RANK_VERSION),
            new LegacyEngineMetadata("simplified", 10, RANK_VERSION)
    );

    public List<LegacyEngineMetadata> all() {
        return ENGINES;
    }

    public LegacyEngineMetadata require(String engineId) {
        return ENGINES.stream()
                .filter(metadata -> metadata.engineId().equals(engineId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown legacy engine: " + engineId
                ));
    }

    public record LegacyEngineMetadata(
            String engineId,
            int legacyRank,
            String rankVersion
    ) {
    }
}
```

The registry records compatibility metadata only. Do not inject it into `EngineSelector`, sort engines with it, or alter equal-rank behavior.

- [ ] **Step 7: Run the adapter tests**

Run:

```bash
mvn -q -Dtest=LegacyRuntimeAdaptersTest test
```

Expected: adapters preserve legacy values and the registry exposes the six current engine names/ranks without invoking route selection.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/springclaw/runtime/bridge/LegacyChatExecutor.java \
  src/main/java/com/springclaw/runtime/bridge/LegacyExecutionObserver.java \
  src/main/java/com/springclaw/runtime/bridge/LegacyChatExecution.java \
  src/main/java/com/springclaw/runtime/bridge/LegacyRunContextAdapter.java \
  src/main/java/com/springclaw/runtime/bridge/LegacyExecutionDecisionAdapter.java \
  src/main/java/com/springclaw/runtime/bridge/LegacyRunResultAdapter.java \
  src/main/java/com/springclaw/runtime/bridge/LegacyEngineMetadataRegistry.java \
  src/main/java/com/springclaw/runtime/bridge/LegacyRuntimeStrategy.java \
  src/test/java/com/springclaw/runtime/bridge/LegacyBridgeTestFixtures.java \
  src/test/java/com/springclaw/runtime/bridge/LegacyRuntimeAdaptersTest.java
git commit -m "feat: define legacy runtime bridge adapters"
```

### Task 5: Implement the canonical bridge lifecycle coordinator

**Files:**
- Create: `src/main/java/com/springclaw/runtime/bridge/LegacyRunStateFactory.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/RunCoordinator.java`
- Test: `src/test/java/com/springclaw/runtime/bridge/RunCoordinatorTest.java`

- [ ] **Step 1: Write the failing success, failure, and duplicate tests**

```java
package com.springclaw.runtime.bridge;

import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.identity.DefaultRunIdentityFactory;
import com.springclaw.runtime.store.InMemoryRunEventStore;
import com.springclaw.runtime.store.InMemoryRunStateRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunCoordinatorTest {

    @Test
    void ownsTheCompleteBridgeLifecycleAndExactLegacyResponse() {
        Fixture fixture = new Fixture();
        AcceptedChatCommand command = fixture.command();
        ChatResponse expected = new ChatResponse(
                "s1", "legacy answer", "primary:model-x", 1_750_464_123_456L
        );
        LegacyChatExecutor executor = (accepted, observer) -> {
            var context = LegacyBridgeTestFixtures.context(accepted.runId());
            observer.onContextReady(context);
            observer.onStrategySelected("simplified");
            return LegacyBridgeTestFixtures.execution(context, expected, "simplified");
        };

        ChatResponse actual = fixture.coordinator.execute(command, executor);

        assertThat(actual).isSameAs(expected);
        assertThat(fixture.repository.find(command.runId()).orElseThrow().status())
                .isEqualTo(RunStatus.COMPLETED);
        assertThat(fixture.events.list(command.runId()))
                .extracting(RunEvent::status)
                .containsExactly(
                        RunStatus.CREATED,
                        RunStatus.CONTEXT_READY,
                        RunStatus.DECIDED,
                        RunStatus.RUNNING,
                        RunStatus.VERIFYING,
                        RunStatus.COMPLETED
                );
        assertThat(fixture.events.list(command.runId()))
                .extracting(RunEvent::sequence)
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(fixture.repository.find(command.runId()).orElseThrow().revision())
                .isEqualTo(5L);
    }

    @Test
    void recordsTypedFailureWithoutRepairingOrReplacingTheException() {
        Fixture fixture = new Fixture();

        assertThatThrownBy(() -> fixture.coordinator.execute(
                fixture.command(),
                (command, observer) -> {
                    throw new IllegalStateException("legacy exploded");
                }
        )).isInstanceOf(IllegalStateException.class)
          .hasMessage("legacy exploded");

        var failed = fixture.repository.find(fixture.command().runId()).orElseThrow();
        assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
        assertThat(failed.failure().code()).isEqualTo("IllegalStateException");
        assertThat(failed.failure().message()).isEqualTo("legacy exploded");
        assertThat(failed.result()).isNull();
    }

    @Test
    void duplicateAcceptanceReusesTerminalRunAndDoesNotExecuteTwice() {
        Fixture fixture = new Fixture();
        AtomicInteger executions = new AtomicInteger();
        LegacyChatExecutor executor = (accepted, observer) -> {
            executions.incrementAndGet();
            var context = LegacyBridgeTestFixtures.context(accepted.runId());
            observer.onContextReady(context);
            observer.onStrategySelected("simplified");
            return LegacyBridgeTestFixtures.execution(
                    context,
                    new ChatResponse("s1", "same", "primary:model-x", 1_750_464_123_456L),
                    "simplified"
            );
        };

        ChatResponse first = fixture.coordinator.execute(fixture.command(), executor);
        ChatResponse second = fixture.coordinator.execute(fixture.command(), executor);

        assertThat(executions).hasValue(1);
        assertThat(second).isEqualTo(first);
        assertThat(fixture.events.list(fixture.command().runId())).hasSize(6);
    }

    private static final class Fixture {
        private static final String RUN_ID =
                "11111111111111111111111111111111";
        private final InMemoryRunStateRepository repository =
                new InMemoryRunStateRepository();
        private final InMemoryRunEventStore events =
                new InMemoryRunEventStore(new DefaultRunIdentityFactory());
        private final RunCoordinator coordinator = new RunCoordinator(
                repository,
                events,
                new LegacyRuntimeStrategy(),
                new LegacyRunContextAdapter(),
                new LegacyExecutionDecisionAdapter(),
                new LegacyRunResultAdapter(),
                new LegacyRunStateFactory(),
                new LegacyEngineMetadataRegistry(),
                Clock.fixed(
                        Instant.parse("2026-06-21T00:00:00Z"),
                        ZoneOffset.UTC
                )
        );

        private AcceptedChatCommand command() {
            return new AcceptedChatCommand(
                    RUN_ID,
                    "s1",
                    "u1",
                    "hello",
                    "api",
                    "agent",
                    Instant.parse("2026-06-21T00:00:00Z").toEpochMilli()
            );
        }
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -q -Dtest=RunCoordinatorTest test
```

Expected: compilation fails because `LegacyRunStateFactory` and `RunCoordinator` do not exist.

- [ ] **Step 3: Implement legal state construction without changing contracts**

`LegacyRunStateFactory` must expose these exact methods:

```java
RunState created(AcceptedChatCommand command, Instant now);
RunState contextReady(RunState previous, ContextSnapshot snapshot, Instant now);
RunState decided(RunState previous, ExecutionDecision decision, Instant now);
RunState running(RunState previous, String strategyId, Instant now);
RunState verifying(RunState previous, Instant now);
RunState completed(
        RunState previous,
        CompletionDecision completion,
        RunResult result,
        Instant now
);
RunState failed(RunState previous, Throwable failure, Instant now);
```

Every method must invoke the full `RunState` constructor, copy immutable acceptance/history fields exactly, increment revision by one except `created` which starts at `0`, and use:

```java
responseMode = command.responseMode().isBlank() ? "agent" : command.responseMode();
acceptedAt = Instant.ofEpochMilli(command.acceptedAtEpochMs());
deadlineAt = acceptedAt.plusSeconds(1_800);
attempt = 1;
strategyId = "legacy-runtime-v1/" + engineName;
failure = new RunState.Failure(
        throwable.getClass().getSimpleName(),
        throwable.getMessage() == null ? "" : throwable.getMessage(),
        false
);
```

`contextReady` attaches the adapter snapshot, `decided` attaches the adapter decision, `running` sets `startedAt` if absent, `verifying` retains legacy evidence without inventing a `CompletionDecision`, `completed` attaches the exact terminal projection, and `failed` leaves `RunResult` null.

- [ ] **Step 4: Implement coordinator ownership and idempotent duplicate claims**

```java
package com.springclaw.runtime.bridge;

import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunEventType;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.store.RunEventStore;
import com.springclaw.runtime.store.RunStateRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public final class RunCoordinator {
    private final RunStateRepository stateRepository;
    private final RunEventStore eventStore;
    private final LegacyRuntimeStrategy strategy;
    private final LegacyRunContextAdapter contextAdapter;
    private final LegacyExecutionDecisionAdapter decisionAdapter;
    private final LegacyRunResultAdapter resultAdapter;
    private final LegacyRunStateFactory stateFactory;
    private final LegacyEngineMetadataRegistry metadataRegistry;
    private final Clock clock;
    private final ConcurrentHashMap<String, Object> runLocks = new ConcurrentHashMap<>();

    public RunCoordinator(
            RunStateRepository stateRepository,
            RunEventStore eventStore,
            LegacyRuntimeStrategy strategy,
            LegacyRunContextAdapter contextAdapter,
            LegacyExecutionDecisionAdapter decisionAdapter,
            LegacyRunResultAdapter resultAdapter,
            LegacyRunStateFactory stateFactory,
            LegacyEngineMetadataRegistry metadataRegistry
    ) {
        this(
                stateRepository,
                eventStore,
                strategy,
                contextAdapter,
                decisionAdapter,
                resultAdapter,
                stateFactory,
                metadataRegistry,
                Clock.systemUTC()
        );
    }

    RunCoordinator(
            RunStateRepository stateRepository,
            RunEventStore eventStore,
            LegacyRuntimeStrategy strategy,
            LegacyRunContextAdapter contextAdapter,
            LegacyExecutionDecisionAdapter decisionAdapter,
            LegacyRunResultAdapter resultAdapter,
            LegacyRunStateFactory stateFactory,
            LegacyEngineMetadataRegistry metadataRegistry,
            Clock clock
    ) {
        this.stateRepository = stateRepository;
        this.eventStore = eventStore;
        this.strategy = strategy;
        this.contextAdapter = contextAdapter;
        this.decisionAdapter = decisionAdapter;
        this.resultAdapter = resultAdapter;
        this.stateFactory = stateFactory;
        this.metadataRegistry = metadataRegistry;
        this.clock = clock;
    }

    public ChatResponse execute(
            AcceptedChatCommand command,
            LegacyChatExecutor executor
    ) {
        Object lock = runLocks.computeIfAbsent(command.runId(), ignored -> new Object());
        synchronized (lock) {
            try {
                RunState existing = stateRepository.find(command.runId()).orElse(null);
                if (existing != null) {
                    if (existing.status() == com.springclaw.runtime.contract.RunStatus.COMPLETED) {
                        return resultAdapter.toChatResponse(existing);
                    }
                    if (existing.status() == com.springclaw.runtime.contract.RunStatus.FAILED) {
                        throw new IllegalStateException(existing.failure().message());
                    }
                    throw new IllegalStateException(
                            "run is already in progress: " + command.runId()
                    );
                }

                RunState created = stateFactory.created(command, clock.instant());
                stateRepository.create(created);
                append("run.created", created, RunEventType.RUN_CREATED, "acceptance");

                CoordinatorObserver observer = new CoordinatorObserver(created);
                LegacyChatExecution legacy =
                        strategy.execute(command, observer, executor);
                RunState running = observer.current();
                if (running.status() != com.springclaw.runtime.contract.RunStatus.RUNNING) {
                    throw new IllegalStateException(
                            "legacy executor did not report context and strategy"
                    );
                }

                RunState verifying = save(
                        stateFactory.verifying(running, clock.instant()),
                        RunEventType.VERIFICATION_COMPLETED,
                        "legacy-boundary"
                );
                LegacyRunResultAdapter.TerminalProjection terminal =
                        resultAdapter.adapt(command.runId(), legacy.response());
                RunState completed = save(
                        stateFactory.completed(
                                verifying,
                                terminal.completionDecision(),
                                terminal.result(),
                                terminal.result().completedAt()
                        ),
                        RunEventType.RUN_COMPLETED,
                        "terminal"
                );
                return terminal.response();
            } catch (RuntimeException | Error failure) {
                failIfPossible(command.runId(), failure);
                throw failure;
            } finally {
                runLocks.remove(command.runId(), lock);
            }
        }
    }
}
```

Implement `CoordinatorObserver` as a private inner class:

```java
@Override
public void onContextReady(ChatContext context) {
    ContextSnapshot snapshot = contextAdapter.adapt(context);
    current = save(
            stateFactory.contextReady(current, snapshot, clock.instant()),
            RunEventType.CONTEXT_READY,
            "legacy-context"
    );
    ExecutionDecision decision = decisionAdapter.adapt(context);
    current = save(
            stateFactory.decided(current, decision, clock.instant()),
            RunEventType.DECISION_MADE,
            "legacy-decision"
    );
}

@Override
public void onStrategySelected(String engineName) {
    LegacyEngineMetadataRegistry.LegacyEngineMetadata metadata =
            metadataRegistry.require(engineName);
    current = save(
            stateFactory.running(
                    current,
                    strategy.strategyId() + "/" + metadata.engineId(),
                    clock.instant()
            ),
            RunEventType.STRATEGY_STARTED,
            "legacy-execution"
    );
}
```

`save(...)` must call `stateRepository.save(runId, previousRevision, next)` before appending the event. `append(...)` must use stable event keys shown below so retries do not duplicate facts:

```text
run.created
context.ready
decision.made
strategy.started
verification.completed
run.completed
run.failed
```

Each `RunEvent.Draft` uses `correlationId = runId`, `payloadSchema = "springclaw.legacy-bridge.v1"`, and compact payloads containing only characterized bridge metadata such as engine name/rank. Do not serialize prompts, answers, tool arguments, or a synthetic context/decision.

`failIfPossible` must load the latest nonterminal state, transition it directly to `FAILED` (legal from every nonterminal Phase 1 status), append `run.failed`, and never replace an already terminal state.

- [ ] **Step 5: Add concurrent duplicate-delivery coverage**

Run two threads with the same accepted command and an executor blocked by latches. Assert the second waits for the per-run monitor, receives the reconstructed completed response, and `executions == 1`.

- [ ] **Step 6: Run coordinator tests**

Run:

```bash
mvn -q -Dtest=RunCoordinatorTest test
```

Expected: success follows `CREATED -> CONTEXT_READY -> DECIDED -> RUNNING -> VERIFYING -> COMPLETED`; failure is typed and terminal; duplicate acceptance does not create, execute, revise, or append twice.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/springclaw/runtime/bridge/LegacyRunStateFactory.java \
  src/main/java/com/springclaw/runtime/bridge/RunCoordinator.java \
  src/test/java/com/springclaw/runtime/bridge/RunCoordinatorTest.java
git commit -m "feat: coordinate canonical legacy bridge lifecycle"
```

### Task 6: Extract the blocking legacy executor and activate sync behind one flag

**Files:**
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java`
- Modify: `src/main/resources/application.yml`
- Modify: `.env.example`
- Create: `src/test/java/com/springclaw/service/chat/impl/UnifiedBridgeActivationTest.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatServiceImplModeTest.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPersistenceTest.java`

- [ ] **Step 1: Write failing flag-state and payload-parity tests**

```java
package com.springclaw.service.chat.impl;

import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.runtime.bridge.RunCoordinator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnifiedBridgeActivationTest {

    @Test
    void disabledFlagUsesExactLegacyBlockingPath() {
        Fixture fixture = Fixture.bridgeEnabled(false);
        ChatResponse expected = new ChatResponse("s1", "answer", "model", 123L);
        fixture.stubLegacyResponse(expected);

        ChatResponse actual = fixture.service.chat(
                new ChatRequest("s1", "u1", "hello", "api", "agent")
        );

        assertThat(actual).isSameAs(expected);
        verify(fixture.coordinator, never()).execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void enabledFlagReturnsTheExactCoordinatorResponseWithoutDtoChanges() {
        Fixture fixture = Fixture.bridgeEnabled(true);
        ChatResponse expected = new ChatResponse("s1", "answer", "model", 123L);
        when(fixture.coordinator.execute(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(expected);

        ChatResponse actual = fixture.service.chat(
                new ChatRequest("s1", "u1", "hello", "api", "agent")
        );

        assertThat(actual).isSameAs(expected);
        assertThat(ChatResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("sessionKey", "answer", "model", "timestamp");
    }
}
```

Build the nested `Fixture` by extracting the existing eleven dependency mocks and constructor setup from `ChatServiceImplModeTest` into a package-private `ChatServiceImplTestFixture`. Add `RunIdentityFactory`, `RunCoordinator`, and `boolean unifiedBridgeEnabled` parameters to that fixture; its constructor must call the package-visible `ChatServiceImpl` test constructor added in Step 4. `stubLegacyResponse` must stub the same engine/context/persister collaborators already used by `ChatServiceImplModeTest`, not bypass `executeLegacyAccepted`.

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
mvn -q -Dtest=UnifiedBridgeActivationTest,ChatServiceImplModeTest,ChatServiceImplPersistenceTest test
```

Expected: compilation fails because `ChatServiceImpl` has no accepted-command overload, no coordinator, and no bridge flag.

- [ ] **Step 3: Add configuration with a false default**

Add under `springclaw` in `application.yml`:

```yaml
  runtime:
    unified-bridge-enabled: ${SPRINGCLAW_RUNTIME_UNIFIED_BRIDGE_ENABLED:false}
```

Add to `.env.example`:

```properties
# Canonical in-memory blocking runtime bridge. Keep false for immediate rollback.
SPRINGCLAW_RUNTIME_UNIFIED_BRIDGE_ENABLED=false
```

- [ ] **Step 4: Separate public entry from the non-recursive legacy boundary**

Inject `RunIdentityFactory`, `RunCoordinator`, and:

```java
@Value("${springclaw.runtime.unified-bridge-enabled:false}")
boolean unifiedBridgeEnabled
```

Keep the existing eleven-argument test constructor and make it delegate with `new DefaultRunIdentityFactory()`, `null` coordinator, and `false`. Add this package-visible constructor for bridge tests:

```java
ChatServiceImpl(
        AiProviderService aiProviderService,
        ChatGuardService chatGuardService,
        OparLoopEngine oparLoopEngine,
        SimplifiedOparEngine simplifiedOparEngine,
        ChatResponsePolicyService chatResponsePolicyService,
        ModelTransportGuardService modelTransportGuardService,
        LlmUsageRecordService llmUsageRecordService,
        ConversationAdvisorSupport conversationAdvisorSupport,
        ChatContextFactory chatContextFactory,
        ChatResultPersister chatResultPersister,
        MetaGuardExecutor metaGuardExecutor,
        RunIdentityFactory runIdentityFactory,
        RunCoordinator runCoordinator,
        boolean unifiedBridgeEnabled
) {
    this(
            aiProviderService, chatGuardService, oparLoopEngine,
            simplifiedOparEngine, chatResponsePolicyService,
            modelTransportGuardService, llmUsageRecordService,
            conversationAdvisorSupport, chatContextFactory,
            chatResultPersister, metaGuardExecutor,
            null, null, null, null, null, null, null,
            runIdentityFactory, runCoordinator,
            unifiedBridgeEnabled, false, true
    );
}
```

Implement:

```java
@Override
public ChatResponse chat(ChatRequest request) {
    return chat(accept(request));
}

@Override
public ChatResponse chat(AcceptedChatCommand command) {
    if (!unifiedBridgeEnabled) {
        return executeLegacyAccepted(
                command,
                LegacyExecutionObserver.noop()
        ).response();
    }
    return runCoordinator.execute(command, this::executeLegacyAccepted);
}

private AcceptedChatCommand accept(ChatRequest request) {
    return new AcceptedChatCommand(
            runIdentityFactory.create(),
            request.sessionKey(),
            request.userId(),
            request.message(),
            request.channel(),
            request.responseMode(),
            System.currentTimeMillis()
    );
}
```

Extract the current blocking implementation into this package-private method:

```java
LegacyChatExecution executeLegacyAccepted(
        AcceptedChatCommand command,
        LegacyExecutionObserver observer
) {
    TaskChatExecutionResult result = executeInternal(
            command,
            true,
            true,
            observer
    );
    ChatResponse response = new ChatResponse(
            result.sessionKey(),
            result.answer(),
            aiProviderService.activeClient().displayName(),
            System.currentTimeMillis()
    );
    return new LegacyChatExecution(
            result.context(),
            result.executionResult(),
            response,
            result.engineName()
    );
}
```

Change the private blocking method signature to:

```java
private TaskChatExecutionResult executeInternal(
        AcceptedChatCommand command,
        boolean enforceRateLimit,
        boolean persistResult,
        LegacyExecutionObserver observer
)
```

Inside it:

```java
ChatContext context = chatContextFactory.build(command, persistResult);
observer.onContextReady(context);
AgentEngine engine = engineSelector.select(context);
observer.onStrategySelected(engine.name());
ChatExecutionResult executionResult = runAgentExecution(context, engine);
```

Change `runAgentExecution` to accept the already-selected engine:

```java
private ChatExecutionResult runAgentExecution(
        ChatContext context,
        AgentEngine engine
) {
    if (engine instanceof AgentRuntimeEngine runtimeEngine) {
        AgentRun run = runtimeEngine.run(context);
        if (sseEventBridge != null) {
            sseEventBridge.recordRunTrace(context, run);
        }
        return run.executionResult();
    }
    return engine.execute(context, metaGuardExecutor::fallbackAnswer);
}
```

Do not change `resolveFinalAnswer`, `ChatResultPersister.persist`, guard acquisition/release, engine execution, fallback behavior, or the order that creates `ChatResponse`.

Update `TaskChatExecutionResult`:

```java
record TaskChatExecutionResult(
        String sessionKey,
        String answer,
        String requestId,
        String executionMode,
        String routingReason,
        ChatContext context,
        ChatExecutionResult executionResult,
        String engineName
) {
}
```

`executeTaskMessage(ChatRequest, boolean)` must call `executeInternal(accept(request), false, persistResult, LegacyExecutionObserver.noop())`; it remains outside the canonical coordinator because scheduled-task execution is not an accepted HTTP/Rabbit transport in Phase 2A.

- [ ] **Step 5: Keep SSE lifecycle disabled while accepting canonical identity**

Implement:

```java
@Override
public SseEmitter stream(ChatRequest request) {
    return stream(accept(request));
}

@Override
public SseEmitter stream(AcceptedChatCommand command) {
    ChatRequest request = new ChatRequest(
            command.sessionKey(),
            command.userId(),
            command.message(),
            command.channel(),
            command.responseMode()
    );
    // Keep all existing rate limit, lock, emitter callback, async scheduling,
    // engine-owned streaming, persistence, and completion behavior unchanged.
    CompletableFuture.runAsync(() -> executeStream(
            command, request, lockToken, lockReleased, emitter, disposableRef
    ));
    return emitter;
}
```

Change only the context-build line in `executeStream`:

```java
ChatContext context = chatContextFactory.build(command, true);
```

Do not call `RunCoordinator` from `stream`, do not append canonical lifecycle events for SSE, and do not move stream completion or lock release. This avoids dual stream ownership until the separate SSE projector plan.

- [ ] **Step 6: Run flag and existing blocking tests**

Run:

```bash
mvn -q \
  -Dtest=UnifiedBridgeActivationTest,ChatServiceImplModeTest,ChatServiceImplPersistenceTest,ChatServiceImplPendingApprovalTest \
  test
```

Expected: flag off returns the exact existing response path; flag on delegates only blocking chat to the coordinator; answer/persistence/guard tests remain unchanged.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java \
  src/main/resources/application.yml \
  .env.example \
  src/test/java/com/springclaw/service/chat/impl/UnifiedBridgeActivationTest.java \
  src/test/java/com/springclaw/service/chat/impl/ChatServiceImplModeTest.java \
  src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPersistenceTest.java
git commit -m "feat: activate blocking unified runtime bridge"
```

### Task 7: Reuse the accepted ID in async delivery, SSE context, audit, and proposal ownership

**Files:**
- Modify: `src/main/java/com/springclaw/controller/ChatController.java`
- Modify: `src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/OparLoopEngine.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/SimplifiedOparEngine.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java`
- Modify: `src/main/java/com/springclaw/service/agent/executor/WorkspaceCapabilityExecutor.java`
- Modify: `src/main/java/com/springclaw/service/agent/executor/LocalFilesCapabilityExecutor.java`
- Modify: `src/main/java/com/springclaw/service/agent/executor/WebCapabilityExecutor.java`
- Modify: `src/main/java/com/springclaw/service/agent/executor/SystemHealthCapabilityExecutor.java`
- Modify: `src/main/java/com/springclaw/service/agent/executor/SkillCapabilityExecutor.java`
- Modify: `src/main/java/com/springclaw/service/agent/executor/RealtimeCapabilityExecutor.java`
- Create: `src/test/java/com/springclaw/service/chat/async/ChatMessageConsumerCanonicalIdentityTest.java`
- Modify: `src/test/java/com/springclaw/architecture/TransportParityCharacterizationTest.java`
- Modify: `src/test/java/com/springclaw/controller/ChatControllerAuthTest.java`

- [ ] **Step 1: Write failing async redelivery and ownership tests**

```java
package com.springclaw.service.chat.async;

import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.runtime.bridge.AcceptedChatCommand;
import com.springclaw.service.chat.ChatService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMessageConsumerCanonicalIdentityTest {

    @Test
    void repeatedDeliveryReusesMessageRequestIdWithoutGeneratingAnotherId() {
        ChatService chatService = mock(ChatService.class);
        Fixture fixture = new Fixture(chatService);
        AsyncChatRequestMessage message = fixture.message(
                "11111111111111111111111111111111"
        );
        when(chatService.chat(org.mockito.ArgumentMatchers.any(AcceptedChatCommand.class)))
                .thenReturn(new ChatResponse("s1", "answer", "model", 123L));

        fixture.consumer.consume(message);
        fixture.consumer.consume(message);

        ArgumentCaptor<AcceptedChatCommand> commands =
                ArgumentCaptor.forClass(AcceptedChatCommand.class);
        verify(chatService, times(2)).chat(commands.capture());
        assertThat(commands.getAllValues())
                .extracting(AcceptedChatCommand::runId)
                .containsOnly(message.requestId());
        assertThat(commands.getAllValues())
                .extracting(AcceptedChatCommand::acceptedAtEpochMs)
                .containsOnly(message.createdAt());
    }

    private static final class Fixture {
        private final AsyncChatResultStore resultStore =
                mock(AsyncChatResultStore.class);
        private final ChatMessageProducer producer =
                mock(ChatMessageProducer.class);
        private final org.springframework.messaging.simp.SimpMessagingTemplate stomp =
                mock(org.springframework.messaging.simp.SimpMessagingTemplate.class);
        private final ChatMessageConsumer consumer;

        private Fixture(ChatService chatService) {
            consumer = new ChatMessageConsumer(
                    chatService, resultStore, producer, stomp
            );
            when(resultStore.markCompleted(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString()
            )).thenAnswer(invocation -> {
                AsyncChatRequestMessage message = invocation.getArgument(0);
                return new AsyncChatResultPayload(
                        message.requestId(),
                        "COMPLETED",
                        message.sessionKey(),
                        message.channel(),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        message.createdAt(),
                        456L,
                        ""
                );
            });
        }

        private AsyncChatRequestMessage message(String requestId) {
            return new AsyncChatRequestMessage(
                    requestId,
                    "s1",
                    "u1",
                    "hello",
                    "api",
                    1_750_464_000_000L,
                    "agent"
            );
        }
    }
}
```

Add to transport characterization:

```java
verify(chatService).chat(argThat(command ->
        command.runId().equals(message.requestId())
        && command.sessionKey().equals(message.sessionKey())
        && command.acceptedAtEpochMs() == message.createdAt()
));
```

Add a controller test that captures the queued message and asserts `requestId` matches `[0-9a-f]{32}` and is the same ID returned in `AsyncChatAcceptedResponse`.

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
mvn -q \
  -Dtest=ChatMessageConsumerCanonicalIdentityTest,TransportParityCharacterizationTest,ChatControllerAuthTest \
  test
```

Expected: verification fails because the consumer reconstructs a `ChatRequest`, and the controller still calls `UUID.randomUUID()` directly.

- [ ] **Step 3: Make async acceptance and consumption reuse the same ID**

Inject `RunIdentityFactory` into `ChatController` and replace:

```java
String requestId = UUID.randomUUID().toString().replace("-", "");
```

with:

```java
String requestId = runIdentityFactory.create();
```

Do not add a field to `ChatRequest`, `AsyncChatRequestMessage`, `AsyncChatAcceptedResponse`, or `AsyncChatResultPayload`.

Change the consumer call to:

```java
ChatResponse response = chatService.chat(new AcceptedChatCommand(
        message.requestId(),
        message.sessionKey(),
        message.userId(),
        message.message(),
        message.channel(),
        message.responseMode(),
        message.createdAt()
));
```

The bridge-enabled consumer then claims the existing run by `message.requestId`; repeated delivery enters `RunCoordinator` with the identical ID and reuses the terminal state instead of creating a second run.

- [ ] **Step 4: Populate `ToolExecutionContext.runId` at existing call sites**

Do not edit `ToolExecutionContext` or `ToolRuntimeAspect`. Replace every production five-argument constructor listed below with the existing seven-argument constructor:

```java
new ToolExecutionContext(
        sessionKey,
        channel,
        userId,
        requestId,
        phase,
        requestId,
        roleCode
)
```

Use `context.roleCode()` where a `ChatContext` is available and `null` in existing executor methods that do not currently receive role data. Exact replacements:

```text
OparLoopEngine: LOCAL-SHORTCUT and ACT-{stepNo}
SimplifiedOparEngine: LOCAL-SHORTCUT and ACT-SIMPLIFIED
AutonomousLoopEngine: AUTONOMOUS
ModelLedStreamEngine: ACT-BLOCKING and ACT-STREAM
WorkspaceCapabilityExecutor: AGENT-RUNTIME
LocalFilesCapabilityExecutor: AGENT-RUNTIME
WebCapabilityExecutor: AGENT-RUNTIME
SystemHealthCapabilityExecutor: AGENT-RUNTIME
SkillCapabilityExecutor: AGENT-RUNTIME
RealtimeCapabilityExecutor: AGENT-RUNTIME
```

For the engine methods that currently receive only `AssembledContext` and `requestId`, use:

```java
new ToolExecutionContext(
        assembled.sessionKey(),
        assembled.channel(),
        assembled.userId(),
        requestId,
        phase,
        requestId,
        null
)
```

This changes only ownership metadata. Permission checks, risk classification, proposal snapshots, argument hashing, workspace guards, approved-proposal resume, and audit execution order remain unchanged. `ToolProposalExecutionService` already reconstructs `requestId` and `runId` from the persisted proposal and must not be edited.

- [ ] **Step 5: Add focused ownership assertions**

Extend existing tool/audit tests to capture `ToolExecutionContext` at one blocking engine path and assert:

```java
assertThat(toolContext.requestId()).isEqualTo(acceptedId);
assertThat(toolContext.runId()).isEqualTo(acceptedId);
```

Extend the pending-proposal test to assert the produced `ToolInvocationProposal` carries:

```java
assertThat(proposal.requestId()).isEqualTo(acceptedId);
assertThat(proposal.runId()).isEqualTo(acceptedId);
```

- [ ] **Step 6: Run async, SSE identity, and safety-path tests**

Run:

```bash
mvn -q \
  -Dtest=ChatMessageConsumerCanonicalIdentityTest,TransportParityCharacterizationTest,ChatControllerAuthTest,ChatContextFactoryTest,ChatServiceImplPendingApprovalTest,ToolSafetyPathCharacterizationTest,ToolRuntimeAspectGuardTest,MessageEventToolAuditServiceTest \
  test
```

Expected: async redelivery supplies the same ID twice, SSE context uses its accepted command ID, and proposal/audit ownership observes `requestId == runId` with no authorization behavior change.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/springclaw/controller/ChatController.java \
  src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java \
  src/main/java/com/springclaw/service/chat/impl/OparLoopEngine.java \
  src/main/java/com/springclaw/service/chat/impl/SimplifiedOparEngine.java \
  src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java \
  src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java \
  src/main/java/com/springclaw/service/agent/executor/WorkspaceCapabilityExecutor.java \
  src/main/java/com/springclaw/service/agent/executor/LocalFilesCapabilityExecutor.java \
  src/main/java/com/springclaw/service/agent/executor/WebCapabilityExecutor.java \
  src/main/java/com/springclaw/service/agent/executor/SystemHealthCapabilityExecutor.java \
  src/main/java/com/springclaw/service/agent/executor/SkillCapabilityExecutor.java \
  src/main/java/com/springclaw/service/agent/executor/RealtimeCapabilityExecutor.java \
  src/test/java/com/springclaw/service/chat/async/ChatMessageConsumerCanonicalIdentityTest.java \
  src/test/java/com/springclaw/architecture/TransportParityCharacterizationTest.java \
  src/test/java/com/springclaw/controller/ChatControllerAuthTest.java
git commit -m "feat: unify async and tool ownership identity"
```

### Task 8: Make legacy trace status diagnostic under the canonical bridge

**Files:**
- Modify: `src/main/java/com/springclaw/service/agent/AgentRunTraceService.java`
- Modify: `src/test/java/com/springclaw/service/agent/AgentRunTraceServiceTest.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/UnifiedBridgeActivationTest.java`

- [ ] **Step 1: Write failing diagnostic-authority tests**

```java
@Test
void canonicalBridgeModeKeepsTracePersistenceButDoesNotAuthoritativelyUpdateLegacyRunStatus() {
    MessageEventService events = mock(MessageEventService.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.queryForObject(any(String.class), eq(Integer.class), eq("req-1")))
            .thenReturn(0);
    AgentRunTraceService service = new AgentRunTraceService(
            events, new ObjectMapper(), jdbc, null, true
    );

    service.record(
            "s1", "api", "u1", "req-1",
            "late trace", "final", "failed", "late diagnostic", 12L
    );

    verify(events).recordSingle(
            eq("s1"), eq("api"), eq("u1"), eq("SYSTEM"), eq("TRACE"),
            any(String.class), eq("req-1")
    );
    verify(jdbc).update(
            argThat(sql -> sql.startsWith("INSERT INTO agent_run\n")
                    && !sql.contains("status = IF(VALUES(status)")),
            any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any()
    );
}
```

Add an activation test:

```java
RunState terminalBeforeTrace = repository.find(runId).orElseThrow();
traceService.record("s1", "api", "u1", runId,
        "late legacy trace", "final", "failed", "diagnostic only", 1L);
RunState terminalAfterTrace = repository.find(runId).orElseThrow();

assertThat(terminalAfterTrace).isEqualTo(terminalBeforeTrace);
assertThat(terminalAfterTrace.status()).isEqualTo(RunStatus.COMPLETED);
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
mvn -q -Dtest=AgentRunTraceServiceTest,UnifiedBridgeActivationTest test
```

Expected: constructor compilation fails and the existing SQL still includes authoritative status update logic.

- [ ] **Step 3: Add an explicit trace authority mode**

Inject the same flag:

```java
@Value("${springclaw.runtime.unified-bridge-enabled:false}")
boolean unifiedBridgeEnabled
```

Store:

```java
private final TraceAuthorityMode traceAuthorityMode;

enum TraceAuthorityMode {
    LEGACY_AUTHORITATIVE,
    CANONICAL_DIAGNOSTIC
}
```

The production constructor selects:

```java
this.traceAuthorityMode = unifiedBridgeEnabled
        ? TraceAuthorityMode.CANONICAL_DIAGNOSTIC
        : TraceAuthorityMode.LEGACY_AUTHORITATIVE;
```

Keep `message_event`, `agent_run_step`, `tool_invocation`, quality, and learning-capture persistence unchanged. Split only `upsertAgentRun`:

```java
String sql = traceAuthorityMode == TraceAuthorityMode.LEGACY_AUTHORITATIVE
        ? LEGACY_AUTHORITATIVE_UPSERT_SQL
        : CANONICAL_DIAGNOSTIC_UPSERT_SQL;
```

`LEGACY_AUTHORITATIVE_UPSERT_SQL` is byte-for-byte the existing SQL. `CANONICAL_DIAGNOSTIC_UPSERT_SQL` uses the same insert values but its duplicate clause omits status ownership:

```sql
ON DUPLICATE KEY UPDATE
  finished_at = COALESCE(finished_at, VALUES(finished_at)),
  duration_ms = COALESCE(duration_ms, VALUES(duration_ms)),
  quality_score = COALESCE(VALUES(quality_score), quality_score),
  quality_level = COALESCE(NULLIF(VALUES(quality_level), ''), quality_level),
  evaluation_json = COALESCE(NULLIF(VALUES(evaluation_json), ''), evaluation_json),
  update_time = VALUES(update_time)
```

`toRunStatus(event)` remains the legacy diagnostic mapping used for inserted trace rows, but it is not read by or wired to `RunStateRepository`. Add a comment stating that only `RunCoordinator` can mutate the canonical in-memory state.

- [ ] **Step 4: Run trace and activation tests**

Run:

```bash
mvn -q -Dtest=AgentRunTraceServiceTest,UnifiedBridgeActivationTest test
```

Expected: flag false preserves the current authoritative legacy SQL; flag true still persists trace/steps/tools but cannot change a canonical terminal state or overwrite an existing legacy run status from a late trace.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/springclaw/service/agent/AgentRunTraceService.java \
  src/test/java/com/springclaw/service/agent/AgentRunTraceServiceTest.java \
  src/test/java/com/springclaw/service/chat/impl/UnifiedBridgeActivationTest.java
git commit -m "feat: make legacy trace status diagnostic"
```

### Task 9: Run full acceptance, prove payload safety, and document rollback

**Files:**
- Verify only; no production or test file changes are expected in this task.

- [ ] **Step 1: Run all 67 Phase 1 contract tests**

Run:

```bash
mvn -q \
  -Dtest=RunStatusTest,ContextAndDecisionContractTest,ToolCompletionAndResultContractTest,RunEventContractTest,RunStateContractTest,RuntimeStrategyContractTest,LegacyRuntimeContractFixturesTest \
  test
```

Expected: 67 tests, 0 failures, 0 errors, 0 skips.

- [ ] **Step 2: Run all 47 production-backed characterization tests**

Run:

```bash
mvn -q \
  -Dtest=RuntimeRouteCharacterizationTest,ContextPropagationCharacterizationTest,ToolSafetyPathCharacterizationTest,FinalAnswerOwnershipCharacterizationTest,TransportParityCharacterizationTest \
  test
```

Expected:

```text
RuntimeRouteCharacterizationTest: 20
ContextPropagationCharacterizationTest: 5
ToolSafetyPathCharacterizationTest: 7
FinalAnswerOwnershipCharacterizationTest: 5
TransportParityCharacterizationTest: 10
Total: 47; failures/errors/skips: 0
```

- [ ] **Step 3: Run the 27 focused baseline tests**

Run:

```bash
mvn -q \
  -Dtest=EngineSelectorTest,ChatContextFactoryTest,PromptInjectionTest,AgentRuntimeEngineTest,ToolRuntimeAspectInterceptionIT,ToolInvocationProposalServiceConfirmTest \
  test
```

Expected: 27 tests, 0 failures, 0 errors, 0 skips. The known local MySQL `Public Key Retrieval is not allowed` warning may appear without failing Maven.

- [ ] **Step 4: Run all new Phase 2A/2B bridge tests**

Run:

```bash
mvn -q \
  -Dtest=DefaultRunIdentityFactoryTest,InMemoryRunStateRepositoryTest,InMemoryRunEventStoreTest,LegacyRuntimeAdaptersTest,RunCoordinatorTest,ChatMessageConsumerCanonicalIdentityTest,UnifiedBridgeActivationTest,AgentRunTraceServiceTest \
  test
```

Expected: all identity, repository, event, adapter, coordinator, duplicate-delivery, flag, and trace-authority tests pass.

- [ ] **Step 5: Prove no transport DTO or prohibited runtime file changed**

Run:

```bash
git diff --name-only HEAD~8..HEAD -- \
  src/main/java/com/springclaw/dto/chat/ChatRequest.java \
  src/main/java/com/springclaw/dto/chat/ChatResponse.java \
  src/main/java/com/springclaw/service/chat/async/AsyncChatRequestMessage.java \
  src/main/java/com/springclaw/service/chat/async/AsyncChatResultPayload.java \
  src/main/java/com/springclaw/tool/runtime/ToolExecutionContext.java \
  src/main/java/com/springclaw/tool/runtime/ToolRuntimeAspect.java \
  src/main/java/com/springclaw/service/workspace/WorkspaceGuard.java \
  src/main/java/com/springclaw/service/workspace/WorkspaceGitGuard.java \
  src/main/java/com/springclaw/service/proposal/ToolInvocationProposalService.java \
  src/main/java/com/springclaw/service/proposal/ToolProposalExecutionService.java \
  src/main/java/com/springclaw/service/agent/EngineSelector.java
```

Expected: no output.

Run:

```bash
git diff HEAD~8..HEAD -- src/main/resources/application.yml \
  | rg -n "unified-bridge-enabled|SPRINGCLAW_RUNTIME_UNIFIED_BRIDGE_ENABLED"
```

Expected: only the new false-default feature flag mapping.

- [ ] **Step 6: Verify rollback behavior explicitly**

Run the activation test with the disabled fixture and inspect the production property:

```bash
mvn -q -Dtest=UnifiedBridgeActivationTest#disabledFlagUsesExactLegacyBlockingPath test
rg -n "unified-bridge-enabled.*false" src/main/resources/application.yml
```

Expected: the disabled path does not call `RunCoordinator`.

Operational rollback is exactly:

```properties
SPRINGCLAW_RUNTIME_UNIFIED_BRIDGE_ENABLED=false
```

then restart the application. This restores all blocking ingress to `executeLegacyAccepted(..., noop())`; SSE was never coordinator-owned. If code rollback is also required, revert the ingress-wiring commit `feat: activate blocking unified runtime bridge` after disabling the flag. The in-memory repository/event data is process-local and requires no schema rollback.

- [ ] **Step 7: Run the complete Maven suite**

Run:

```bash
mvn -q test
```

Expected: exit code `0`. No test may require a real model call.

- [ ] **Step 8: Stop on any acceptance failure**

If any command in Steps 1-7 fails, return to the task that owns the failing file, add a new RED assertion to that task's named test class, apply the smallest correction in that task's named production file, rerun that task's GREEN command, and commit with that task's specified commit message. Do not create a catch-all acceptance commit and do not weaken an existing assertion.

## Acceptance invariants

The implementation is accepted only when all of the following are true:

- One accepted ID is a normalized 32-character lowercase UUID string.
- `RunState.runId == RunState.requestId == ChatContext.requestId`.
- Sync creates one ID at `ChatServiceImpl` acceptance.
- SSE creates one ID at `ChatServiceImpl` acceptance and uses it in `ChatContext`, trace, audit, and proposal ownership, but does not activate canonical lifecycle.
- Async creates one ID in `ChatController.sendAsync`; the message, result polling key, WebSocket topic, `AcceptedChatCommand`, `ChatContext`, canonical run, trace, audit, and proposal all reuse it.
- Repeated RabbitMQ delivery reuses the same terminal run and does not execute legacy behavior twice.
- The canonical path is exactly `CREATED -> CONTEXT_READY -> DECIDED -> RUNNING -> VERIFYING -> COMPLETED`, or any current nonterminal state directly to `FAILED`.
- Context and decision adapters translate existing `ChatContext`/`AgentDecision`; they do not call retrieval or routing services.
- `LegacyRunResultAdapter` preserves exact `sessionKey`, `answer`, `model`, and `timestamp`; it does not repair, summarize, or regenerate an answer.
- `LegacyRuntimeStrategy` does not call public `ChatService.chat`, select an engine, persist a result, complete an emitter, or release a lock.
- Existing `EngineSelector` and all six `supports()` implementations are unchanged.
- Existing `ChatResultPersister`, answer composition, model fallback, proposal authorization, `ToolRuntimeAspect`, workspace guards, and approved-proposal resume remain authoritative for legacy behavior.
- `AgentRunTraceService` continues persisting diagnostics; with the bridge enabled it cannot mutate `RunStateRepository` and late trace cannot replace a canonical terminal state.
- The default flag value is false.
- All 67 contract, 47 characterization, 27 focused baseline, and new bridge tests pass.

## Out of scope

Do not include any of the following in this implementation:

- Database schema or canonical run/event persistence.
- Redis persistence for canonical run state.
- SSE event projector, SSE lifecycle migration, stream cancellation redesign, or stream completion migration.
- Context retrieval migration or a production `ContextSnapshotFactory`.
- Runtime decision migration or replacement of `AgentDecisionService`, `ChatRoutingPolicyService`, `EngineSelector`, or engine `supports()`.
- `ToolGateway`, tool execution migration, proposal state-machine changes, workspace mutation leases, or any change to `ToolRuntimeAspect`.
- `CompletionVerifier`, answer composer migration, final-answer repair changes, or evidence scoring.
- WebSocket runtime projector or frontend payload changes.
- Engine deletion or priority/rank behavior changes.

## Final plan-document verification

Before implementation handoff, run:

```bash
rg -n "T[B]D|T[O]DO|F[I]XME|p[l]aceholder|fill[ ]in|implement[ ]later|待[定]|以后处[理]" \
  docs/superpowers/plans/2026-06-21-unified-runtime-legacy-bridge.md
git diff --check
git status --short
```

Expected:

- the red-flag language scan has no output;
- `git diff --check` has no output;
- only `docs/superpowers/plans/2026-06-21-unified-runtime-legacy-bridge.md` is changed when this plan is authored.
