# Runtime Context Identity Convergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox notation so progress can be tracked without reinterpreting the design.

**Goal:** Make the accepted `RunState` the sole identity authority after acceptance and guarantee that initial execution, retry, and resume paths consume the one canonical persisted `ContextSnapshot` for the run.

**Architecture:** Add an accepted-run boundary resolver that validates immutable request fields before any downstream work, then add a canonical snapshot resolver that either commits the first snapshot candidate or reuses the stored snapshot. Refactor `ChatContextFactory` to consume those two boundaries while preserving the explicit legacy rollback path.

**Tech Stack:** Java 21, Spring Boot, Spring `ObjectProvider`, JUnit 5, AssertJ, Mockito, Maven, canonical runtime contracts under `com.springclaw.runtime`.

**Global Constraints:** Keep `springclaw.context.snapshot.factory-enabled=false` behavior unchanged; never echo prompt contents or credentials in mismatch errors; do not change persistence schemas, execution-engine selection, memory ranking, or tool authorization; use accepted identity in canonical mode; return only the snapshot contained in a canonical `RunState`; keep each commit independently testable.

---

## Task 1: Introduce typed accepted-run identity boundary

**Files:**

- Create: `src/main/java/com/springclaw/runtime/bridge/CanonicalRunContextException.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/AcceptedRunContext.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/AcceptedRunContextResolver.java`
- Create: `src/test/java/com/springclaw/runtime/bridge/AcceptedRunContextResolverTest.java`

- [ ] **Step 1: Write the failing resolver tests**

Create `AcceptedRunContextResolverTest` with a valid `CREATED` fixture and parameterized mismatch coverage. The tests must prove:

- a matching request returns accessors backed by the loaded `RunState`;
- blank request channel normalizes to `api`;
- blank request response mode normalizes to `agent`;
- mismatches for run ID, session key, normalized channel, user ID, original message, and normalized response mode each throw `CanonicalRunContextException` with code `ACCEPTED_REQUEST_MISMATCH`;
- terminal states throw code `RUN_ALREADY_TERMINAL`;
- mismatch messages name only the field, not the original message value.

Use assertions shaped like:

```java
CanonicalRunContextException failure = catchThrowableOfType(
        () -> resolver.resolve(RUN_ID, mismatchedRequest),
        CanonicalRunContextException.class
);
assertThat(failure.code()).isEqualTo(
        CanonicalRunContextException.Code.ACCEPTED_REQUEST_MISMATCH
);
assertThat(failure.getMessage()).contains("sessionKey");
assertThat(failure.getMessage()).doesNotContain(SECRET_MESSAGE);
```

- [ ] **Step 2: Run the focused test and confirm the expected compile failure**

Run:

```bash
mvn -q -Dtest=AcceptedRunContextResolverTest test
```

Expected: compilation fails because `AcceptedRunContextResolver`, `AcceptedRunContext`, and `CanonicalRunContextException` do not exist.

- [ ] **Step 3: Implement the typed exception**

Implement a final runtime exception with a stable enum code:

```java
public final class CanonicalRunContextException extends RuntimeException {
    public enum Code {
        ACCEPTED_REQUEST_MISMATCH,
        RUN_ALREADY_TERMINAL,
        CANONICAL_SNAPSHOT_INVARIANT,
        CANONICAL_PROVIDER_MISMATCH
    }

    private final Code code;

    public CanonicalRunContextException(Code code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
    }

    public CanonicalRunContextException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code");
    }

    public Code code() {
        return code;
    }
}
```

- [ ] **Step 4: Implement the accepted context record**

Implement `AcceptedRunContext` as a one-field public record. Its accessor methods must delegate to immutable `RunState` fields:

```java
public record AcceptedRunContext(RunState runState) {
    public AcceptedRunContext {
        Objects.requireNonNull(runState, "runState");
    }

    public String runId() { return runState.runId(); }
    public String sessionKey() { return runState.sessionKey(); }
    public String channel() { return runState.channel(); }
    public String userId() { return runState.userId(); }
    public String roleCode() { return runState.roleCodeAtAcceptance(); }
    public String originalMessage() { return runState.originalMessage(); }
    public String responseMode() { return runState.responseMode(); }
    public SessionAccessClaim sessionAccessClaim() {
        return runState.sessionAccessClaim();
    }
}
```

