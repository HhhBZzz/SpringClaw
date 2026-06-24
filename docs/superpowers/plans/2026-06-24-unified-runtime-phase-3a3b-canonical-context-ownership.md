# Unified Runtime Phase 3A3b Canonical Context Ownership Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make canonical `ContextSnapshot` ownership the default runtime path, using the accepted `RunState.sessionAccessClaim()` as the memory authorization source.

**Architecture:** Add Spring wiring for snapshot ownership, introduce a focused `RunStateContextSnapshotRequestFactory`, make `ChatContextFactory` load the accepted run before building canonical context, and split `LegacyLifecycleObserver` so canonical mode does not create a second context snapshot. Legacy `ContextAssembler` and `SemanticMemoryAdvisor` remain available only through explicit rollback/default-off configuration.

**Tech Stack:** Java 17, Spring Boot 3.5, JUnit 5, AssertJ, Mockito, existing Phase 2B lifecycle contracts and Phase 3A memory contracts.

---

## Hard boundaries

Do not remove these classes in Phase 3A3b:

- `ContextAssembler`
- `SemanticMemoryAdvisor`
- `LegacyRunContextAdapter`
- existing engines

Do not change these behaviors:

- routing policy;
- final-answer ownership;
- stream termination;
- tool approval, proposal lifecycle, workspace guard, or tool runtime safety;
- automatic semantic extraction.

Rollback must remain:

```properties
SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false
```

## File map

- Create: `src/main/java/com/springclaw/config/ContextSnapshotConfig.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/RunStateContextSnapshotRequestFactory.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java`
- Modify: `src/main/java/com/springclaw/runtime/bridge/LegacyLifecycleObserver.java`
- Modify: `src/main/resources/application.yml`
- Modify: `.env.example`
- Test: `src/test/java/com/springclaw/runtime/bridge/RunStateContextSnapshotRequestFactoryTest.java`
- Test: `src/test/java/com/springclaw/runtime/contract/ContextSnapshotFactorySpringWiringTest.java`
- Test: `src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalOwnershipTest.java`
- Test: `src/test/java/com/springclaw/runtime/bridge/LegacyLifecycleObserverCanonicalModeTest.java`
- Modify ledger: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`

---

## Task 1: Add canonical snapshot Spring wiring and defaults

**Owner:** Codex or Claude

**Files:**

- Create: `src/main/java/com/springclaw/config/ContextSnapshotConfig.java`
- Modify: `src/main/java/com/springclaw/config/MemoryFrameConfig.java`
- Modify: `src/main/resources/application.yml`
- Modify: `.env.example`
- Test: `src/test/java/com/springclaw/runtime/contract/ContextSnapshotFactorySpringWiringTest.java`

- [ ] **Step 1: Write failing Spring wiring test**

Create `src/test/java/com/springclaw/runtime/contract/ContextSnapshotFactorySpringWiringTest.java`:

```java
package com.springclaw.runtime.contract;

