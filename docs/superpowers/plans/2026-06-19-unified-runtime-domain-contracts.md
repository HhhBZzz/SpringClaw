# Unified Runtime Domain Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the immutable canonical Runtime contracts and lifecycle validation required by the approved unified-runtime architecture without changing existing request routing or transport behavior.

**Architecture:** All new production types live in the isolated `com.springclaw.runtime.contract` package. Records defensively copy collections, validate only architecture-approved invariants, and contain no Spring, persistence, model, tool-execution, or transport dependencies. `RunTransitionPolicy` validates aggregate-to-aggregate lifecycle changes while a future `RunCoordinator` remains the only runtime owner allowed to apply them.

**Tech Stack:** Java 17 records, JUnit 5, AssertJ, Jackson, Maven.

---

## Scope and file map

Create:

```text
src/main/java/com/springclaw/runtime/contract/RunStatus.java
src/main/java/com/springclaw/runtime/contract/ContextSnapshot.java
src/main/java/com/springclaw/runtime/contract/ExecutionDecision.java
src/main/java/com/springclaw/runtime/contract/ToolInvocation.java
src/main/java/com/springclaw/runtime/contract/CompletionDecision.java
src/main/java/com/springclaw/runtime/contract/RunResult.java
src/main/java/com/springclaw/runtime/contract/RunEventType.java
src/main/java/com/springclaw/runtime/contract/RunEvent.java
src/main/java/com/springclaw/runtime/contract/RunState.java
src/main/java/com/springclaw/runtime/contract/RunTransitionPolicy.java
src/main/java/com/springclaw/runtime/contract/RuntimeStrategy.java
src/test/java/com/springclaw/runtime/contract/RunStatusTest.java
src/test/java/com/springclaw/runtime/contract/ContextAndDecisionContractTest.java
src/test/java/com/springclaw/runtime/contract/ToolCompletionAndResultContractTest.java
src/test/java/com/springclaw/runtime/contract/RunEventContractTest.java
src/test/java/com/springclaw/runtime/contract/RunStateContractTest.java
src/test/java/com/springclaw/runtime/contract/RuntimeStrategyContractTest.java
src/test/java/com/springclaw/runtime/contract/LegacyRuntimeContractFixtures.java
src/test/java/com/springclaw/runtime/contract/LegacyRuntimeContractFixturesTest.java
```

Modify only:

```text
docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
```

Do not modify existing controllers, services, engines, tool safety classes, persistence classes, or transport DTOs in this plan.

### Task 1: Integrate the characterized Phase 1 baseline

**Files:**
- Merge: branch `claude/runtime-characterization`
- Verify: `src/test/java/com/springclaw/architecture/**`
- Verify: `docs/architecture/runtime-current-state-audit.md`

- [ ] **Step 1: Verify both worktrees are clean**

Run:

```bash
git -C /Users/hanbingzheng/springclaw/.worktrees/unified-agent-runtime status --short
git -C /Users/hanbingzheng/springclaw/.claude/worktrees/claude-runtime-characterization status --short
```

Expected: no file entries from either command.

- [ ] **Step 2: Merge the completed Claude characterization branch**

Run:

```bash
git merge --no-ff claude/runtime-characterization -m "merge: integrate runtime characterization baseline"
```

Expected: one merge commit containing only the audit document and five architecture test classes from commits `dab2dbc`, `9b87c65`, and `8f6135f`.

- [ ] **Step 3: Verify the characterization suite**

Run:

```bash
mvn -q \
  -Dtest=RuntimeRouteCharacterizationTest,ContextPropagationCharacterizationTest,ToolSafetyPathCharacterizationTest,FinalAnswerOwnershipCharacterizationTest,TransportParityCharacterizationTest \
  test
```

Expected: exit code `0`; the Surefire XML files contain 41 `<testcase>` elements and no failures, errors, or skips.

### Task 2: Define lifecycle states

**Files:**
- Create: `src/main/java/com/springclaw/runtime/contract/RunStatus.java`
- Test: `src/test/java/com/springclaw/runtime/contract/RunStatusTest.java`

- [ ] **Step 1: Write the failing lifecycle test**