- [ ] **Step 5: Implement resolver validation in one place**

Implement `AcceptedRunContextResolver` with constructor injection of `RunStateRepository`. Resolve in this order:

1. reject blank supplied run ID;
2. load `RunState` with `requireByRunId`;
3. compare supplied ID to both `runId` and `requestId`;
4. reject a terminal state;
5. compare request fields to the canonical fields;
6. explicitly compare the `SessionAccessClaim` channel, session key, and accepted user to the run identity;
7. return `new AcceptedRunContext(runState)`.

Use these exact normalizers only for transport-defaulted fields:

```java
private static String normalizeChannel(String value) {
    return StringUtils.hasText(value)
            ? value.trim().toLowerCase(Locale.ROOT)
            : "api";
}

private static String normalizeResponseMode(String value) {
    return StringUtils.hasText(value)
            ? value.trim().toLowerCase(Locale.ROOT)
            : "agent";
}
```

Compare session key, user ID, and original message exactly. Centralize failure creation so messages have the safe form `accepted request mismatch: <field>` and never include field values.

- [ ] **Step 6: Run the focused test and verify success**

Run:

```bash
mvn -q -Dtest=AcceptedRunContextResolverTest test
```

Expected: all accepted-run resolver tests pass.

- [ ] **Step 7: Commit the boundary**

Run:

```bash
git add src/main/java/com/springclaw/runtime/bridge/CanonicalRunContextException.java \
  src/main/java/com/springclaw/runtime/bridge/AcceptedRunContext.java \
  src/main/java/com/springclaw/runtime/bridge/AcceptedRunContextResolver.java \
  src/test/java/com/springclaw/runtime/bridge/AcceptedRunContextResolverTest.java
git diff --cached --check
git commit -m "feat: enforce accepted run identity"
```

Expected: the commit succeeds with only the four boundary files.

## Task 2: Resolve exactly one canonical snapshot per run

**Files:**

- Create: `src/main/java/com/springclaw/runtime/bridge/CanonicalContextSnapshotResolver.java`
- Create: `src/test/java/com/springclaw/runtime/bridge/CanonicalContextSnapshotResolverTest.java`
- Modify: `src/test/java/com/springclaw/runtime/bridge/CanonicalContextReadyProjectorTest.java`

- [ ] **Step 1: Write lifecycle and race tests first**

Create focused tests for this behavior table:

| Starting state | Expected result | Candidate supplier |
|---|---|---|
| `CREATED`, no snapshot | snapshot from projector-returned committed state | called once |
| `CREATED`, snapshot present | `CANONICAL_SNAPSHOT_INVARIANT` | never called |
| `CONTEXT_READY`, snapshot present | stored snapshot | never called |
| `DECIDED`, `RUNNING`, `WAITING_CONFIRMATION`, or `VERIFYING`, snapshot present | stored snapshot | never called |
| later non-terminal state without snapshot | `CANONICAL_SNAPSHOT_INVARIANT` | never called |
| projection failure followed by reload with a snapshot | reloaded winner snapshot | called once |
| projection failure followed by reload without a snapshot | `CANONICAL_SNAPSHOT_INVARIANT` retaining the cause | called once |

Use two different snapshot instances and hashes in first-creation and race tests. Assert identity with `isSameAs(committedSnapshot)` so returning the local losing candidate cannot pass accidentally.

- [ ] **Step 2: Run the new test and confirm the expected compile failure**

Run:

```bash
mvn -q -Dtest=CanonicalContextSnapshotResolverTest test
```

Expected: compilation fails because `CanonicalContextSnapshotResolver` does not exist.

- [ ] **Step 3: Implement state-sensitive snapshot resolution**

Constructor-inject `CanonicalContextReadyProjector` and `RunStateRepository`. Implement the core method with these guards:

```java
public ContextSnapshot resolve(
        AcceptedRunContext accepted,
        Supplier<ContextSnapshot> snapshotCandidate
) {
    Objects.requireNonNull(accepted, "accepted");
    Objects.requireNonNull(snapshotCandidate, "snapshotCandidate");
    RunState initial = accepted.runState();

    if (initial.status() == RunStatus.CREATED) {
        if (initial.contextSnapshot() != null) {
            throw invariant(initial.runId(), "CREATED run already owns a snapshot");
        }
        ContextSnapshot candidate = Objects.requireNonNull(
                snapshotCandidate.get(),
                "snapshotCandidate returned null"
        );
        requireRunId(initial.runId(), candidate);
        try {
            return requireCanonicalSnapshot(
                    projector.project(initial.runId(), candidate, candidate.capturedAt())
            );
        } catch (RuntimeException projectionFailure) {
            RunState reloaded = repository.requireByRunId(initial.runId());
            if (!reloaded.status().isTerminal()
                    && reloaded.contextSnapshot() != null) {
                return reloaded.contextSnapshot();
            }
            throw invariant(
                    initial.runId(),
                    "snapshot projection did not produce canonical state",
                    projectionFailure
            );
        }
    }

    if (initial.status().isTerminal() || initial.contextSnapshot() == null) {
        throw invariant(initial.runId(), "non-created run has no reusable snapshot");
    }
    return initial.contextSnapshot();
}
```

`requireCanonicalSnapshot` must verify the returned state has the same run ID, is non-terminal, is no longer `CREATED`, and has a non-null snapshot. All lifecycle failures use code `CANONICAL_SNAPSHOT_INVARIANT` and safe messages without serializing the snapshot.

- [ ] **Step 4: Strengthen projector characterization**

Add a test to `CanonicalContextReadyProjectorTest` proving that when repository state is already `CONTEXT_READY`, `project(...)` returns that repository object and its stored snapshot rather than the supplied candidate.

- [ ] **Step 5: Run focused runtime-bridge tests**

Run:

```bash
mvn -q -Dtest=AcceptedRunContextResolverTest,CanonicalContextSnapshotResolverTest,CanonicalContextReadyProjectorTest test
```

Expected: all three test classes pass.

- [ ] **Step 6: Commit canonical snapshot ownership**

Run:

```bash
git add src/main/java/com/springclaw/runtime/bridge/CanonicalContextSnapshotResolver.java \
  src/test/java/com/springclaw/runtime/bridge/CanonicalContextSnapshotResolverTest.java \
  src/test/java/com/springclaw/runtime/bridge/CanonicalContextReadyProjectorTest.java
git diff --cached --check
git commit -m "feat: reuse canonical context snapshots"
```

Expected: the commit contains the resolver and its focused lifecycle tests.

## Task 3: Wire canonical boundaries without changing rollback mode

**Files:**

- Modify: `src/main/java/com/springclaw/config/ContextSnapshotConfig.java`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalOwnershipTest.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalSnapshotTest.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalLifecycleProjectionTest.java`

- [ ] **Step 1: Replace the existing ownership expectation with failing security tests**

In `ChatContextFactoryCanonicalOwnershipTest`, replace the current test that accepts `forged-session` and `mallory`. Add tests proving:

- forged session/user fields fail before `AgentSessionService`, `AuthService`, `SkillService`, routing, prompt, provider, or snapshot factory interaction;
- a matching shared claim creates/loads `AgentSession` with accepted `group-1`, `feishu`, and `ou-1`;
- canonical mode uses `roleCodeAtAcceptance` even when `AuthService` would return a different current role;
- canonical `ChatContext` exposes accepted channel, user, original message, request ID, and response mode-derived routing output;
- legacy rollback still resolves current role and calls `ContextAssembler` exactly as before.

The first failure assertion must use code `ACCEPTED_REQUEST_MISMATCH`.

- [ ] **Step 2: Add failing snapshot reuse and provider tests**

In `ChatContextFactoryCanonicalSnapshotTest` and `ChatContextFactoryCanonicalLifecycleProjectionTest`, add tests proving:

- a `CONTEXT_READY` run returns its stored snapshot without calling `ContextSnapshotFactory.create`, `RunStateContextSnapshotRequestFactory.create`, or `SoulPromptService.buildSystemPrompt`;
- the frozen `effectiveMessage` and `systemPrompt` populate `ChatContext`;
- first creation exposes the snapshot returned by `CanonicalContextSnapshotResolver`, even when it is not the candidate instance;
- a reused snapshot whose `providerId` differs from the active provider fails with `CANONICAL_PROVIDER_MISMATCH`;
- a reused snapshot whose `model` differs from the active model fails with the same code;
- absent or blank stored provider fields do not claim a mismatch.

Run:

```bash
mvn -q -Dtest=ChatContextFactoryCanonicalOwnershipTest,ChatContextFactoryCanonicalSnapshotTest,ChatContextFactoryCanonicalLifecycleProjectionTest test
```

Expected: the new tests fail because `ChatContextFactory` still uses request identity, always creates a candidate, and returns the local snapshot.

- [ ] **Step 3: Register the two boundary beans**

Add these beans inside the existing conditional `ContextSnapshotConfig`:

```java
@Bean
public AcceptedRunContextResolver acceptedRunContextResolver(
        RunStateRepository runStateRepository
) {
    return new AcceptedRunContextResolver(runStateRepository);
}

