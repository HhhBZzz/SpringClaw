# Unified Agent Runtime Collaboration Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` when an approved implementation plan exists. This document is the cross-agent coordination ledger; it does not authorize production-code changes before the new architecture spec is approved.

**Goal:** Replace SpringClaw's duplicated runtime control paths with one canonical Run lifecycle while preserving P0 tool confirmation, workspace safety, context, trace, persistence, and transport behavior.

**Architecture:** First characterize the current system and define ownership contracts. Then introduce a canonical runtime model behind compatibility adapters, migrate one responsibility at a time, and delete the old owner immediately after each migration. Engine count is an outcome, not a design constraint.

**Tech Stack:** Java 17, Spring Boot 3.5.7, Spring AI 1.1.2, JUnit 5, Mockito, MySQL, Redis, RabbitMQ, Vue 3.

---

## 1. Document Authority

This file is the authoritative task ledger for the Unified Agent Runtime work.

- Codex owns and updates this ledger.
- Claude reports commit SHAs and findings; Codex records them here.
- Chat messages are explanatory context, not task state.
- A task is complete only when its checkbox is checked and its evidence is recorded.
- The deprecated engine-modernization spec must not be used as an implementation source.

### Current repository state

| Item | Value |
|---|---|
| Coordination base | `36ca396` |
| Codex branch | `codex/unified-agent-runtime` |
| Codex worktree | `/Users/hanbingzheng/springclaw/.worktrees/unified-agent-runtime` |
| Deprecated Claude branch | `worktree-claude-engine-modernization` |
| Deprecated spec marker | `f6a775f` |
| Production implementation authorized | Phase 2A completed; Phase 2B requires a rewritten approved plan |

## 2. Non-Negotiable Invariants

1. Every request has one canonical `runId`/`requestId` and one Run lifecycle.
2. Actual `write` and `dangerous` tool calls must pass through `ToolRuntimeAspect`.
3. Unapproved write operations must not produce side effects.
4. `WorkspaceGitGuard` and proposal argument/path validation remain mandatory.
5. Context has one canonical snapshot; Advisors only project that snapshot into model requests.
6. `finishReason`, final text, or absence of tool calls cannot independently prove business completion.
7. REST, SSE, RabbitMQ, persistence, trace, and audit must project the same Run.
8. No phase may leave two active owners for routing, final-answer composition, persistence, or stream termination.
9. Existing engines are not deleted until characterization tests and compatibility acceptance pass.
10. Codex and Claude must not modify the same production file concurrently.

## 3. File Ownership

### Codex-owned paths

Claude must not modify these paths unless this ledger explicitly transfers ownership:

```text
src/main/java/com/springclaw/service/agent/**
src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java
src/main/java/com/springclaw/service/chat/impl/ChatContext.java
src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java
src/main/java/com/springclaw/service/chat/impl/ChatExecutionResult.java
src/main/java/com/springclaw/service/chat/impl/ChatRoutingPolicyService.java
src/main/java/com/springclaw/service/chat/impl/ConversationAdvisorSupport.java
src/main/java/com/springclaw/service/context/**
src/main/java/com/springclaw/tool/runtime/**
src/main/java/com/springclaw/service/proposal/**
src/main/java/com/springclaw/service/workspace/**
docs/superpowers/specs/2026-06-18-unified-agent-runtime-design.md
docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
```

### Claude-owned paths for Phase 1

```text
docs/architecture/runtime-current-state-audit.md
src/test/java/com/springclaw/architecture/**
```

Claude must not modify production Java in Phase 1.

### Early Phase 2B ownership transfer

Claude may begin the following isolated task before the lifecycle core is complete:

```text
src/main/java/com/springclaw/service/agent/EngineSelector.java
src/test/java/com/springclaw/architecture/RuntimeRouteCharacterizationTest.java
all existing EngineSelector-focused test files discovered with:
  rg -l "class EngineSelectorTest|new EngineSelector" src/test/java
```

Task:

```text
Freeze legacy engine ordering to (priority, legacyRank):
  basic-stream=10
  agent-runtime=20
  autonomous-loop=30
  opar-loop=40
  model-led-stream=50
  simplified=60

Only change the EngineSelector constructor ordering and directly affected tests.
A registered engine name without a rank must fail initialization.
Do not change engine priority(), supports(), routing conditions, ChatServiceImpl,
runtime lifecycle core, DTOs, configuration, or production files outside
EngineSelector.java.

Required verification:
  mvn -q -Dtest=EngineSelectorTest,RuntimeRouteCharacterizationTest test
  mvn -q -Dtest=EngineSelectorTest,ChatContextFactoryTest,PromptInjectionTest,AgentRuntimeEngineTest test
  git diff --check

Return commit SHA, changed files, test counts, and any environmental warnings.
Do not merge or edit Codex commits.
```

This transfer removes `EngineSelector.java` from Codex ownership for the duration
of the task. All other `service/agent/**` files remain Codex-owned.

## 4. Phase 0 — Coordination Setup

- [x] **C0.1 — Deprecate the incorrect engine-modernization spec**
  - Owner: Claude
  - Evidence: commit `f6a775f`
  - Result: old `6 → 2` implementation direction is explicitly blocked.