```java
package com.springclaw.runtime.contract;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RunStatusTest {

    @Test
    void identifiesOnlyBusinessTerminalStates() {
        Set<RunStatus> terminal = Set.of(
                RunStatus.COMPLETED,
                RunStatus.DEGRADED,
                RunStatus.FAILED
        );

        for (RunStatus status : RunStatus.values()) {
            assertThat(status.isTerminal()).isEqualTo(terminal.contains(status));
        }
    }

    @Test
    void exposesOnlyApprovedStateTransitions() {
        assertThat(RunStatus.CREATED.allowedTargets())
                .containsExactlyInAnyOrder(RunStatus.CONTEXT_READY, RunStatus.FAILED);
        assertThat(RunStatus.CONTEXT_READY.allowedTargets())
                .containsExactlyInAnyOrder(RunStatus.DECIDED, RunStatus.FAILED);
        assertThat(RunStatus.DECIDED.allowedTargets())
                .containsExactlyInAnyOrder(RunStatus.RUNNING, RunStatus.FAILED);
        assertThat(RunStatus.RUNNING.allowedTargets())
                .containsExactlyInAnyOrder(
                        RunStatus.WAITING_CONFIRMATION,
                        RunStatus.VERIFYING,
                        RunStatus.FAILED
                );
        assertThat(RunStatus.WAITING_CONFIRMATION.allowedTargets())
                .containsExactlyInAnyOrder(RunStatus.RUNNING, RunStatus.FAILED);
        assertThat(RunStatus.VERIFYING.allowedTargets())
                .containsExactlyInAnyOrder(
                        RunStatus.DECIDED,
                        RunStatus.COMPLETED,
                        RunStatus.DEGRADED,
                        RunStatus.FAILED
                );
        assertThat(RunStatus.COMPLETED.allowedTargets()).isEmpty();
        assertThat(RunStatus.DEGRADED.allowedTargets()).isEmpty();
        assertThat(RunStatus.FAILED.allowedTargets()).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -q -Dtest=RunStatusTest test
```

Expected: compilation fails because `RunStatus` does not exist.

- [ ] **Step 3: Implement the lifecycle enum**

```java
package com.springclaw.runtime.contract;

import java.util.Set;

public enum RunStatus {
    CREATED,
    CONTEXT_READY,
    DECIDED,
    WAITING_CONFIRMATION,
    RUNNING,
    VERIFYING,
    COMPLETED,
    DEGRADED,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == DEGRADED || this == FAILED;
    }

    public Set<RunStatus> allowedTargets() {
        return switch (this) {
            case CREATED -> Set.of(CONTEXT_READY, FAILED);
            case CONTEXT_READY -> Set.of(DECIDED, FAILED);
            case DECIDED -> Set.of(RUNNING, FAILED);
            case RUNNING -> Set.of(WAITING_CONFIRMATION, VERIFYING, FAILED);
            case WAITING_CONFIRMATION -> Set.of(RUNNING, FAILED);
            case VERIFYING -> Set.of(DECIDED, COMPLETED, DEGRADED, FAILED);
            case COMPLETED, DEGRADED, FAILED -> Set.of();
        };
    }

    public boolean canTransitionTo(RunStatus target) {
        return target != null && allowedTargets().contains(target);
    }
}
```

- [ ] **Step 4: Run the test and verify GREEN**

Run:

```bash
mvn -q -Dtest=RunStatusTest test
```

Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/springclaw/runtime/contract/RunStatus.java \
  src/test/java/com/springclaw/runtime/contract/RunStatusTest.java