@Bean
public CanonicalContextSnapshotResolver canonicalContextSnapshotResolver(
        CanonicalContextReadyProjector projector,
        RunStateRepository runStateRepository
) {
    return new CanonicalContextSnapshotResolver(projector, runStateRepository);
}
```

The existing feature flag therefore removes the canonical beans together and leaves rollback construction valid.

- [ ] **Step 4: Replace direct repository/projector providers in the factory**

In the primary `ChatContextFactory` constructor:

- add `ObjectProvider<AcceptedRunContextResolver>`;
- add `ObjectProvider<CanonicalContextSnapshotResolver>`;
- remove `ObjectProvider<RunStateRepository>`;
- remove `ObjectProvider<CanonicalContextReadyProjector>`;
- retain providers for `ContextSnapshotFactory`, `LegacyContextViewAdapter`, and `RunStateContextSnapshotRequestFactory`;
- update the convenience constructor with empty providers for the two new dependencies.

Update every direct constructor fixture in the four listed test classes in the same edit so the project compiles after this step.

- [ ] **Step 5: Split legacy and canonical orchestration at the top of `build`**

Keep the public signature unchanged. After validating `acceptedRunId`, branch immediately:

```java
return contextSnapshotFactoryEnabled
        ? buildCanonical(request, persistSession, acceptedRunId)
        : buildLegacy(request, persistSession, acceptedRunId);
```

Move the current flag-disabled behavior into `buildLegacy` without semantic changes. `buildLegacy` must continue to:

- derive channel/user/session/message/role from `ChatRequest`;
- call `ContextAssembler`;
- leave `contextSnapshot` null;
- use the accepted run ID only as request correlation ID.

- [ ] **Step 6: Resolve accepted identity before any canonical dependency**

At the beginning of `buildCanonical`:

```java
AcceptedRunContextResolver acceptedResolver = requireBean(
        acceptedRunContextResolverProvider,
        "AcceptedRunContextResolver"
);
AcceptedRunContext accepted = acceptedResolver.resolve(acceptedRunId, request);

AgentSession session = persistSession
        ? agentSessionService.getOrCreate(
                accepted.sessionKey(), accepted.channel(), accepted.userId()
        )
        : buildEphemeralSession(
                accepted.sessionKey(), accepted.channel(), accepted.userId()
        );
```

Every canonical downstream request must use `accepted.sessionKey()`, `accepted.channel()`, `accepted.userId()`, `accepted.roleCode()`, `accepted.originalMessage()`, and `accepted.responseMode()`. Do not call `authService.resolveRoleByUserId` in `buildCanonical`.

- [ ] **Step 7: Reuse frozen snapshot inputs before candidate-only work**

Read the existing snapshot once:

```java
ContextSnapshot existing = accepted.runState().contextSnapshot();
String routingQuestion = existing == null
        ? resolveRoutingQuestion(
                accepted.sessionKey(),
                accepted.channel(),
                accepted.userId(),
                accepted.runId(),
                accepted.originalMessage(),
                accepted.responseMode()
        )
        : existing.effectiveMessage();