- [x] **C0.2 — Create isolated Codex worktree**
  - Owner: Codex
  - Evidence: branch `codex/unified-agent-runtime`
  - Path: `/Users/hanbingzheng/springclaw/.worktrees/unified-agent-runtime`

- [x] **C0.3 — Verify focused baseline**
  - Owner: Codex
  - Command:

    ```bash
    mvn -q \
      -Dtest=EngineSelectorTest,ChatContextFactoryTest,PromptInjectionTest,AgentRuntimeEngineTest,ToolRuntimeAspectInterceptionIT,ToolInvocationProposalServiceConfirmTest \
      test
    ```

  - Result: exit code `0`.
  - Environment note: application-context tests log local MySQL authentication warnings (`Public Key Retrieval is not allowed`), but the focused command passes.

- [x] **C0.4 — Create Claude characterization worktree**
  - Owner: Claude
  - Base: `36ca396`
  - Branch: `claude/runtime-characterization`
  - Result:

    ```text
    clean worktree
    no production Java changes
    ```

## 5. Phase 1 — Parallel Architecture Discovery

Phase 1 work may run in parallel because Codex writes the architecture spec while Claude writes audit documentation and characterization tests.

### Codex workstream A — Canonical Runtime Design

- [x] **A1 — Map the four complete request lifecycles**
  - Owner: Codex
  - Cover:
    - synchronous chat
    - SSE streaming chat
    - RabbitMQ asynchronous chat
    - proposal confirmation and resumed execution
  - Required output: lifecycle diagrams and exact owner classes in the new spec.

- [x] **A2 — Build the responsibility source-of-truth matrix**
  - Owner: Codex
  - Responsibilities:
    - routing
    - risk and confirmation
    - context
    - capability/tool execution
    - completion
    - final answer
    - persistence
    - trace/audit
    - stream termination
  - Acceptance: every responsibility lists current owners, target owner, and deletion target.

- [x] **A3 — Define canonical domain contracts**
  - Owner: Codex
  - Required contracts:

    ```text
    RunState
    RunStatus
    ContextSnapshot
    ExecutionDecision
    RuntimeStrategy
    ToolInvocation
    CompletionDecision
    RunResult
    RunEvent
    ```

  - Acceptance: each contract has responsibility, fields, producer, consumers, and forbidden responsibilities.

- [x] **A4 — Define the Run state machine**
  - Owner: Codex
  - Required states:

    ```text
    CREATED
    CONTEXT_READY
    DECIDED
    WAITING_CONFIRMATION
    RUNNING
    VERIFYING
    COMPLETED
    DEGRADED
    FAILED
    ```

  - Acceptance: valid transitions, terminal states, retry semantics, confirmation resume semantics, and failure ownership are explicit.

- [x] **A5 — Define incremental migration and rollback**
  - Owner: Codex
  - Acceptance:
    - initial runtime delegates through compatibility adapters;
    - one responsibility migrates per phase;
    - old owner is disabled in the same phase;
    - rollback unit and compatibility acceptance are defined for every phase.

- [x] **A6 — Write and self-review the architecture spec**
  - Owner: Codex
  - Create:

    ```text
    docs/superpowers/specs/2026-06-18-unified-agent-runtime-design.md
    ```

  - Required checks:

    ```bash
    rg -n "T[B]D|T[O]DO|F[I]XME|待[定]|以后处[理]" \
      docs/superpowers/specs/2026-06-18-unified-agent-runtime-design.md
    git diff --check
    ```

  - Expected: no placeholders and no whitespace errors.

### Codex Phase 1 evidence

```text
Task: A1-A6
Owner: Codex
Branch: codex/unified-agent-runtime
Commit: 8ae585c
Files: docs/superpowers/specs/2026-06-18-unified-agent-runtime-design.md
Tests: mvn -q -Dtest=EngineSelectorTest,ChatContextFactoryTest,PromptInjectionTest,AgentRuntimeEngineTest,ToolRuntimeAspectInterceptionIT,ToolInvocationProposalServiceConfirmTest test
Findings:
- The focused suite passed: 27 tests, 0 failures, 0 errors, 0 skipped.
- MySQL emitted the known `Public Key Retrieval is not allowed` warning; Runtime Console and tool-proposal schema initialization were skipped, but the Maven command exited 0.
- RabbitMQ async creates outer requestId A, then ChatContextFactory creates runtime requestId B; result polling and run trace therefore refer to different identifiers.
- Runtime confirmation has two authorities: in-memory AgentActionProposal and persisted ToolInvocationProposal. Generic AgentActionProposal confirmation does not resume execution.
- Sync and SSE have different trace, final-status, failover, persistence, lock-release, and pending-confirmation semantics.
- Streamable engines, ChatServiceImpl, SseEventBridge, AgentRunTraceService, and MessageEventToolAuditService duplicate persistence, termination, and trace ownership.
- WorkspaceGitGuard does not isolate the existing Git index or concurrent repository mutations, so unrelated staged files or parallel confirmed proposals can cross commit boundaries.
- The target design assigns one owner for identity, lifecycle, context, decision, tool gateway, completion, final answer, persistence, trace/audit, and transport termination.
Next dependency: Claude B1-B7 evidence, cross-review against characterization tests, then user approval of the architecture spec.
```

### Claude workstream B — Characterization and Evidence