git commit -m "feat: define canonical run lifecycle states"
```

### Task 3: Define immutable context and decision contracts

**Files:**
- Create: `src/main/java/com/springclaw/runtime/contract/ContextSnapshot.java`
- Create: `src/main/java/com/springclaw/runtime/contract/ExecutionDecision.java`
- Test: `src/test/java/com/springclaw/runtime/contract/ContextAndDecisionContractTest.java`

- [ ] **Step 1: Write failing immutability and identifier tests**

```java
package com.springclaw.runtime.contract;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextAndDecisionContractTest {

    @Test
    void contextSnapshotCopiesEveryCollection() {
        List<String> events = new ArrayList<>(List.of("event-1"));
        Map<String, String> provider = new HashMap<>(Map.of("providerId", "p1"));

        ContextSnapshot snapshot = new ContextSnapshot(
                "run-1", "session-1", "user-1", "web", "user-1", "USER",
                "original", "effective", "system", "memory",
                events, List.of("semantic-1"), List.of("rule-1"),
                List.of("web.search"), provider, Map.of("schema", "v1"),
                Instant.parse("2026-06-19T00:00:00Z"), "hash-1"
        );

        events.add("event-2");
        provider.put("modelId", "m1");

        assertThat(snapshot.shortTermEvents()).containsExactly("event-1");
        assertThat(snapshot.providerSnapshot()).containsOnlyEntry("providerId", "p1");
        assertThatThrownBy(() -> snapshot.allowedCapabilities().add("workspace.edit"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void executionDecisionRequiresTheSameRunIdentifier() {
        assertThatThrownBy(() -> new ExecutionDecision(
                " ", "research", "answer", "agent", "read",
                List.of(), List.of(), Map.of(), List.of(), 0.8,
                "matched capability", "policy", Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("runId");
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -q -Dtest=ContextAndDecisionContractTest test
```

Expected: compilation fails because both contracts are missing.

- [ ] **Step 3: Implement `ContextSnapshot`**

```java
package com.springclaw.runtime.contract;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ContextSnapshot(
        String runId,
        String sessionKey,
        String sessionOwnerUserId,
        String channel,
        String userId,
        String roleCode,
        String originalMessage,
        String effectiveMessage,
        String systemPrompt,
        String memoryBankText,
        List<String> shortTermEvents,
        List<String> semanticRecallItems,
        List<String> activeLearningRules,
        List<String> allowedCapabilities,
        Map<String, String> providerSnapshot,
        Map<String, String> contextSourceSummary,
        Instant capturedAt,
        String snapshotHash
) {
    public ContextSnapshot {
        runId = requireText(runId, "runId");
        sessionKey = requireText(sessionKey, "sessionKey");
        sessionOwnerUserId = requireText(sessionOwnerUserId, "sessionOwnerUserId");
        channel = requireText(channel, "channel");
        userId = requireText(userId, "userId");
        roleCode = requireText(roleCode, "roleCode");
        originalMessage = Objects.requireNonNullElse(originalMessage, "");
        effectiveMessage = Objects.requireNonNullElse(effectiveMessage, "");
        systemPrompt = Objects.requireNonNullElse(systemPrompt, "");
        memoryBankText = Objects.requireNonNullElse(memoryBankText, "");
        shortTermEvents = copy(shortTermEvents);
        semanticRecallItems = copy(semanticRecallItems);
        activeLearningRules = copy(activeLearningRules);
        allowedCapabilities = copy(allowedCapabilities);
        providerSnapshot = providerSnapshot == null ? Map.of() : Map.copyOf(providerSnapshot);
        contextSourceSummary = contextSourceSummary == null ? Map.of() : Map.copyOf(contextSourceSummary);
        capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
        snapshotHash = requireText(snapshotHash, "snapshotHash");
    }

    private static List<String> copy(List<String> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
```

- [ ] **Step 4: Implement `ExecutionDecision`**

```java
package com.springclaw.runtime.contract;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ExecutionDecision(
        String runId,
        String intent,
        String goal,
        String responseMode,
        String riskSummary,
        List<String> selectedCapabilityIds,
        List<String> requestedInvocations,
        Map<String, String> strategyRequirements,
        List<String> missingInputs,
        double confidence,
        String reason,
        String decisionSource,
        Instant decidedAt
) {
    public ExecutionDecision {
        runId = requireText(runId, "runId");
        intent = requireText(intent, "intent");
        goal = Objects.requireNonNullElse(goal, "");
        responseMode = requireText(responseMode, "responseMode");
        riskSummary = Objects.requireNonNullElse(riskSummary, "");
        selectedCapabilityIds = copy(selectedCapabilityIds);
        requestedInvocations = copy(requestedInvocations);
        strategyRequirements = strategyRequirements == null ? Map.of() : Map.copyOf(strategyRequirements);
        missingInputs = copy(missingInputs);
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        reason = Objects.requireNonNullElse(reason, "");
        decisionSource = requireText(decisionSource, "decisionSource");
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
    }

    private static List<String> copy(List<String> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
```

- [ ] **Step 5: Run the tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=ContextAndDecisionContractTest test
```

Expected: 2 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/runtime/contract/ContextSnapshot.java \
  src/main/java/com/springclaw/runtime/contract/ExecutionDecision.java \
  src/test/java/com/springclaw/runtime/contract/ContextAndDecisionContractTest.java
git commit -m "feat: add runtime context and decision contracts"
```

### Task 4: Define tool, completion, and terminal-result contracts

**Files:**
- Create: `src/main/java/com/springclaw/runtime/contract/ToolInvocation.java`
- Create: `src/main/java/com/springclaw/runtime/contract/CompletionDecision.java`
- Create: `src/main/java/com/springclaw/runtime/contract/RunResult.java`
- Test: `src/test/java/com/springclaw/runtime/contract/ToolCompletionAndResultContractTest.java`

- [ ] **Step 1: Write failing safety and terminal-result tests**

```java
package com.springclaw.runtime.contract;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolCompletionAndResultContractTest {

    @Test
    void toolInvocationFreezesArgumentsPathsAndEvidence() {
        ToolInvocation invocation = new ToolInvocation(
                "inv-1", "run-1", 1, "workspace", "write-file",
                "workspaceEdit", "workspace-edit", "{\"path\":\"a.txt\"}",
                "sha256", ToolInvocation.RiskLevel.WRITE, List.of("a.txt"),
                List.of("file:a.txt"), "run-1:1:inv-1",
                ToolInvocation.Status.REQUESTED, null, null, null, null
        );

        assertThat(invocation.targetPaths()).containsExactly("a.txt");
        assertThatThrownBy(() -> invocation.targetPaths().add("b.txt"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void completionDecisionConstrainsRetryMetadata() {
        assertThatThrownBy(() -> new CompletionDecision(
                "run-1", CompletionDecision.Outcome.RETRY, "retry",
                "try again", List.of(), List.of("evidence"), true,
                1, 0.5, Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nextAttempt");
    }

    @Test
    void failedResultRequiresFailureCode() {
        assertThatThrownBy(() -> new RunResult(
                "run-1", RunStatus.FAILED, "answer", RunResult.AnswerKind.FINAL,
                "provider", "model", List.of(), List.of(), 1.0,
                Map.of(), null, null, Instant.now()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failureCode");
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -q -Dtest=ToolCompletionAndResultContractTest test
```

Expected: compilation fails because the contracts are missing.

- [ ] **Step 3: Implement the three records**

Implement `ToolInvocation` with nested enums `RiskLevel { READ, WRITE, SIDE_EFFECT, DANGEROUS }` and `Status { REQUESTED, WAITING_CONFIRMATION, APPROVED, RUNNING, SUCCEEDED, FAILED, DENIED }`. Its compact constructor must require non-blank identifiers, require `attempt >= 1`, copy `targetPaths` and `expectedEvidence`, require canonical arguments plus hash, and require `proposalId` when status is `WAITING_CONFIRMATION`.

Implement `ToolInvocation.Outcome` as:

```java
public record Outcome(
        boolean success,
        String code,
        String summary,
        List<String> evidenceRefs,
        Instant completedAt
) {
    public Outcome {
        code = code == null ? "" : code;
        summary = summary == null ? "" : summary;
        evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
    }
}
```

Implement `CompletionDecision` with nested enum:

```java
public enum Outcome {
    COMPLETE,
    RETRY,
    DEGRADE,
    FAIL,
    WAIT_FOR_CONFIRMATION
}
```

Its compact constructor must require `runId`, `outcome`, `reasonCode`, `decidedAt`; copy evidence lists; constrain quality to `0..1`; require `retryAllowed=true` and `nextAttempt >= 2` only for `RETRY`; normalize non-retry `nextAttempt` to `0`.

Implement `RunResult` with nested enum:

```java
public enum AnswerKind {
    FINAL,
    DEGRADED,
    FAILURE
}
```

Its compact constructor must require a terminal status and matching semantics:

```java
if (!status.isTerminal()) {
    throw new IllegalArgumentException("RunResult status must be terminal");
}
if (status == RunStatus.FAILED && (failureCode == null || failureCode.isBlank())) {
    throw new IllegalArgumentException("failureCode is required for FAILED result");
}
if (status != RunStatus.FAILED && answerKind == AnswerKind.FAILURE) {
    throw new IllegalArgumentException("FAILURE answerKind requires FAILED status");
}
```

Copy `evidenceRefs`, `toolInvocationIds`, and `usage`; require quality in `0..1` and non-null `completedAt`.

- [ ] **Step 4: Run the tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=ToolCompletionAndResultContractTest test
```

Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/springclaw/runtime/contract/ToolInvocation.java \
  src/main/java/com/springclaw/runtime/contract/CompletionDecision.java \
  src/main/java/com/springclaw/runtime/contract/RunResult.java \
  src/test/java/com/springclaw/runtime/contract/ToolCompletionAndResultContractTest.java
git commit -m "feat: define runtime safety and result contracts"
```

### Task 5: Define ordered run events

**Files:**
- Create: `src/main/java/com/springclaw/runtime/contract/RunEventType.java`
- Create: `src/main/java/com/springclaw/runtime/contract/RunEvent.java`
- Test: `src/test/java/com/springclaw/runtime/contract/RunEventContractTest.java`

- [ ] **Step 1: Write the failing serialization test**

```java
package com.springclaw.runtime.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunEventContractTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void serializesStableEventFamilyName() throws Exception {
        RunEvent event = new RunEvent(
                "evt-1", "run-1", 1, RunEventType.CONTEXT_READY,
                "context", RunStatus.CONTEXT_READY,
                Instant.parse("2026-06-19T00:00:00Z"), 12,
                "springclaw.run-event.context-ready.v1", "{\"hash\":\"h1\"}",
                "cmd-1", "run-1"
        );

        assertThat(mapper.writeValueAsString(event))
                .contains("\"eventType\":\"context.ready\"");
    }

    @Test
    void rejectsNonPositiveSequence() {
        assertThatThrownBy(() -> new RunEvent(
                "evt-1", "run-1", 0, RunEventType.RUN_CREATED,
                "acceptance", RunStatus.CREATED, Instant.now(), 0,
                "v1", "{}", null, "run-1"
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence");
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -q -Dtest=RunEventContractTest test
```

Expected: compilation fails because event types are missing.

- [ ] **Step 3: Implement `RunEventType`**

Create enum constants for all approved families and annotate the stable wire value:

```java
package com.springclaw.runtime.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum RunEventType {
    RUN_CREATED("run.created"),
    CONTEXT_READY("context.ready"),
    DECISION_MADE("decision.made"),
    STRATEGY_STARTED("strategy.started"),
    MODEL_CALLED("model.called"),
    TOOL_REQUESTED("tool.requested"),
    CONFIRMATION_REQUIRED("confirmation.required"),
    CONFIRMATION_APPROVED("confirmation.approved"),
    CONFIRMATION_REJECTED("confirmation.rejected"),
    TOOL_STARTED("tool.started"),
    TOOL_SUCCEEDED("tool.succeeded"),
    TOOL_FAILED("tool.failed"),
    VERIFICATION_COMPLETED("verification.completed"),
    ANSWER_COMPOSED("answer.composed"),
    RUN_COMPLETED("run.completed"),
    RUN_DEGRADED("run.degraded"),
    RUN_FAILED("run.failed"),
    DELIVERY_ATTEMPTED("delivery.attempted"),
    DELIVERY_FAILED("delivery.failed");

    private final String wireName;

    RunEventType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static RunEventType fromWireName(String value) {
        return Arrays.stream(values())
                .filter(type -> type.wireName.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown run event type: " + value));
    }
}
```

- [ ] **Step 4: Implement `RunEvent`**

Use the required spec fields with `Instant timestamp` and JSON payload text:

```java
package com.springclaw.runtime.contract;

import java.time.Instant;
import java.util.Objects;

public record RunEvent(
        String eventId,
        String runId,
        long sequence,
        RunEventType eventType,
        String stage,
        RunStatus status,
        Instant timestamp,
        long durationMs,
        String payloadSchema,
        String payload,
        String causationId,
        String correlationId
) {
    public RunEvent {
        eventId = requireText(eventId, "eventId");
        runId = requireText(runId, "runId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        eventType = Objects.requireNonNull(eventType, "eventType");
        stage = requireText(stage, "stage");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        payloadSchema = requireText(payloadSchema, "payloadSchema");
        payload = Objects.requireNonNullElse(payload, "{}");
        correlationId = requireText(correlationId, "correlationId");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
```

- [ ] **Step 5: Run the tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=RunEventContractTest test
```

Expected: 2 tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/runtime/contract/RunEventType.java \
  src/main/java/com/springclaw/runtime/contract/RunEvent.java \
  src/test/java/com/springclaw/runtime/contract/RunEventContractTest.java
git commit -m "feat: add canonical runtime event contract"
```

### Task 6: Define the canonical aggregate and transition validation

**Files:**
- Create: `src/main/java/com/springclaw/runtime/contract/RunState.java`
- Create: `src/main/java/com/springclaw/runtime/contract/RunTransitionPolicy.java`
- Test: `src/test/java/com/springclaw/runtime/contract/RunStateContractTest.java`

- [ ] **Step 1: Write failing aggregate invariant tests**

Create fixtures for `CREATED`, `WAITING_CONFIRMATION`, `COMPLETED`, and `FAILED`, then assert:

```java
assertThatThrownBy(() -> createdState("run-1", "request-2"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("equal");

assertThatThrownBy(() -> waitingState(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pendingProposalId");

assertThatThrownBy(() -> completedState(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RunResult");

assertThatThrownBy(() -> failedState(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("failure");

assertThatThrownBy(() -> RunTransitionPolicy.validate(completedState(result()), completedState(result())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("terminal");

assertThatThrownBy(() -> RunTransitionPolicy.validate(createdState(), runningState()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("CREATED -> RUNNING");
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -q -Dtest=RunStateContractTest test
```

Expected: compilation fails because `RunState` and `RunTransitionPolicy` are missing.

- [ ] **Step 3: Implement `RunState`**

Use all required fields from the architecture spec:

```java
public record RunState(
        String runId,
        String requestId,
        long revision,
        RunStatus status,
        String sessionKey,
        String channel,
        String userId,
        String roleCodeAtAcceptance,
        String originalMessage,
        String responseMode,
        Instant acceptedAt,
        Instant startedAt,
        Instant updatedAt,
        Instant finishedAt,
        Instant deadlineAt,
        ContextSnapshot contextSnapshot,
        ExecutionDecision executionDecision,
        String strategyId,
        int attempt,
        String pendingProposalId,
        List<ToolInvocation> toolInvocations,
        CompletionDecision completionDecision,
        RunResult result,
        Map<String, Long> usage,
        Failure failure
) {
    public record Failure(String code, String message, boolean retryable) {
        public Failure {
            code = requireText(code, "failure.code");
            message = message == null ? "" : message;
        }
    }
}
```

The compact constructor must:

- require non-blank `runId`, `requestId`, `sessionKey`, `channel`, `userId`, `roleCodeAtAcceptance`, and `responseMode`;
- require `runId.equals(requestId)`;
- require `revision >= 0` and `attempt >= 1`;
- require non-null `status`, `acceptedAt`, `updatedAt`, and `deadlineAt`;
- copy `toolInvocations` and `usage`;
- require `pendingProposalId` for `WAITING_CONFIRMATION`;
- require `result` and `finishedAt` for `COMPLETED` or `DEGRADED`;
- require `failure` and `finishedAt` for `FAILED`.

Add a Jackson round-trip test using `new ObjectMapper().registerModule(new JavaTimeModule())`. Serialize a valid terminal `RunState`, deserialize it, and assert that the resulting record equals the original. This verifies the complete aggregate and all nested contracts are persistence-safe before repositories exist.

- [ ] **Step 4: Implement `RunTransitionPolicy`**

```java
package com.springclaw.runtime.contract;

import java.util.Objects;

public final class RunTransitionPolicy {

    private RunTransitionPolicy() {
    }

    public static void validate(RunState previous, RunState next) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
        if (previous.status().isTerminal()) {
            throw new IllegalStateException("terminal run state is immutable: " + previous.status());
        }
        if (!previous.runId().equals(next.runId())
                || !previous.requestId().equals(next.requestId())) {
            throw new IllegalStateException("run identity cannot change");
        }
        if (next.revision() != previous.revision() + 1) {
            throw new IllegalStateException("revision must increase by exactly one");
        }
        if (!previous.status().canTransitionTo(next.status())) {
            throw new IllegalStateException(
                    "invalid run transition: " + previous.status() + " -> " + next.status()
            );
        }
        if (next.updatedAt().isBefore(previous.updatedAt())) {
            throw new IllegalStateException("updatedAt cannot move backwards");
        }
    }
}
```

- [ ] **Step 5: Run the tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=RunStateContractTest test
```

Expected: all aggregate and transition tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/springclaw/runtime/contract/RunState.java \
  src/main/java/com/springclaw/runtime/contract/RunTransitionPolicy.java \
  src/test/java/com/springclaw/runtime/contract/RunStateContractTest.java
git commit -m "feat: add canonical run state aggregate"
```

### Task 7: Define the strategy boundary

**Files:**
- Create: `src/main/java/com/springclaw/runtime/contract/RuntimeStrategy.java`
- Test: `src/test/java/com/springclaw/runtime/contract/RuntimeStrategyContractTest.java`

- [ ] **Step 1: Write the failing API-shape test**

```java
package com.springclaw.runtime.contract;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeStrategyContractTest {

    @Test
    void strategyBoundaryContainsNoTransportOrPersistenceTypes() {
        Set<String> forbiddenFragments = Set.of(
                "SseEmitter",
                "RabbitTemplate",
                "HttpServlet",
                "Repository",
                "Mapper"
        );

        String signatureText = Arrays.stream(RuntimeStrategy.class.getDeclaredMethods())
                .map(method -> method.toGenericString())
                .reduce("", (left, right) -> left + "\n" + right);

        assertThat(forbiddenFragments)
                .allSatisfy(fragment -> assertThat(signatureText).doesNotContain(fragment));
    }

    @Test
    void strategyExposesOnlyIdentityCapabilitiesExecuteAndResume() {
        assertThat(Arrays.stream(RuntimeStrategy.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .containsExactlyInAnyOrder("strategyId", "capabilities", "execute", "resume");
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -q -Dtest=RuntimeStrategyContractTest test
```

Expected: compilation fails because `RuntimeStrategy` is missing.

- [ ] **Step 3: Implement the boundary**

```java
package com.springclaw.runtime.contract;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface RuntimeStrategy {

    String strategyId();

    StrategyCapabilities capabilities();

    StrategyExecution execute(RunExecutionContext context);

    StrategyExecution resume(RunExecutionContext context, ToolInvocation.Outcome outcome);

    record StrategyCapabilities(Set<String> capabilityIds, boolean resumable) {
        public StrategyCapabilities {
            capabilityIds = capabilityIds == null ? Set.of() : Set.copyOf(capabilityIds);
        }
    }

    record RunExecutionContext(
            RunState runState,
            ContextSnapshot contextSnapshot,
            ExecutionDecision executionDecision
    ) {
        public RunExecutionContext {
            if (runState == null || contextSnapshot == null || executionDecision == null) {
                throw new IllegalArgumentException("runState, contextSnapshot, and executionDecision are required");
            }
            if (!runState.runId().equals(contextSnapshot.runId())
                    || !runState.runId().equals(executionDecision.runId())) {
                throw new IllegalArgumentException("execution context runId values must match");
            }
        }
    }

    record StrategyExecution(
            List<RunEvent> events,
            List<String> evidence,
            ToolInvocation requestedToolInvocation,
            Map<String, Long> modelUsage,
            String continuationToken,
            RunState.Failure strategyFailure
    ) {
        public StrategyExecution {
            events = events == null ? List.of() : List.copyOf(events);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            modelUsage = modelUsage == null ? Map.of() : Map.copyOf(modelUsage);
        }
    }
}
```

- [ ] **Step 4: Run the tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=RuntimeStrategyContractTest test
```

Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/springclaw/runtime/contract/RuntimeStrategy.java \
  src/test/java/com/springclaw/runtime/contract/RuntimeStrategyContractTest.java
git commit -m "feat: define canonical runtime strategy boundary"
```

### Task 8: Add legacy fixture translation and full acceptance

**Files:**
- Create: `src/test/java/com/springclaw/runtime/contract/LegacyRuntimeContractFixtures.java`
- Create: `src/test/java/com/springclaw/runtime/contract/LegacyRuntimeContractFixturesTest.java`
- Modify: `docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md`

- [ ] **Step 1: Write a failing fixture translation test**

Build an existing `AssembledContext` and `AgentDecision`, translate them through `LegacyRuntimeContractFixtures`, and assert:

```java
assertThat(snapshot.runId()).isEqualTo("run-1");
assertThat(snapshot.sessionKey()).isEqualTo(assembled.sessionKey());
assertThat(snapshot.effectiveMessage()).isEqualTo(assembled.question());
assertThat(decision.intent()).isEqualTo(agentDecision.intent());
assertThat(decision.selectedCapabilityIds())
        .containsExactlyElementsOf(agentDecision.selectedCapabilities());
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
mvn -q -Dtest=LegacyRuntimeContractFixturesTest test
```

Expected: compilation fails because the fixture translator is missing.

- [ ] **Step 3: Implement a test-only translator**

`LegacyRuntimeContractFixtures` must expose:

```java
static ContextSnapshot contextSnapshot(
        String runId,
        AssembledContext assembled,
        String roleCode,
        Instant capturedAt
)

static ExecutionDecision executionDecision(
        String runId,
        AgentDecision legacy,
        String responseMode,
        Instant decidedAt
)
```

The translator must not be placed under `src/main`; it exists only to prove current records can populate the new contracts before production adapters are introduced.

- [ ] **Step 4: Run all domain contract tests**

Run:

```bash
mvn -q \
  -Dtest=RunStatusTest,ContextAndDecisionContractTest,ToolCompletionAndResultContractTest,RunEventContractTest,RunStateContractTest,RuntimeStrategyContractTest,LegacyRuntimeContractFixturesTest \
  test
```

Expected: all new contract tests pass.

- [ ] **Step 5: Re-run current characterization and focused baseline suites**

Run:

```bash
mvn -q \
  -Dtest=RuntimeRouteCharacterizationTest,ContextPropagationCharacterizationTest,ToolSafetyPathCharacterizationTest,FinalAnswerOwnershipCharacterizationTest,TransportParityCharacterizationTest \
  test

mvn -q \
  -Dtest=EngineSelectorTest,ChatContextFactoryTest,PromptInjectionTest,AgentRuntimeEngineTest,ToolRuntimeAspectInterceptionIT,ToolInvocationProposalServiceConfirmTest \
  test
```

Expected:

- characterization XML contains 41 testcases with 0 failures, 0 errors, and 0 skips;
- focused baseline contains 27 tests with 0 failures, 0 errors, and 0 skips;
- the known local MySQL `Public Key Retrieval is not allowed` warning may appear without failing Maven.

- [ ] **Step 6: Verify no existing production behavior was edited**

Run:

```bash
git diff --name-only 05049d7..HEAD -- src/main/java \
  | rg -v '^src/main/java/com/springclaw/runtime/contract/'
```

Expected: no output.

- [ ] **Step 7: Commit the fixture translator**

```bash
git add src/test/java/com/springclaw/runtime/contract/LegacyRuntimeContractFixtures.java \
  src/test/java/com/springclaw/runtime/contract/LegacyRuntimeContractFixturesTest.java
git commit -m "test: verify unified runtime contract compatibility"
```

- [ ] **Step 8: Record Phase 2 domain-contract evidence**

Capture the exact implementation commit list:

```bash
git log --reverse --format='%h %s' 05049d7..HEAD
```

Add an update-protocol block to the collaboration ledger containing:

```text
Task: unified-runtime-domain-contracts
Owner: Codex
Branch: codex/unified-agent-runtime
Commits: copy the exact commit lines printed by the preceding git log command
Files: isolated com.springclaw.runtime.contract package and contract tests
Tests: domain contracts, 41 characterization tests, 27 focused baseline tests
Findings: no existing production routing, safety, persistence, or transport file changed
Next dependency: unified-runtime-legacy-bridge plan
```

- [ ] **Step 9: Commit the ledger evidence**

```bash
git add docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
git commit -m "docs: record runtime domain contract evidence"
```

## Final verification

Run:

```bash
git diff --check
rg -n "T[B]D|T[O]DO|F[I]XME|待[定]|以后处[理]" \
  src/main/java/com/springclaw/runtime/contract \
  src/test/java/com/springclaw/runtime/contract
git status --short
```

Expected:

- no whitespace errors;
- no placeholders;
- clean worktree;
- no existing API, engine selection, tool authorization, persistence, or transport behavior changed.

## Rollback boundary

Revert the merge only if the characterization baseline itself must be removed. Revert the domain-contract commits recorded in the collaboration ledger to remove this implementation. Because no existing production type imports `com.springclaw.runtime.contract` in this plan, rollback does not require changes to active runtime code.