```

For both first execution and resume, recompute the legacy `AgentDecision` and routing wrapper from the frozen/accepted effective message because the current canonical decision adapter cannot restore `requiresConfirmation`. Always use accepted identity.

Only when `existing == null`:

- match visible skills;
- call `SoulPromptService.buildSystemPrompt`;
- prepare the `ContextSnapshotRequest` supplier;
- call `ContextSnapshotFactory.create` inside that supplier.

When `existing != null`, use `existing.systemPrompt()` and do not invoke skill matching or prompt construction. The decision may resolve allowed tool packs for legacy execution compatibility, but snapshot capability metadata stays frozen.

- [ ] **Step 8: Resolve and adapt the canonical snapshot**

Build a lazy supplier and pass it to the new resolver:

```java
Supplier<ContextSnapshot> candidate = () -> snapshotFactory.create(
        requestFactory.create(
                accepted.runState(),
                routingDecision.effectiveQuestion(),
                systemPrompt,
                decision.selectedCapabilities(),
                providerSnapshot(activeClient)
        )
);
ContextSnapshot snapshot = snapshotResolver.resolve(accepted, candidate);
requireMatchingProvider(snapshot, activeClient);
LegacyContextView view = viewAdapter.adapt(snapshot);
```

The supplier must not be evaluated by the factory. The resolver controls evaluation. Build the resulting `ChatContext` with:

- accepted identity for session, channel, user, role, original message, and request ID;
- snapshot `effectiveMessage` and `systemPrompt`;
- the current routing wrapper/decision computed from the frozen effective message;
- the canonical snapshot returned by the resolver;
- assembled and injection values from `LegacyContextViewAdapter`.

- [ ] **Step 9: Implement fail-closed provider comparison**

Compare only non-blank stored values:

```java
private static void requireMatchingProvider(
        ContextSnapshot snapshot,
        AiProviderService.ActiveChatClient activeClient
) {
    Map<String, String> frozen = snapshot.providerSnapshot();
    requireProviderField("providerId", frozen.get("providerId"),
            activeClient == null ? null : activeClient.providerId());
    requireProviderField("model", frozen.get("model"),
            activeClient == null ? null : activeClient.model());
}
```

`requireProviderField` trims both values. If a stored field is non-blank and differs from the active value, throw code `CANONICAL_PROVIDER_MISMATCH` with message `canonical provider mismatch: <field>`. Do not include provider IDs, model names, base URLs, or credentials in the message.

- [ ] **Step 10: Run focused factory tests**

Run:

```bash
mvn -q -Dtest=ChatContextFactoryTest,ChatContextFactoryCanonicalOwnershipTest,ChatContextFactoryCanonicalSnapshotTest,ChatContextFactoryCanonicalLifecycleProjectionTest test
```

Expected: all factory tests pass, including the unchanged rollback characterization.

- [ ] **Step 11: Commit the factory convergence**

Run:

```bash
git add src/main/java/com/springclaw/config/ContextSnapshotConfig.java \
  src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java \
  src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryTest.java \
  src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalOwnershipTest.java \
  src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalSnapshotTest.java \
  src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalLifecycleProjectionTest.java
git diff --cached --check
git commit -m "refactor: build chat context from accepted run"
```

Expected: the commit contains bean wiring, factory orchestration, and focused behavior tests only.

## Task 4: Characterize transport parity through the shared boundary

**Files:**

- Modify: `src/test/java/com/springclaw/service/chat/async/ChatMessageConsumerTest.java`
- Modify: `src/test/java/com/springclaw/service/webhook/WebhookRouterServiceTest.java`
- Modify: `src/test/java/com/springclaw/service/task/TaskExecutionServiceTest.java`

- [ ] **Step 1: Add transport-to-command assertions**

Extend existing tests without adding comparison logic to production transports:

- MQ: capture the `AcceptedChatCommand` and assert run ID, session key, channel, user, message, and response mode equal the canonical `RunState`; keep its existing early equality guard.
- Webhook personal and trusted shared cases: capture both `RunAcceptance` and `AcceptedChatCommand`, then assert the command request carries the same immutable fields that were accepted and that the access claim type remains correct.
- Scheduled task chat case: capture `RunAcceptance` and the command/execution call, then assert `SCHEDULED_TASK` origin plus matching session, user, channel, message, response mode, and run ID.

These tests characterize that every ingress reaches `ChatContextFactory` with a command the shared resolver can validate; they must not duplicate `AcceptedRunContextResolver` field-by-field production checks.

- [ ] **Step 2: Run the transport tests**

Run:

```bash
mvn -q -Dtest=ChatMessageConsumerTest,WebhookRouterServiceTest,TaskExecutionServiceTest test
```

Expected: all transport characterization tests pass without production transport changes.

- [ ] **Step 3: Commit transport characterization**

Run:

```bash
git add src/test/java/com/springclaw/service/chat/async/ChatMessageConsumerTest.java \
  src/test/java/com/springclaw/service/webhook/WebhookRouterServiceTest.java \
  src/test/java/com/springclaw/service/task/TaskExecutionServiceTest.java