- [x] **B1 — Audit current responsibility ownership**
  - Owner: Claude
  - Create:

    ```text
    docs/architecture/runtime-current-state-audit.md
    ```

  - Required contents:
    - all six `AgentEngine` implementations and their `priority()/supports()` conditions;
    - route examples for general/read/write/dangerous/deep/fast/tool;
    - every final-answer composition site;
    - every persistence, SSE completion, and lock-release site;
    - all tool entry paths;
    - all context construction and injection paths;
    - all completion signals;
    - actual configuration keys and environment variable names.

- [x] **B2 — Characterize routing**
  - Owner: Claude
  - Create:

    ```text
    src/test/java/com/springclaw/architecture/RuntimeRouteCharacterizationTest.java
    ```

  - Tests must record current behavior without changing production code.

- [x] **B3 — Characterize context propagation**
  - Owner: Claude
  - Create:

    ```text
    src/test/java/com/springclaw/architecture/ContextPropagationCharacterizationTest.java
    ```

  - Acceptance: asserts which paths receive `ContextInjection`, ChatMemory Advisor, semantic memory, and Memory Bank content.

- [x] **B4 — Characterize tool safety**
  - Owner: Claude
  - Create:

    ```text
    src/test/java/com/springclaw/architecture/ToolSafetyPathCharacterizationTest.java
    ```

  - Acceptance: asserts write calls cannot bypass `ToolRuntimeAspect` and proposal confirmation.

- [x] **B5 — Characterize final-answer ownership**
  - Owner: Claude
  - Create:

    ```text
    src/test/java/com/springclaw/architecture/FinalAnswerOwnershipCharacterizationTest.java
    ```

  - Acceptance: records every path that composes, repairs, narrates, or replaces the final answer.

- [x] **B6 — Characterize transport parity**
  - Owner: Claude
  - Create:

    ```text
    src/test/java/com/springclaw/architecture/TransportParityCharacterizationTest.java
    ```

  - Acceptance: records sync/SSE/async differences in result, persistence, trace, and completion behavior.

- [x] **B7 — Commit Phase 1 evidence**
  - Owner: Claude
  - Required commits:

    ```text
    docs: audit current runtime responsibility ownership
    test: characterize runtime routing and context propagation
    test: characterize safety completion and transport parity
    ```

  - Claude reports SHAs; Codex records them in this ledger.

### Claude Phase 1 evidence

```text
Task: B1-B7
Owner: Claude
Branch: claude/runtime-characterization
Commits:
- dab2dbc docs: audit current runtime responsibility ownership
- 9b87c65 test: characterize runtime routing and context propagation
- 8f6135f test: characterize safety completion and transport parity
Files:
- docs/architecture/runtime-current-state-audit.md
- src/test/java/com/springclaw/architecture/ContextPropagationCharacterizationTest.java
- src/test/java/com/springclaw/architecture/FinalAnswerOwnershipCharacterizationTest.java
- src/test/java/com/springclaw/architecture/RuntimeRouteCharacterizationTest.java
- src/test/java/com/springclaw/architecture/ToolSafetyPathCharacterizationTest.java
- src/test/java/com/springclaw/architecture/TransportParityCharacterizationTest.java
Tests:
- mvn -q -Dtest=RuntimeRouteCharacterizationTest,ContextPropagationCharacterizationTest,ToolSafetyPathCharacterizationTest,FinalAnswerOwnershipCharacterizationTest,TransportParityCharacterizationTest test
- mvn -q -Dtest=EngineSelectorTest,ChatContextFactoryTest,PromptInjectionTest,AgentRuntimeEngineTest,ToolRuntimeAspectInterceptionIT,ToolInvocationProposalServiceConfirmTest test
Findings:
- The characterization suite passed: 41 JUnit testcase elements, 0 failures, 0 errors, 0 skipped.
- Surefire reports RuntimeRouteCharacterizationTest as an outer suite with tests="0", but its XML contains 13 executed nested testcases; the five classes contain 13 + 8 + 5 + 7 + 8 = 41 testcases.
- The focused baseline passed: 27 tests, 0 failures, 0 errors, 0 skipped.
- MySQL emitted the known Public Key Retrieval is not allowed warning, but the Maven command exited 0.
- The verified diff contains 6 files: 1 audit document and 5 test classes, with 0 production Java changes.
- The architecture spec cross-review approved the design with one non-blocking clarification: freeze equal-priority legacy engine ordering before Phase 3B.
- The spec now records explicit version-controlled legacyRank compatibility ordering and forbids inventing alphabetical tie-breaking.
Next dependency: user approval of the architecture spec before any implementation plan or production-code change.
```

## 6. Phase 1 Review Gate

No implementation plan may be written until all checks pass:

- [x] Codex spec includes the four lifecycle maps.
- [x] Codex spec defines one owner per runtime responsibility.
- [x] Claude audit and characterization tests are available for review.
- [x] P0 invariants map to explicit target-runtime enforcement points.
- [x] The design does not assume a fixed engine count.
- [x] The user approves the new spec.
  - Evidence: user approval in the collaboration thread on `2026-06-19` followed by the instruction to begin implementation.

## 7. Future Implementation Plans

After the Phase 1 review gate, Codex will create separate plans rather than one oversized implementation plan:

1. `unified-runtime-domain-contracts`
2. `unified-runtime-legacy-bridge`
3. `unified-runtime-context-and-decision-ownership`
4. `unified-runtime-tool-gateway-and-completion`
5. `unified-runtime-output-projections`
6. `unified-runtime-legacy-removal`

Each plan must deliver working, testable software and identify its own rollback boundary.

## 8. Planned Phase 2 Ownership

| Work | Owner | Rule |
|---|---|---|
| Runtime domain contracts | Codex | Claude does not modify runtime core types |
| Coordinator and legacy bridge | Codex | Existing behavior preserved first |
| SSE/REST/async projectors | Claude | Created behind frozen Codex interfaces |
| Context and decision ownership migration | Codex | Old owner disabled in same change |
| ToolGateway and CompletionVerifier | Codex | P0 safety review required |
| Documentation and mechanical cleanup | Claude | Only after Codex publishes deletion list |
| Final integration and safety verification | Codex | Full cross-transport acceptance |

## 9. Update Protocol

After every completed task, record:

```text
Task:
Owner:
Branch:
Commit:
Files:
Tests:
Findings:
Next dependency:
```

Rules:

- Do not mark work complete without a commit SHA or explicit read-only artifact.
- Do not cherry-pick another agent's incomplete branch.
- Rebase only at phase boundaries.
- If file ownership must change, update this ledger before editing.
- If a new architectural fact contradicts the spec, stop implementation and return to the spec review gate.

## 10. Claude Phase 1 Instruction

Use the following instruction without expanding scope:

```text
The previous engine-modernization spec is deprecated. Do not implement engine reduction.

Create an isolated worktree from commit 36ca396:
- branch: claude/runtime-characterization

You may modify only:
- docs/architecture/runtime-current-state-audit.md
- src/test/java/com/springclaw/architecture/**

Do not modify production Java.

Audit the complete sync, SSE, RabbitMQ async, and proposal-confirm-resume request lifecycles. Identify every owner of routing, risk/confirmation, context, tool execution, completion, final answer, persistence, trace, and stream termination.

Add characterization tests for routing, context propagation, tool safety, final-answer ownership, and transport parity. Tests must record existing behavior; they must not repair production code.

Use three commits:
1. docs: audit current runtime responsibility ownership
2. test: characterize runtime routing and context propagation
3. test: characterize safety completion and transport parity

Return commit SHAs, files changed, tests run, environmental blockers, and all duplicated sources of truth found.
```

---

## Update: unified-runtime-domain-contracts (Phase 2 first plan)

```text
Task: unified-runtime-domain-contracts
Owner: Codex
Assistance: Claude temporarily executed the first contract implementation while Codex quota was unavailable; Codex retained ownership, reviewed the handoff, and completed the corrective implementation and acceptance.
Branch: codex/unified-agent-runtime
Commits:
  b86035a merge: integrate runtime characterization baseline
  73e3987 test: fix characterization quality review findings
  44ca5e4 feat: define canonical run lifecycle states
  817fa8e feat: add runtime context and decision contracts
  c96b9f9 feat: define runtime safety and result contracts
  f2b97be feat: add canonical runtime event contract
  6fda7b3 feat: add canonical run state aggregate
  75ca615 feat: define canonical runtime strategy boundary
  3d3332d test: verify unified runtime contract compatibility
  e297bce docs: record runtime domain contract evidence
  620b9a6 fix: enforce unified runtime contract invariants
  774d77d fix: reject results on nonterminal run states
  4318e1a fix: enforce runtime transition and invocation timelines
  cbb66cf fix: preserve canonical run history across transitions
  a1418c8 fix: allow canonical invocation lifecycle progression
  7c5d2f0 fix: enforce runtime decision stage ordering
  788b355 fix: isolate runtime invocation attempts
  87694ee test: strengthen runtime routing and context characterization
  a444806 test: correct routing reachability and context fixtures
  c5a8012 test: strengthen tool safety and final answer characterization
  5c62ed4 test: complete final answer ownership inventory
  2a900f1 test: strengthen async transport parity characterization
  16f736f test: remove field-only async transport assertion
  c6fa025 docs: correct unified runtime current-state audit
  01f7568 docs: align runtime audit with production evidence
  d8d7c72 docs: finalize runtime audit evidence labels
  b5379c7 docs: distinguish local cache expiry evidence
Files:
  - isolated src/main/java/com/springclaw/runtime/contract/**
  - src/test/java/com/springclaw/runtime/contract/**
  - strengthened src/test/java/com/springclaw/architecture/**
  - corrected docs/architecture/runtime-current-state-audit.md
Tests:
  - 67 domain contract tests pass
  - 47 production-backed characterization tests pass:
    - RuntimeRouteCharacterizationTest: 20
    - ContextPropagationCharacterizationTest: 5
    - ToolSafetyPathCharacterizationTest: 7
    - FinalAnswerOwnershipCharacterizationTest: 5
    - TransportParityCharacterizationTest: 10
  - 27 focused baseline tests pass
  - focused acceptance total: 141 tests, 0 failures, 0 errors, 0 skips
Findings:
  - No existing production routing, safety, persistence, controller, engine, or transport file changed.
  - The unapproved RunState Builder/toBuilder API from the temporary handoff was removed.
  - Contracts now enforce finite quality/confidence values, cross-run ownership, terminal evidence, stage ordering, immutable acceptance/context history, retry isolation, invocation identity/idempotency uniqueness, legal invocation lifecycle progression, and EventStore-owned event identity/sequence.
  - Characterization tests now execute production routing, context, ToolRuntimeAspect, final-answer, async store, and WebSocket/STOMP behavior instead of validating local constants.
  - Current production behavior records ModelLedStreamEngine as shadowed for reachable non-null decisions, category scoring as one point per category, any non-empty engine reflect as bypassing MetaGuard, and async results as local Caffeine plus optional Redis plus WebSocket/STOMP projection.
  - Known environment warning remains: MySQL authentication logs `Public Key Retrieval is not allowed`; the focused baseline exits 0.
Next dependency: unified-runtime-legacy-bridge plan
```