import com.springclaw.config.ContextSnapshotConfig;
import com.springclaw.config.MemoryFrameConfig;
import com.springclaw.runtime.bridge.LegacyContextViewAdapter;
import com.springclaw.runtime.bridge.RunStateContextSnapshotRequestFactory;
import com.springclaw.runtime.memory.port.MemoryRecordStore;
import com.springclaw.runtime.memory.port.ProjectMemorySource;
import com.springclaw.runtime.memory.store.InMemoryMemoryRecordStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class ContextSnapshotFactorySpringWiringTest {

    @Test
    void canonicalSnapshotBeansAreActiveByDefaultWhenMemoryFrameIsEnabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        ContextSnapshotConfig.class,
                        MemoryFrameConfig.class,
                        TestConfig.class
                )
                .withPropertyValues("springclaw.memory.frame.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(ContextSnapshotFactory.class)
                        .hasSingleBean(LegacyContextViewAdapter.class)
                        .hasSingleBean(RunStateContextSnapshotRequestFactory.class));
    }

    @Test
    void disablingCanonicalSnapshotModeRemovesSnapshotFactoryBeans() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        ContextSnapshotConfig.class,
                        MemoryFrameConfig.class,
                        TestConfig.class
                )
                .withPropertyValues(
                        "springclaw.context.snapshot.factory-enabled=false",
                        "springclaw.memory.frame.enabled=true"
                )
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ContextSnapshotFactory.class)
                        .doesNotHaveBean(LegacyContextViewAdapter.class)
                        .doesNotHaveBean(RunStateContextSnapshotRequestFactory.class));
    }

    @Test
    void canonicalSnapshotModeFailsClearlyWithoutMemoryCoordinator() {
        new ApplicationContextRunner()
                .withUserConfiguration(ContextSnapshotConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("MemoryCoordinator");
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        MemoryRecordStore memoryRecordStore() {
            return new InMemoryMemoryRecordStore();
        }

        @Bean
        ProjectMemorySource projectMemorySource() {
            return ignored -> java.util.List.of();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
```

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=ContextSnapshotFactorySpringWiringTest test
```

Expected: compilation fails because `ContextSnapshotConfig` and
`RunStateContextSnapshotRequestFactory` do not exist.

- [ ] **Step 3: Implement ContextSnapshotConfig**

Create `src/main/java/com/springclaw/config/ContextSnapshotConfig.java`:

```java
package com.springclaw.config;

import com.springclaw.runtime.bridge.LegacyContextViewAdapter;
import com.springclaw.runtime.bridge.RunStateContextSnapshotRequestFactory;
import com.springclaw.runtime.contract.ContextSnapshotFactory;
import com.springclaw.service.memory.frame.MemoryCoordinator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "springclaw.context.snapshot",
        name = "factory-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ContextSnapshotConfig {

    @Bean
    public ContextSnapshotFactory contextSnapshotFactory(
            MemoryCoordinator memoryCoordinator,
            ObjectProvider<Clock> clockProvider
    ) {
        return new ContextSnapshotFactory(
                memoryCoordinator,
                clockProvider.getIfAvailable(Clock::systemUTC)
        );
    }

    @Bean
    public LegacyContextViewAdapter legacyContextViewAdapter() {
        return new LegacyContextViewAdapter();
    }

    @Bean
    public RunStateContextSnapshotRequestFactory runStateContextSnapshotRequestFactory() {
        return new RunStateContextSnapshotRequestFactory();
    }
}
```

- [ ] **Step 4: Add minimal RunStateContextSnapshotRequestFactory bean class**

Create `src/main/java/com/springclaw/runtime/bridge/RunStateContextSnapshotRequestFactory.java`:

```java
package com.springclaw.runtime.bridge;

public class RunStateContextSnapshotRequestFactory {
}
```

Task 2 replaces this minimal class with the real implementation. Do not add
business logic in Task 1.

- [ ] **Step 5: Change defaults**

Modify `MemoryFrameConfig` so the coordinator is active when the property is
missing:

```java
    @Bean
    @ConditionalOnProperty(
            prefix = "springclaw.memory.frame",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    public MemoryCoordinator memoryCoordinator(
```

Modify `src/main/resources/application.yml`:

```yaml
  context:
    snapshot:
      factory-enabled: ${SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED:true}
```

Modify the existing memory frame flag default:

```yaml
    frame:
      enabled: ${SPRINGCLAW_MEMORY_FRAME_ENABLED:true}
```

Modify `.env.example`:

```properties
SPRINGCLAW_MEMORY_FRAME_ENABLED=true
SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=true
```

- [ ] **Step 6: Verify GREEN**

Run:

```bash
mvn -q -Dtest=ContextSnapshotFactorySpringWiringTest,MemoryFrameSpringWiringTest test
```

Expected:

- new context snapshot wiring test passes;
- `MemoryFrameSpringWiringTest.memoryFrameCoordinatorIsInactiveByDefault` now
  fails because the default changed.

Update `MemoryFrameSpringWiringTest` to reflect the Phase 3A3b default:

```java
@Test
void memoryFrameCoordinatorIsActiveByDefault() {
    new ApplicationContextRunner()
            .withUserConfiguration(MemoryFrameConfig.class, TestConfig.class)
            .run(context -> assertThat(context)
                    .hasSingleBean(MemoryCoordinator.class));
}

@Test
void memoryFrameCoordinatorCanBeDisabledForRollback() {
    new ApplicationContextRunner()
            .withUserConfiguration(MemoryFrameConfig.class)
            .withPropertyValues("springclaw.memory.frame.enabled=false")
            .run(context -> assertThat(context)
                    .doesNotHaveBean(MemoryCoordinator.class));
}
```

Then rerun:

```bash
mvn -q -Dtest=ContextSnapshotFactorySpringWiringTest,MemoryFrameSpringWiringTest test
```

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/springclaw/config/ContextSnapshotConfig.java \
  src/main/java/com/springclaw/config/MemoryFrameConfig.java \
  src/main/java/com/springclaw/runtime/bridge/RunStateContextSnapshotRequestFactory.java \
  src/main/resources/application.yml .env.example \
  src/test/java/com/springclaw/runtime/contract/ContextSnapshotFactorySpringWiringTest.java \
  src/test/java/com/springclaw/service/memory/frame/MemoryFrameSpringWiringTest.java
git commit -m "feat: enable canonical snapshot wiring by default"
```

---

## Task 2: Build ContextSnapshotRequest from accepted RunState

**Owner:** Codex or Claude

**Files:**

- Modify: `src/main/java/com/springclaw/runtime/bridge/RunStateContextSnapshotRequestFactory.java`
- Test: `src/test/java/com/springclaw/runtime/bridge/RunStateContextSnapshotRequestFactoryTest.java`

- [ ] **Step 1: Write failing request-factory tests**

Create `src/test/java/com/springclaw/runtime/bridge/RunStateContextSnapshotRequestFactoryTest.java`:

```java
package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.ContextSnapshotRequest;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunStateContextSnapshotRequestFactoryTest {

    private static final Instant T0 = Instant.parse("2026-06-24T00:00:00Z");

    @Test
    void usesAcceptedRunClaimAndIdentity() {
        RunState state = created(personalClaim());

        ContextSnapshotRequest request = new RunStateContextSnapshotRequestFactory()
                .create(
                        state,
                        "effective",
                        "system",
                        List.of("web"),
                        Map.of("providerId", "provider")
                );

        assertThat(request.runId()).isEqualTo(state.runId());
        assertThat(request.sessionKey()).isEqualTo(state.sessionKey());
        assertThat(request.channel()).isEqualTo(state.channel());
        assertThat(request.userId()).isEqualTo(state.userId());
        assertThat(request.sessionAccessClaim()).isEqualTo(state.sessionAccessClaim());
        assertThat(request.roleCode()).isEqualTo(state.roleCodeAtAcceptance());
        assertThat(request.originalMessage()).isEqualTo(state.originalMessage());
        assertThat(request.effectiveMessage()).isEqualTo("effective");
        assertThat(request.allowedCapabilities()).containsExactly("web");
    }

    @Test
    void preservesSharedSessionClaim() {
        SessionAccessClaim claim = SessionAccessClaim.shared(
                SessionAccessClaim.AcceptanceOrigin.VERIFIED_FEISHU_SDK,
                "feishu",
                "group-1",
                "ou-1",
                "feishu:group-1"
        );
        RunState state = created(claim);

        ContextSnapshotRequest request = new RunStateContextSnapshotRequestFactory()
                .create(state, "effective", "system", List.of(), Map.of());

        assertThat(request.sessionAccessClaim().claimType())
                .isEqualTo(SessionAccessClaim.ClaimType.SHARED_SESSION);
        assertThat(request.sessionAccessClaim().ownerOrSharedPrincipal())
                .isEqualTo("feishu:group-1");
    }

    @Test
    void rejectsTerminalRunState() {
        RunState terminal = new RunState(
                "run-1", "run-1", 1, RunStatus.FAILED,
                "session-1", "api", "alice", personalClaim(), "USER",
                "original", "agent", T0, null, T0.plusSeconds(1),
                T0.plusSeconds(1), T0.plusSeconds(300),
                null, null, "", 1, "", List.of(), null, null, Map.of(),
                new RunState.Failure("FAILED", "boom", false)
        );

        assertThatThrownBy(() -> new RunStateContextSnapshotRequestFactory()
                .create(terminal, "effective", "system", List.of(), Map.of()))
                .hasMessageContaining("terminal");
    }

    private static RunState created(SessionAccessClaim claim) {
        return new RunState(
                "run-1", "run-1", 0, RunStatus.CREATED,
                claim.sessionKey(), claim.channel(), claim.acceptedUserId(),
                claim, "USER", "original", "agent", T0, null, T0,
                null, T0.plusSeconds(300), null, null, "", 1, "",
                List.of(), null, null, Map.of(), null
        );
    }

    private static SessionAccessClaim personalClaim() {
        return SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                "session-1",
                "alice"
        );
    }
}
```

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=RunStateContextSnapshotRequestFactoryTest test
```

Expected: compilation fails because the minimal factory class has no `create(...)`
method.

- [ ] **Step 3: Implement factory**

Replace `RunStateContextSnapshotRequestFactory` with:

```java
package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.ContextSnapshotRequest;
import com.springclaw.runtime.contract.RunState;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class RunStateContextSnapshotRequestFactory {

    public ContextSnapshotRequest create(
            RunState runState,
            String effectiveMessage,
            String systemPrompt,
            List<String> allowedCapabilities,
            Map<String, String> providerSnapshot
    ) {
        Objects.requireNonNull(runState, "runState");
        if (runState.status().isTerminal()) {
            throw new IllegalStateException(
                    "terminal run cannot create ContextSnapshotRequest: "
                            + runState.status()
            );
        }
        return new ContextSnapshotRequest(
                runState.runId(),
                runState.sessionKey(),
                runState.userId(),
                runState.channel(),
                runState.userId(),
                runState.sessionAccessClaim(),
                runState.roleCodeAtAcceptance(),
                runState.originalMessage(),
                effectiveMessage == null ? "" : effectiveMessage,
                systemPrompt == null ? "" : systemPrompt,
                allowedCapabilities == null ? List.of() : allowedCapabilities,
                providerSnapshot == null ? Map.of() : providerSnapshot
        );
    }
}
```

- [ ] **Step 4: Verify GREEN**

Run:

```bash
mvn -q -Dtest=RunStateContextSnapshotRequestFactoryTest,ContextSnapshotFactoryTest test
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/springclaw/runtime/bridge/RunStateContextSnapshotRequestFactory.java \
  src/test/java/com/springclaw/runtime/bridge/RunStateContextSnapshotRequestFactoryTest.java
git commit -m "feat: derive snapshot requests from accepted runs"
```

---

## Task 3: Make ChatContextFactory use accepted RunState by default

**Owner:** Codex

**Files:**

- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java`
- Test: `src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalOwnershipTest.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalSnapshotTest.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java` if constructor helpers need updating.

- [ ] **Step 1: Write failing ownership tests**

Create `ChatContextFactoryCanonicalOwnershipTest`:

```java
package com.springclaw.service.chat.impl;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.runtime.bridge.LegacyContextView;
import com.springclaw.runtime.bridge.LegacyContextViewAdapter;
import com.springclaw.runtime.bridge.RunStateContextSnapshotRequestFactory;
import com.springclaw.runtime.contract.*;
import com.springclaw.runtime.lifecycle.RunStateRepository;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.service.agent.*;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.context.*;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.session.AgentSessionService;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ChatContextFactoryCanonicalOwnershipTest {

    @Test
    void canonicalDefaultUsesAcceptedRunStateClaimAndSkipsContextAssembler() {
        Fixture fixture = new Fixture(true);

        ChatContext context = fixture.factory.build(
                new ChatRequest("forged-session", "mallory", "hello", "api", "agent"),
                true,
                "run-1"
        );

        verify(fixture.runStateRepository).requireByRunId("run-1");
        verify(fixture.requestFactory).create(
                eq(fixture.runState),
                eq("hello"),
                eq("system"),
                eq(List.of("web")),
                any()
        );
        verifyNoInteractions(fixture.contextAssembler);
        assertThat(context.assembled().observePrompt()).isEqualTo("canonical observe");
    }

    @Test
    void explicitLegacyRollbackUsesContextAssembler() {
        Fixture fixture = new Fixture(false);

        ChatContext context = fixture.factory.build(
                new ChatRequest("session-1", "alice", "hello", "api", "agent"),
                true,
                "run-1"
        );

        verify(fixture.contextAssembler).assemble("session-1", "api", "alice", "hello");
        verifyNoInteractions(fixture.runStateRepository, fixture.contextSnapshotFactory);
        assertThat(context.assembled().observePrompt()).isEqualTo("legacy observe");
    }

    @Test
    void canonicalDefaultFailsWhenAcceptedRunIsMissing() {
        Fixture fixture = new Fixture(true);
        when(fixture.runStateRepository.findByRunId("run-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fixture.factory.build(
                new ChatRequest("session-1", "alice", "hello", "api", "agent"),
                true,
                "run-1"
        )).hasMessageContaining("run not found");
    }

    // Include a Fixture equivalent to ChatContextFactoryCanonicalSnapshotTest,
    // but with RunStateRepository and RunStateContextSnapshotRequestFactory mocks.
}
```

Fill the local `Fixture` with the same mocks used by
`ChatContextFactoryCanonicalSnapshotTest`, plus:

```java
private final RunStateRepository runStateRepository = mock(RunStateRepository.class);
private final RunStateContextSnapshotRequestFactory requestFactory =
        mock(RunStateContextSnapshotRequestFactory.class);
private final RunState runState = createdRunState();
private final ContextSnapshotRequest snapshotRequest = snapshotRequest();
```

Set up:

```java
when(runStateRepository.findByRunId("run-1")).thenReturn(Optional.of(runState));
when(runStateRepository.requireByRunId("run-1")).thenReturn(runState);
when(requestFactory.create(any(), any(), any(), any(), any()))
        .thenReturn(snapshotRequest);
when(contextSnapshotFactory.create(snapshotRequest)).thenReturn(snapshot);
```

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=ChatContextFactoryCanonicalOwnershipTest test
```

Expected: compilation fails because `ChatContextFactory` does not accept
`RunStateRepository` and `RunStateContextSnapshotRequestFactory`.

- [ ] **Step 3: Modify ChatContextFactory dependencies**

Add imports:

```java
import com.springclaw.runtime.bridge.RunStateContextSnapshotRequestFactory;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.lifecycle.RunStateRepository;
```

Add fields:

```java
private final ObjectProvider<RunStateRepository> runStateRepositoryProvider;
private final ObjectProvider<RunStateContextSnapshotRequestFactory> snapshotRequestFactoryProvider;
```

Extend the main constructor with:

```java
ObjectProvider<RunStateRepository> runStateRepositoryProvider,
ObjectProvider<RunStateContextSnapshotRequestFactory> snapshotRequestFactoryProvider,
```

Keep the legacy public constructor and pass `emptyProvider()` for both new
providers.

- [ ] **Step 4: Replace reconstructed claim in canonical branch**

Replace the `new ContextSnapshotRequest(...)` construction in canonical mode with:

```java
RunStateRepository runStateRepository = runStateRepositoryProvider.getIfAvailable();
RunStateContextSnapshotRequestFactory requestFactory =
        snapshotRequestFactoryProvider.getIfAvailable();
if (runStateRepository == null || requestFactory == null) {
    throw new IllegalStateException(
            "canonical ContextSnapshotFactory path is enabled but run-state beans are missing"
    );
}
RunState acceptedRun = runStateRepository.requireByRunId(requestId);
ContextSnapshotRequest snapshotRequest = requestFactory.create(
        acceptedRun,
        routingDecision.effectiveQuestion(),
        systemPrompt,
        decision.selectedCapabilities(),
        providerSnapshot(activeClient)
);
ContextSnapshot snapshot = snapshotFactory.create(snapshotRequest);
```

Remove direct use of `SessionAccessClaim.personal(...)` from canonical mode.

- [ ] **Step 5: Update existing canonical tests**

Update `ChatContextFactoryCanonicalSnapshotTest.Fixture` constructor to provide
mock `RunStateRepository` and `RunStateContextSnapshotRequestFactory`.

Existing test assertions should continue to pass:

- default/legacy mode uses `ContextAssembler`;
- canonical mode skips `ContextAssembler` and uses `ContextSnapshotFactory`.

- [ ] **Step 6: Verify GREEN**

Run:

```bash
mvn -q -DforkCount=0 -Dtest=ChatContextFactoryCanonicalOwnershipTest,ChatContextFactoryCanonicalSnapshotTest,ChatContextFactoryTest test
```

Expected: pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java \
  src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalOwnershipTest.java \
  src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalSnapshotTest.java \
  src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java
git commit -m "feat: use accepted run state for canonical context"
```

---

## Task 4: Split LegacyLifecycleObserver context observation in canonical mode

**Owner:** Codex or Claude after Task 3

**Files:**

- Modify: `src/main/java/com/springclaw/runtime/bridge/LegacyLifecycleObserver.java`
- Test: `src/test/java/com/springclaw/runtime/bridge/LegacyLifecycleObserverCanonicalModeTest.java`
- Modify: `src/test/java/com/springclaw/runtime/bridge/LegacyLifecycleObserverTest.java` if constructor signatures change.

- [ ] **Step 1: Write failing observer tests**

Create `LegacyLifecycleObserverCanonicalModeTest`:

```java
package com.springclaw.runtime.bridge;

import com.springclaw.service.chat.impl.ChatContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.Mockito.*;

class LegacyLifecycleObserverCanonicalModeTest {

    private static final Instant T0 = Instant.parse("2026-06-24T00:00:00Z");

    @Test
    void canonicalModeSkipsContextObservationButKeepsDecisionObservation() {
        LegacyRuntimeBridge bridge = mock(LegacyRuntimeBridge.class);
        LegacyRunContextAdapter contextAdapter = mock(LegacyRunContextAdapter.class);
        LegacyExecutionDecisionAdapter decisionAdapter = mock(LegacyExecutionDecisionAdapter.class);
        LegacyLifecycleObserver observer = new LegacyLifecycleObserver(
                bridge,
                contextAdapter,
                decisionAdapter,
                mock(LegacyRunResultAdapter.class),
                true
        );
        ChatContext context = TestChatContexts.context("run-1");

        observer.contextAndDecisionObserved(context, T0);

        verifyNoInteractions(contextAdapter);
        verify(bridge, never()).contextObserved(any(), any(), any());
        verify(decisionAdapter).adapt(context, T0);
        verify(bridge).decisionObserved(eq("run-1"), any(), eq(T0));
    }

    @Test
    void legacyModeKeepsContextAndDecisionObservation() {
        LegacyRuntimeBridge bridge = mock(LegacyRuntimeBridge.class);
        LegacyRunContextAdapter contextAdapter = mock(LegacyRunContextAdapter.class);
        LegacyExecutionDecisionAdapter decisionAdapter = mock(LegacyExecutionDecisionAdapter.class);
        LegacyLifecycleObserver observer = new LegacyLifecycleObserver(
                bridge,
                contextAdapter,
                decisionAdapter,
                mock(LegacyRunResultAdapter.class),
                false
        );
        ChatContext context = TestChatContexts.context("run-1");

        observer.contextAndDecisionObserved(context, T0);

        verify(contextAdapter).adapt(context, T0);
        verify(bridge).contextObserved(eq("run-1"), any(), eq(T0));
        verify(decisionAdapter).adapt(context, T0);
        verify(bridge).decisionObserved(eq("run-1"), any(), eq(T0));
    }
}
```

If no `TestChatContexts` helper exists, define a private `context(String runId)`
helper in this test using the same `ChatContext` construction pattern from
`LegacyLifecycleObserverTest`.

- [ ] **Step 2: Verify RED**

Run:

```bash
mvn -q -Dtest=LegacyLifecycleObserverCanonicalModeTest test
```

Expected: compilation fails because `LegacyLifecycleObserver` lacks a canonical
mode constructor flag.

- [ ] **Step 3: Modify LegacyLifecycleObserver**

Add field:

```java
private final boolean contextSnapshotFactoryEnabled;
```

Add constructor parameter:

```java
@Value("${springclaw.context.snapshot.factory-enabled:true}")
boolean contextSnapshotFactoryEnabled
```

Keep a four-argument compatibility constructor for tests and legacy callers:

```java
public LegacyLifecycleObserver(
        LegacyRuntimeBridge bridge,
        LegacyRunContextAdapter contextAdapter,
        LegacyExecutionDecisionAdapter decisionAdapter,
        LegacyRunResultAdapter resultAdapter
) {
    this(bridge, contextAdapter, decisionAdapter, resultAdapter, true);
}
```

Change `contextAndDecisionObserved`:

```java
public void contextAndDecisionObserved(ChatContext context, Instant at) {
    if (!contextSnapshotFactoryEnabled) {
        bridge.contextObserved(
                context.requestId(),
                contextAdapter.adapt(context, at),
                at
        );
    }
    bridge.decisionObserved(
            context.requestId(),
            decisionAdapter.adapt(context, at),
            at
    );
}
```

- [ ] **Step 4: Update existing observer tests**

`LegacyLifecycleObserverTest` currently assumes context observation by default.
Update it to pass `false` to the new constructor where it validates old event
ordering:

```java
new LegacyLifecycleObserver(..., false)
```

Add one assertion in the canonical-mode test that event order does not include
`CONTEXT_READY` from the observer when context is skipped.

- [ ] **Step 5: Verify GREEN**

Run:

```bash
mvn -q -DforkCount=0 -Dtest=LegacyLifecycleObserverCanonicalModeTest,LegacyLifecycleObserverTest,LegacyRuntimeBridgeTest test
```

Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/runtime/bridge/LegacyLifecycleObserver.java \
  src/test/java/com/springclaw/runtime/bridge/LegacyLifecycleObserverCanonicalModeTest.java \
  src/test/java/com/springclaw/runtime/bridge/LegacyLifecycleObserverTest.java
git commit -m "feat: skip legacy context observation in canonical mode"
```

---

## Task 5: Compatibility characterization updates

**Owner:** Codex

**Files:**

- Modify tests only if Phase 3A3b default-on behavior invalidates old default assumptions:
  - `src/test/java/com/springclaw/architecture/ContextPropagationCharacterizationTest.java`
  - `src/test/java/com/springclaw/architecture/FinalAnswerOwnershipCharacterizationTest.java`
  - `src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java`

- [ ] **Step 1: Run characterization subset**

Run:

```bash
mvn -q -DforkCount=0 -Dtest=ContextPropagationCharacterizationTest,FinalAnswerOwnershipCharacterizationTest,ChatContextFactoryTest test
```

Expected:

- if tests fail only because they instantiate `ChatContextFactory` using legacy
  constructors, preserve the old constructors or set the rollback flag false in
  test fixtures;
- do not rewrite characterization expectations for route, answer, stream, or
  tool safety.

- [ ] **Step 2: Apply minimal test fixture updates if needed**

If a test wants legacy behavior explicitly, instantiate:

```java
new ChatContextFactory(... existing 12 args ...)
```

The public 12-arg constructor should keep legacy mode with empty providers. If
that constructor already works, do not modify tests.

- [ ] **Step 3: Verify**

Run:

```bash
mvn -q -DforkCount=0 -Dtest=ContextPropagationCharacterizationTest,FinalAnswerOwnershipCharacterizationTest,ChatContextFactoryTest test
```

Expected: pass.

- [ ] **Step 4: Commit only if files changed**

If no files changed, skip the commit.

If files changed:

```bash
git add src/test/java/com/springclaw/architecture/ContextPropagationCharacterizationTest.java \
  src/test/java/com/springclaw/architecture/FinalAnswerOwnershipCharacterizationTest.java \
  src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java
git commit -m "test: characterize canonical context defaults"
```

---

## Task 6: Phase 3A3b acceptance gates and ledger

**Owner:** Codex

**Files:**

- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`

- [ ] **Step 1: Run focused Phase 3A3b suite**

Run:

```bash
mvn -q -DforkCount=0 -Dtest=ContextSnapshotFactorySpringWiringTest,RunStateContextSnapshotRequestFactoryTest,ChatContextFactoryCanonicalOwnershipTest,ChatContextFactoryCanonicalSnapshotTest,LegacyLifecycleObserverCanonicalModeTest,LegacyLifecycleObserverTest,ConversationAdvisorSupportCanonicalModeTest,ContextSnapshotFactoryTest,MemoryCoordinatorTest test
```

Expected: zero failures and zero errors.

- [ ] **Step 2: Run compatibility gates**

Run with local environment variables loaded without printing secrets:

```bash
set -a
source /Users/hanbingzheng/springclaw/.env.local
set +a
mvn -q -DforkCount=0 -Dtest=ContextPropagationCharacterizationTest,RuntimeRouteCharacterizationTest,FinalAnswerOwnershipCharacterizationTest,TransportParityCharacterizationTest,ChatContextFactoryTest,EngineSelectorTest,PromptInjectionTest,ToolRuntimeAspectInterceptionIT,WorkspaceGitGuardTest test
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
mvn -q -DforkCount=0 test
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

Append a Phase 3A3b section to
`docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`
recording:

- all Phase 3A3b commit SHAs;
- files modified;
- default flags:
  - `SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=true`
  - `SPRINGCLAW_MEMORY_FRAME_ENABLED=true`
- confirmation that canonical mode reads authorization from accepted
  `RunState.sessionAccessClaim`;
- confirmation that canonical mode skips `ContextAssembler`;
- confirmation that `LegacyLifecycleObserver` skips context observation in
  canonical mode;
- focused suite counts;
- compatibility gate counts;
- full suite counts;
- known limitations:
  - old classes still exist for rollback;
  - projection-only Advisor still deferred;
  - flat compatibility fields still exist;
  - full deletion of legacy retrieval remains later cleanup;
- rollback:
  - set `SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false`;
  - set `SPRINGCLAW_MEMORY_FRAME_ENABLED=false` only if memory-frame retrieval
    also needs rollback;
  - revert observer split;
  - revert ChatContextFactory accepted-run bridge;
  - revert snapshot Spring wiring/default change.

- [ ] **Step 5: Commit ledger**

```bash
git add docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
git commit -m "docs: record phase 3a3b canonical context evidence"
```

---

## Final Phase 3A3b gate

Phase 3A3b is complete only when:

- canonical context snapshot mode is default-on;
- memory frame retrieval is default-on for canonical context startup;
- canonical mode reads authorization from accepted `RunState.sessionAccessClaim`;
- `ChatContextFactory` no longer reconstructs personal claims in canonical mode;
- canonical mode does not call `ContextAssembler`;
- canonical mode does not attach independent semantic retrieval Advisor;
- `LegacyLifecycleObserver` does not create a second context snapshot in
  canonical mode;
- explicit rollback flag restores old `ContextAssembler` behavior;
- focused, compatibility, and full suites pass;
- collaboration ledger records evidence and rollback.

Do not remove legacy context or Advisor code in Phase 3A3b.