git diff --cached --check
git commit -m "test: characterize canonical context transport parity"
```

Expected: test-only commit; if a production transport change appears necessary, stop and revise the approved design before broadening scope.

## Task 5: Verify the complete convergence and document the result

**Files:**

- Modify: `docs/superpowers/specs/2026-07-11-runtime-context-identity-convergence-design.md`
- Verify only: all files changed in Tasks 1–4

- [ ] **Step 1: Run the complete focused suite**

Run:

```bash
mvn -q -Dtest=AcceptedRunContextResolverTest,CanonicalContextSnapshotResolverTest,CanonicalContextReadyProjectorTest,RunStateContextSnapshotRequestFactoryTest,ChatContextFactoryTest,ChatContextFactoryCanonicalOwnershipTest,ChatContextFactoryCanonicalSnapshotTest,ChatContextFactoryCanonicalLifecycleProjectionTest,ChatMessageConsumerTest,WebhookRouterServiceTest,TaskExecutionServiceTest test
```

Expected: exit code 0 with no test failure or error.

- [ ] **Step 2: Run the entire backend suite**

Run:

```bash
mvn -q test
```

Expected: exit code 0. Record the final Surefire test count from `target/surefire-reports` in the handoff.

- [ ] **Step 3: Run repository hygiene checks**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; status contains only the intended documentation status update before the final documentation commit.

- [ ] **Step 4: Update the design status with verification evidence**

Change the design document status to `Implemented and verified` and append a short implementation evidence section listing:

- the accepted-run resolver;
- the canonical snapshot resolver;
- the `ChatContextFactory` boundary refactor;
- the focused-suite command and result;
- the full-suite command and test count.

- [ ] **Step 5: Commit verification documentation**

Run:

```bash
git add docs/superpowers/specs/2026-07-11-runtime-context-identity-convergence-design.md
git diff --cached --check
git commit -m "docs: record runtime context convergence verification"
```

- [ ] **Step 6: Perform final branch review**

Run:

```bash
git status --short
git log --oneline --decorate -7
git diff --stat 7a77ce5...HEAD
```

Expected: clean worktree, five implementation commits after the design and plan commits, and no files outside the approved scope.

## Acceptance Checklist

- [ ] Canonical mode loads and validates `RunState` before session, role, tools, routing, prompt, provider, or memory work.
- [ ] Every immutable command mismatch produces `ACCEPTED_REQUEST_MISMATCH` without exposing field values.
- [ ] Terminal rebuilds produce `RUN_ALREADY_TERMINAL`.
- [ ] Canonical role and identity come from acceptance, not current request/auth state.
- [ ] Only `CREATED` without a snapshot may evaluate a snapshot candidate.
- [ ] Retry/resume returns the stored snapshot and skips snapshot/prompt reconstruction.
- [ ] First creation and projection races return the committed snapshot, never a local loser.
- [ ] Provider/model drift produces `CANONICAL_PROVIDER_MISMATCH`.
- [ ] Invalid lifecycle combinations produce `CANONICAL_SNAPSHOT_INVARIANT`.
- [ ] Explicit rollback mode still uses the legacy assembler.
- [ ] MQ, webhook, and scheduled task tests preserve acceptance-to-command parity.
- [ ] Focused and complete Maven suites pass.