## Update: unified-runtime-canonical-identity (Phase 2A)

```text
Task: unified-runtime-canonical-identity
Owner: Codex
Branch: codex/unified-agent-runtime
Plan commits:
  2dd2b4f docs: split canonical identity from legacy bridge
  48c8861 docs: correct canonical identity migration plan
Implementation commits:
  c624e75 feat: add canonical run identity primitive
  9660548 refactor: migrate canonical chat identity atomically
  ac0c3b7 fix: propagate canonical tool ownership
Files:
  - canonical identity factory and AcceptedChatCommand
  - REST, Rabbit, webhook, scheduled-task, service, and context identity propagation
  - engine and capability-executor tool ownership propagation
  - direct identity and ownership tests plus affected characterization tests
Tests:
  - 67 domain contract tests pass
  - 47 production-backed characterization tests pass
  - 27 focused baseline tests pass
  - 58 direct canonical identity and tool ownership tests pass
  - full suite: 610 tests, 0 failures, 8 errors, 0 skips
  - all 8 full-suite errors are ToolInvocationProposalRepositoryTest transaction setup
    errors caused by the environment's MySQL authentication setting:
    "Public Key Retrieval is not allowed"
Findings:
  - Sync REST, SSE REST, async REST/Rabbit, webhook, and scheduled-task paths
    preserve one accepted normalized identity as requestId == runId.
  - Rabbit redelivery reuses message.requestId but may execute again; Phase 2A
    does not claim exactly-once processing or duplicate suppression.
  - ChatContextFactory no longer creates UUIDs.
  - All 14 production ToolExecutionContext construction sites use the canonical
    request identity for both requestId and runId.
  - Phase 2A did not modify transport DTOs, ToolRuntimeAspect, proposal execution,
    workspace guards, EngineSelector, runtime contracts, or configuration.
  - The temporary handoff and .gitignore commits were fully reverted; their net
    tree effect is zero.
Rollback:
  - Revert ac0c3b7.
  - Revert 9660548 atomically; never partially roll back controller, consumer,
    webhook, scheduled task, service, or context-factory identity wiring.
  - Revert c624e75.
Next dependency:
  - Replace the superseded unified-runtime-legacy-bridge plan with a bounded
    Phase 2B canonical lifecycle bridge plan before further production changes.
```

Production implementation beyond Phase 2A remains unauthorized until the rewritten Phase 2B lifecycle bridge plan is reviewed and accepted.

## Update: Phase 2B core start and early Claude task

```text
Owner: Codex
Design commit: 3c8d245
Plan commit: e13f182
Atomic lifecycle store commit: 75bc07e
Codex in progress:
  - RunCoordinator
  - LegacyRuntimeBridge frozen API
Claude authorized now:
  - EngineSelector legacyRank freeze only
Dependency:
  - remaining ingress, projection, trace, and proposal integration waits for the
    Codex lifecycle core and handoff commit.
```

## Update: Phase 2B lifecycle core ready for integration

```text
Owner: Codex
Branch: codex/unified-agent-runtime
Commits:
  3c8d245 docs: correct phase 2b lifecycle bridge design
  e13f182 docs: plan phase 2b core and Claude handoff
  75bc07e feat: add atomic canonical lifecycle store
  b8aac16 feat: coordinate canonical run lifecycle
  86340a1 feat: freeze legacy lifecycle bridge boundary
  0cffc04 test: verify concurrent lifecycle commit fencing
  802b094 fix: close lifecycle idempotency and failure gaps
Frozen core APIs:
  - RunStateRepository: query-only state view
  - RunEventStore: query-only ordered event view
  - RunLifecycleStore: atomic create/commit write port
  - RunCoordinator: only canonical lifecycle writer
  - LegacyRuntimeBridge: transport-neutral legacy observation boundary
Verified invariants:
  - state revision and lifecycle event commit under one per-run critical section
  - stale concurrent writers add neither state nor event
  - duplicate acceptance remains idempotent after the run advances when immutable
    acceptance fields match
  - conflicting acceptance fails
  - terminal state is immutable
  - VERIFYING -> FAILED requires and preserves CompletionDecision.FAIL
  - event identity and sequence are store-owned
  - bridge is not RuntimeStrategy and has no transport, engine, persistence, lock,
    or tool-execution dependency
Tests:
  - 131 contract, lifecycle-core, characterization, identity, and ownership
    testcase elements pass
  - 0 failures, 0 errors, 0 skips
Durability limit:
  - current lifecycle store is process-local and does not claim restart durability
    or exactly-once execution
Rollback:
  - revert integration wiring first
  - revert 802b094, 0cffc04, 86340a1, b8aac16, and 75bc07e
  - retain Phase 2A identity propagation
Claude next:
  - complete early Task 4A engine ordering if not already complete
  - then execute Tasks 5-8 from
    docs/superpowers/plans/2026-06-21-unified-runtime-phase-2b-core-and-handoff.md
  - do not modify runtime/lifecycle/** or runtime/bridge/**
  - report any required core API change instead of editing the frozen core
```

Claude may now begin Phase 2B integration Tasks 5–8 after basing work on commit
`802b094` or a later ledger-only handoff commit.

## Update: Phase 2B Task 6 adapter core and wiring handoff

```text
Owner: Codex
Reviewed Claude work:
  - b7bb77f engine ordering: APPROVED
  - 4403717, 710c6a8, 3a66621, d203e08 ingress wiring: APPROVED
Fresh verification:
  - 174 testcase elements
  - 0 failures, 0 errors, 0 skips
  - known MySQL authentication warnings only
Codex commits:
  6a55600 feat: adapt legacy execution facts to runtime contracts
  8ae12ee feat: orchestrate legacy lifecycle observations
Frozen semantics:
  - adapters translate existing facts only; no context retrieval or rerouting
  - successful legacy returns map to DEGRADED / LEGACY_UNVERIFIED_RESULT
  - no synthetic COMPLETED status or quality=1
  - LegacyLifecycleObserver owns transition ordering for integration callers
Claude authorized next:
  - execute docs/superpowers/plans/
    2026-06-22-unified-runtime-phase-2b-task6-observation-wiring.md
  - do not modify runtime/** or other prohibited paths
```

## Update: Phase 2B Task 7 core event API and projection handoff

```text
Owner: Codex
Commit:
  1f51ba2 feat: append canonical tool lifecycle facts
Core additions:
  - RunLifecycleStore.append for event-only facts under revision fencing
  - RunCoordinator tool.started/tool.succeeded/tool.failed observations
  - typed confirmationRejected transition and event
  - LegacyLifecycleObserver projection-facing methods
Clarification:
  - ToolInvocationProposalService.createPending is the unique owner of persisted
    tool-proposal WAITING_CONFIRMATION
  - Task 6 PendingToolApproval rendering does not repeat that transition
Claude authorized after Task 6:
  - execute docs/superpowers/plans/
    2026-06-22-unified-runtime-phase-2b-task7-projections.md
```

## Update: Phase 2B Task 5 ingress wiring

```text
Task: Phase 2B Task 5 ingress wiring
Owner: Claude
Base: b7bb77f
Commits:
  4403717 feat: create canonical runs at chat ingress
  710c6a8 feat: require canonical run for async delivery
  3a66621 feat: create canonical runs at webhook ingress
  d203e08 feat: create canonical runs for scheduled tasks
Decisions:
  - Rabbit requires a matching process-local run and never reconstructs role.
  - Async acceptedAt equals message.createdAt.
  - deadlineAt is acceptedAt + 30 minutes.
  - webhook and scheduled roles come from AuthService.
  - acceptance failures stop legacy execution.
  - ChatMessageConsumerTest is a new claim/fail-closed suite.
Limitations:
  - no restart durability
  - no exactly-once execution
  - no duplicate-execution suppression
Environmental warnings: none
Tests:
  - 67 contract tests pass
  - 53 characterization/Phase 2A tests pass
  - 29 baseline tests pass (EngineSelectorTest, ChatContextFactoryTest,
    PromptInjectionTest, AgentRuntimeEngineTest, ToolRuntimeAspectInterceptionIT,
    ToolInvocationProposalServiceConfirmTest)
  - 29 Task 5 focused tests pass
Prohibited-file check: clean (runtime/lifecycle, runtime/bridge, runtime/contract,
  dto/chat, AsyncChatRequestMessage, AsyncChatResultPayload, ChatServiceImpl,
  tool/runtime, service/workspace, resources, .env.example untouched)
Whitespace check: clean
Next dependency:
  Phase 2B Task 6 legacy observation wiring
```

## Update: Phase 2B Task 6 legacy observation wiring

```text
Task: Phase 2B Task 6 observation wiring
Owner: Claude
Base: f0f15a9
Commit:
  e3c3538 feat: project legacy execution into canonical lifecycle
Decisions:
  - LegacyLifecycleObserver injected into ChatServiceImpl and the three
    streamable engines (Basic/ModelLed/Autonomous).
  - Blocking executeInternal: contextAndDecisionObserved after build,
    single engine select + executionStarted, resultReturned after
    persistence (even when persistResult=false), failed on exception.
  - SSE executeStream: contextAndDecisionObserved after build,
    executionStarted after the one EngineSelector.select,
    confirmationRequired when streamActionRequired creates an
    AgentActionProposal, failed on startup failure.
  - PendingToolApprovalException catch does NOT call confirmationRequired;
    Task 7 owns persisted tool-proposal suspension.
  - Five SSE terminal points each call resultReturned exactly once:
    streamImmediateAnswer, streamAgentRuntimeAnswer success,
    streamBlockingFallback success, streamReflectAnswer doOnComplete,
    streamReflectAnswer doOnError after fallback persistence.
    streamBlockingFallback own failure calls failed.
  - Engines call resultReturned after their successful persistence,
    including partial-answer fallback recovery (mutually exclusive with
    streamBlockingFallback via handlePartialAnswer return value).
  - observer is nullable with guards; production Spring-injected path is
    non-null. Existing behavior tests pass null observer and are
    unchanged. New focused tests use a real observer backed by the
    process-local store to prove DEGRADED/FAILED terminal projection.
Deviation from plan:
  - Plan 6.1 asked for "no-op-free real dependency" in compatibility
    constructors. Claude used null-guarded observer instead, because
    existing ChatServiceImpl tests do not pre-accept a canonical run and
    a real observer would throw "run not found" in those behavior tests.
    Lifecycle correctness is proven by the new focused projection test
    with a real observer; production path is unaffected. Flagged for
    Codex review.
Tests:
  - 67 contract tests pass
  - 53 characterization/Phase 2A tests pass
  - 29 baseline tests pass
  - 3 new ChatServiceImplLifecycleProjectionTest cases pass
  - all existing ChatServiceImpl/engine/transport tests green
Prohibited-file check (f0f15a9..HEAD): clean (runtime, tool/runtime,
  service/workspace, dto, resources, .env.example untouched)
Whitespace check: clean
Environmental warnings: none
Next dependency:
  Phase 2B Task 7 legacy status store projections
```

## Update: Phase 2B Task 7 legacy status store projections

```text
Task: Phase 2B Task 7 projection wiring
Owner: Claude
Base: 2c7ede7
Commits:
  7924456 feat: guard async result projection by canonical run status
  597e562 feat: project canonical run status into trace metadata
  7ccc530 feat: project persisted proposal suspension onto canonical run
  1d057c5 feat: project proposal approval, rejection, and expiry onto canonical run
  aa7d9ad feat: project frozen tool outcomes onto canonical run
Decisions:
  - 7.1 AsyncChatResultStore injects RunStateRepository (nullable guard);
    markQueued requires nonterminal run, markCompleted requires COMPLETED or
    DEGRADED, markFailed requires FAILED and no-ops otherwise (no overwrite of
    a successful payload by a late notification failure).
  - 7.2 AgentRunTraceService injects RunStateRepository (nullable); recordRunMetadata
    and upsertAgentRun read status from canonical state (UNKNOWN when absent);
    toRunStatus deleted; trace events/quality/steps/tool rows/payload JSON unchanged.
  - 7.3 ToolProposalCreatedEvent + AFTER_COMMIT ToolProposalLifecycleListener is the
    unique owner of persisted tool-proposal suspension (confirmationRequired);
    createPending is @Transactional and publishes the event.
  - 7.4 onExecutionRequested calls confirmationApproved + toolStarted before tool
    context; reject publishes ToolProposalRejectedEvent (AFTER_COMMIT ->
    confirmationRejected); expirePendingBefore is per-row CAS returning the exact
    expired list; ToolProposalCleanupTask projects failed(CONFIRMATION_EXPIRED)
    per expired proposal.
  - 7.5 toolInvoker success -> toolSucceeded (run not marked terminal, no strategy
    continuation claim); failure -> markFailed (now returns boolean) and only when
    it actually moved a nonterminal proposal to FAILED does it emit toolFailed +
    failed(TOOL_EXECUTION_FAILED). Idempotent.
  - markFailed signature changed void -> boolean; existing callers ignore the
    return value (Java-compatible). ToolRuntimeAspect and WorkspaceGitGuard untouched.
Deviation from plan:
  - RunStateRepository/LegacyLifecycleObserver are nullable with guards in
    AsyncChatResultStore and AgentRunTraceService (same pattern as Task 6 observer),
    so existing storage-mechanism and trace tests that do not pre-accept a canonical
    run remain green. Production Spring-injected path is non-null; projection
    correctness is proven by new focused tests with a real process-local store.
Tests:
  - full mvn test green (including DB-backed ToolInvocationProposalRepositoryTest
    and ToolRuntimeAspectInterceptionIT against local MySQL 8.0.43)
  - new focused tests: AsyncChatResultStoreProjectionTest (6),
    ToolProposalLifecycleListenerTest (4), AgentRunTraceServiceTest canonical
    projection cases (2)
  - 7.6 verification groups green
Prohibited-file check (2c7ede7..HEAD): clean (runtime, tool/runtime,
  service/workspace, dto, resources, .env.example untouched)
Whitespace check: clean
Environmental warnings: none (local MySQL connected via .env.local)
Limitation:
  - successful frozen tool execution does not yet resume the original strategy
    continuation (durable continuation remains Phase 4A)
Next dependency:
  Phase 2B Task 8 integration acceptance
```

## Update: Phase 2B Task 8 integration acceptance

```text
Task: Phase 2B Task 8 integration acceptance
Owner: Codex
Reviewed Claude commits:
  7ccc530 feat: project persisted proposal suspension onto canonical run
  1d057c5 feat: project proposal approval, rejection, and expiry onto canonical run
  aa7d9ad feat: project frozen tool outcomes onto canonical run
  1dd933d docs: record phase 2b task 7 projection evidence
Codex P1 fixes:
  e349b90 fix: terminate canonical runs on frozen tool failure
  435820d fix: isolate terminal tool failure projection
  3f62662 fix: isolate timeout terminal projection
Findings and corrections:
  - ToolRuntimeAspect may mark a proposal FAILED before the async execution
    owner catches the exception. The canonical run is now terminated even when
    the executor's idempotent markFailed call returns false.
  - toolFailed observation append and terminal failed transition use separate
    exception boundaries, so an observation failure cannot strand RUNNING.
  - cleanup of stuck EXECUTING proposals projects TOOL_FAILED and terminal
    TOOL_EXECUTION_TIMEOUT only when its CAS actually changes the proposal.
Acceptance evidence (2026-06-22, Asia/Shanghai):
  - Gate 2 contract/lifecycle core: 86 tests, 0 failures, 0 errors, 0 skips
  - Gate 3 characterization/Phase 2A: 53 tests, 0 failures, 0 errors, 0 skips
  - Gate 4 ingress/execution observations: 47 tests, 0 failures, 0 errors, 0 skips
  - Gate 5 async/trace/proposal/P0 safety: 61 tests, 0 failures, 0 errors, 0 skips
  - Gate 6 focused baseline: 29 tests, 0 failures, 0 errors, 0 skips
  - Gate 7 final full mvn test: 659 tests, 0 failures, 0 errors, 0 skips
Review:
  - bounded P0/P1 review after all three fixes: APPROVED
Environment:
  - local MySQL 8.0.43 connected through .env.local without exposing secrets
  - Redis and RabbitMQ were available during Spring integration tests
Scope:
  - no Task 7 change to tool/runtime, service/workspace, DTOs, resources, or env
  - e9013cc is the separately reviewed Codex core/stream fix after the original
    1f51ba2 freeze baseline
Known limits:
  - lifecycle storage and AFTER_COMMIT projections remain process-local
  - no restart durability, exactly-once execution, or duplicate suppression claim
  - successful frozen tool execution does not resume the original strategy;
    durable continuation remains Phase 4A
Rollback order:
  - revert Task 7 projection commits and Codex P1 fixes
  - revert Task 6 observation wiring
  - revert Task 5 ingress wiring
  - revert lifecycle core while retaining Phase 2A canonical identity
```

## Update: Phase 3A memory and context design

```text
Task: Phase 3A architecture and Phase 3A1 implementation planning
Owner: Codex
Design commits:
  c311c6c docs: design canonical memory and context ownership
  b97d37f docs: harden memory design after bounded review
Design:
  docs/superpowers/specs/
  2026-06-22-unified-runtime-memory-and-context-design.md
Bounded review:
  - architecture consistency: 5 P1 findings
  - data/security/operations: 1 P0 and 4 P1 findings
  - scope/YAGNI/implementability: APPROVED
  - reviewers were explicitly limited to P0/P1 and forbidden from code polish,
    style suggestions, or scope expansion
Closed findings:
  - ContextSnapshot must embed the complete structured MemoryFrame
  - legacy Phase 2B context producer must stop at canonical activation
  - factory, Advisors, and old retrieval must switch in one activation unit
  - confirmation suspension must not create terminal memory
  - snapshot role reuses roleCodeAtAcceptance
  - REST strings cannot mint SHARED_SESSION access
  - logical memory identity is separate from version-row identity
  - outbox uses revision fencing and recoverable claim leases
  - short-term Redis order uses persisted event IDs and recovery watermark
  - vector rebuild uses a new generation and authoritative revision watermark
Next implementation plan:
  docs/superpowers/plans/
  2026-06-23-unified-runtime-phase-3a1-memory-core.md
Phase 3A1 boundary:
  - add memory storage authority and shadow projections
  - do not activate MemoryFrame/ContextSnapshotFactory
  - do not replace Advisors or legacy context owner
  - do not change route, answer, lock, stream, or tool-safety ownership
Planned ownership:
  - Codex: acceptance claim, contracts, lifecycle, management invariants,
    acceptance and final review
  - Claude: MySQL/Redis/vector/Markdown adapters and focused wiring after each
    corresponding Codex core commit
Progress:
  - Phase 3A1 Task 1 implementation and bounded P0/P1 review complete
  - trusted SessionAccessClaim is frozen at acceptance and propagated through
    canonical lifecycle state
  - REST and unverified webhook input cannot mint SHARED access
  - verified HTTP webhook and Feishu SDK long connection carry explicit trusted
    ingress evidence
  - focused lifecycle, ingress, security, and compatibility tests pass
  - Phase 3A1 Task 2 typed memory contracts and bounded fallback stores complete
  - MySQL adapters can map record rows without reconstructing authorization claims
  - fallback stores enforce active/source/outbox uniqueness, lease fencing,
    bounded short-term windows, and shared-session storage identity
  - Phase 3A1 Task 3 MySQL schema and persistence adapters complete
  - bounded P0/P1 review closed active-slot NULL CAS, database-time lease
    fencing, lowest-revision claim TOCTOU, fail-loud migration, and claim-token
    reread fencing
  - real local MySQL integration tests pass without exposing credentials
  - next Codex task: Task 4 atomic memory lifecycle management
```
