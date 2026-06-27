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
  - Phase 3A1 Task 4 atomic memory lifecycle management complete
  - record and outbox changes share MySQL transactions or rollback-capable
    process-local transaction boundaries
  - automatic source retries converge under concurrent MySQL and local writes
  - supersede ordering is fenced as UPSERT(1), DELETE(2), UPSERT(3)
  - Phase 3A1 Task 5 stable event receipts and persistence intent complete
  - Claude implemented explicit MessageEventWrite/Receipt, ChatPersistenceIntent,
    terminal/suspension persistence split, and migrated terminal call sites
  - Codex bounded P0/P1 review fixed duplicate-key DB receipt reload and bounded
    local event-key fallback cleanup
  - focused Task 5 tests pass
  - Phase 3A1 Task 6 Redis short-term shadow storage and recovery complete
  - Claude started Redis store/recovery/writer and persister wiring; Codex picked
    up the interrupted work and closed compile/runtime gaps
  - Redis short-term storage uses eventId as ZSET score, Lua ZADD NX + trim + TTL,
    and StringCodec for script arguments
  - recovery uses a token-checked per-scope lease, owner-filtered CHAT
    user/assistant events, and mergeRecovery watermark semantics
  - terminal shadow writes user + assistant receipts; confirmation suspension
    writes user only; Redis/shadow failure leaves MySQL as recovery source
  - Redis beans are conditional on RedissonClient; writer no-ops when Redis is not
    enabled, preserving default startup
  - focused Task 6 tests and package pass
  - Phase 3A1 Task 7 fenced vector projection and rebuild primitives complete
  - MemoryVectorIndex wraps Spring AI VectorStore with memoryVersionId document
    identity and canonical metadata fields
  - index worker fences stale UPSERTs, compensates authority changes, and uses
    claim-token completion through MemoryIndexOutboxStore
  - reconciler deletes indexed ids that are no longer authoritative active
    versions
  - rebuild creates a new generation, marks retrieval degraded during rebuild,
    copies active records, applies only non-succeeded tail events after the
    active watermark, and activates the new generation
  - VectorStore-dependent workers are conditional so default startup is preserved
  - focused Task 7 tests, memory regression tests, and package pass
  - Phase 3A1 Task 8 typed Markdown project and procedural memory complete
  - Claude exposed reviewed Markdown project memory as typed ProjectMemoryItem
    sources and preserved bounded legacy MemoryBankService rendering from those
    typed items
  - newly captured automatic learnings now start as candidate; only approved,
    active, and explicitly retained legacy learning sections enter runtime
    project memory context
  - Phase 3A1 Task 9 shadow mode wiring and closing evidence complete
  - Codex added disabled-by-default memory core flags:
    SPRINGCLAW_MEMORY_CORE_ENABLED=false,
    SPRINGCLAW_MEMORY_SHORT_TERM_SHADOW_ENABLED=false,
    SPRINGCLAW_MEMORY_INDEX_WORKER_ENABLED=false,
    SPRINGCLAW_MEMORY_SCHEMA_AUTO_INIT=true,
    SPRINGCLAW_MEMORY_SHORT_TERM_MAX_MESSAGES=40,
    SPRINGCLAW_MEMORY_SHORT_TERM_TTL_DAYS=7
  - when memory core DB persistence is disabled, Spring wires bounded
    process-local MemoryRecordStore and MemoryIndexOutboxStore; MySQL memory
    store integration tests explicitly enable springclaw.memory.core.enabled
  - Redis short-term shadow store/recovery are no longer component-scanned
    directly; MemoryShortTermShadowConfig registers them only when
    springclaw.memory.core.short-term-shadow-enabled=true
  - vector worker/reconciler/rebuild beans are gated by
    springclaw.memory.core.index-worker-enabled=true and remain inactive by
    default
  - REST authenticated API group-like strings still produce PERSONAL
    AUTHENTICATED_API claims; existing ChatControllerAuthTest covers this
    boundary
  - active Phase 2B context, Advisors, routes, final-answer ownership, stream
    transport, and tool-safety guards remain unchanged in Phase 3A1
Phase 3A1 commits:
  - 1a3792e docs: plan phase 3a1 canonical memory core
  - 5ad582d feat: freeze trusted memory access at run acceptance
  - 3704c40 feat: add canonical memory contracts and fallback stores
  - 1772d95 feat: persist versioned memory and index outbox
  - 3c26163 fix: fence memory persistence transitions
  - 871d869 feat: manage memory versions and index events atomically
  - 043d1ec feat: distinguish terminal and suspended memory writes
  - 47c3f1e fix: harden stable message event receipts
  - 4543723 feat: project ordered short-term memory into redis
  - 30b7b0b feat: fence memory vector projection and rebuild
  - 249a2cb fix: gate memory index background beans
  - 97ebfb6 feat: expose reviewed project memory as typed sources
  - ba010e9 feat: wire memory core shadow mode
Task 9 verification:
  - RED: mvn -q -Dtest=MemoryCoreShadowIT test failed because
    MemoryCoreStoreConfig was missing
  - focused suite: 119 tests, 0 failures, 0 errors, 0 skipped
  - compatibility gates: 40 tests, 0 failures, 0 errors, 0 skipped
  - full suite: 731 tests, 0 failures, 0 errors, 0 skipped
  - MySQL-backed tests were run with local /Users/hanbingzheng/springclaw/.env.local
    environment variables loaded without printing secrets
Known Phase 3A1 limitations:
  - canonical MemoryFrame read activation is not enabled
  - ContextSnapshotFactory is not wired
  - automatic semantic extraction is not implemented
  - process-local memory stores are bounded but not restart durable when DB
    persistence is disabled
Rollback order:
  - set SPRINGCLAW_MEMORY_SHORT_TERM_SHADOW_ENABLED=false and
    SPRINGCLAW_MEMORY_INDEX_WORKER_ENABLED=false
  - set SPRINGCLAW_MEMORY_CORE_ENABLED=false to use local bounded stores
  - revert ba010e9 if shadow wiring itself must be removed
  - revert 97ebfb6 to remove typed Markdown project/procedural memory
  - revert 30b7b0b and 249a2cb to remove vector projection/rebuild wiring
  - revert 4543723 to remove Redis short-term projection
  - revert 043d1ec and 47c3f1e to remove stable persistence intent/event
    receipt changes
Next dependency:
  - Phase 3A2/3A3 may activate canonical MemoryFrame retrieval and
    ContextSnapshotFactory in a separate, single-cutover plan
```

## Update: Phase 3A2 MemoryFrame shadow retrieval

```text
Task: Phase 3A2 canonical MemoryFrame retrieval as disabled shadow infrastructure
Owner: Codex
Design and plan:
  - da67104 docs: design phase 3a2 memory frame retrieval
  - ad6c29b docs: plan phase 3a2 memory frame retrieval
Implementation commits:
  - 2d3ea2e feat: add memory frame contracts
  - 37f8cb4 feat: add memory frame hashing and budgets
  - 1a0618b feat: assemble canonical memory frames
  - d6fd9ef feat: wire memory frame shadow retrieval
Modified ownership:
  - runtime memory contracts:
    src/main/java/com/springclaw/runtime/memory/contract/MemoryFrame*.java
    src/main/java/com/springclaw/runtime/memory/contract/MemoryRetrievalTrace.java
  - frame assembly service:
    src/main/java/com/springclaw/service/memory/frame/*
  - disabled-by-default wiring:
    src/main/java/com/springclaw/config/MemoryFrameConfig.java
    src/main/resources/application.yml
    .env.example
  - tests:
    src/test/java/com/springclaw/runtime/memory/MemoryFrameContractTest.java
    src/test/java/com/springclaw/service/memory/frame/*
Active prompt/input boundary:
  - ContextAssembler unchanged
  - SemanticMemoryAdvisor unchanged
  - ConversationAdvisorSupport unchanged
  - ContextSnapshot unchanged
  - RunCoordinator unchanged
  - no engine behavior or prompt-input owner activated MemoryFrame
Flags and default behavior:
  - SPRINGCLAW_MEMORY_FRAME_ENABLED=false
  - SPRINGCLAW_MEMORY_FRAME_SHADOW_COMPARE_ENABLED=false
  - SPRINGCLAW_MEMORY_FRAME_MAX_CHARS=6000
  - SPRINGCLAW_MEMORY_FRAME_TRACE_MAX_WARNINGS=20
  - MemoryCoordinator bean is not created unless
    springclaw.memory.frame.enabled=true
  - shadow comparator is read-only and does not mutate legacy AssembledContext
Functionality:
  - immutable MemoryFrame and MemoryRetrievalTrace contracts
  - deterministic canonical frame hashing excluding frameHash self-reference
  - budget utility for deterministic layer caps
  - MemoryCoordinator reads one accepted MemoryScope from:
    short-term store, active memory records, and reviewed project Markdown
  - project CANDIDATE/REJECTED entries are omitted from runtime frame content
  - duplicate content hashes are omitted with trace counts
  - missing optional short-term store produces SOURCE_UNAVAILABLE omission
Verification (2026-06-24, Asia/Shanghai):
  - focused Phase 3A2 suite:
    67 tests, 0 failures, 0 errors, 0 skipped
  - compatibility gates:
    40 tests, 0 failures, 0 errors, 0 skipped
  - full suite:
    749 tests, 0 failures, 0 errors, 0 skipped
  - full and compatibility gates loaded local
    /Users/hanbingzheng/springclaw/.env.local without printing secrets
Known limitations:
  - ContextSnapshotFactory still inactive
  - ContextSnapshot still does not embed MemoryFrame
  - Advisors still perform legacy retrieval until Phase 3A3
  - MemoryFrame is not injected into active prompts or engine context
  - vector candidates remain optional and authority-checked; Phase 3A2 does
    not activate vector candidate retrieval
Rollback order:
  - set SPRINGCLAW_MEMORY_FRAME_ENABLED=false
  - set SPRINGCLAW_MEMORY_FRAME_SHADOW_COMPARE_ENABLED=false
  - revert d6fd9ef to remove shadow wiring/config/comparator
  - revert 1a0618b to remove MemoryCoordinator assembly
  - revert 37f8cb4 to remove hashing/budget utilities
  - revert 2d3ea2e to remove MemoryFrame contracts
Next dependency:
  - Phase 3A3 must be a separate single-cutover plan to activate
    ContextSnapshotFactory/MemoryFrame prompt ownership and retire duplicate
    legacy retrieval paths.
```

## Update: Phase 3A3a ContextSnapshot bridge

```text
Task: Phase 3A3a conservative ContextSnapshot bridge
Owner split:
  - Claude: Task 1-3 implementation
  - Codex: review fix, Task 4-6 implementation, final gates
Design and plan:
  - 3dfbedc docs: design phase 3a3a context snapshot bridge
  - aea0071 docs: plan phase 3a3a context snapshot bridge
Implementation commits:
  - f90aae2 feat: embed memory frame in context snapshot
  - 20c48fa feat: build context snapshots from memory frames
  - bde2754 feat: project context snapshots to legacy views
  - ad0a9e1 fix: stabilize legacy memory frame timestamps
  - 84c66cb feat: bridge chat context through canonical snapshots
  - 6db1ec2 feat: guard semantic advisor in canonical context mode
Modified paths:
  - src/main/java/com/springclaw/runtime/contract/ContextSnapshot.java
  - src/main/java/com/springclaw/runtime/contract/ContextSnapshotFactory.java
  - src/main/java/com/springclaw/runtime/contract/ContextSnapshotRequest.java
  - src/main/java/com/springclaw/runtime/bridge/LegacyContextView.java
  - src/main/java/com/springclaw/runtime/bridge/LegacyContextViewAdapter.java
  - src/main/java/com/springclaw/runtime/bridge/LegacyRunContextAdapter.java
  - src/main/java/com/springclaw/runtime/memory/contract/MemoryScope.java
  - src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java
  - src/main/java/com/springclaw/service/chat/impl/ConversationAdvisorSupport.java
  - src/main/resources/application.yml
  - .env.example
  - focused tests under src/test/java/com/springclaw/runtime and
    src/test/java/com/springclaw/service/chat/impl
Default behavior:
  - SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false
  - default ChatContextFactory path still calls ContextAssembler
  - default ConversationAdvisorSupport still attaches SemanticMemoryAdvisor as
    before
  - no route, engine, stream, final-answer, tool-safety, proposal, or workspace
    ownership change
Canonical-mode behavior:
  - ChatContextFactory can use ContextSnapshotFactory behind the default-off flag
  - ContextSnapshotFactory calls MemoryCoordinator once to build a MemoryFrame
  - LegacyContextViewAdapter projects the saved snapshot back to AssembledContext
    and ContextInjection so old engines still receive legacy-compatible inputs
  - ConversationAdvisorSupport suppresses independent SemanticMemoryAdvisor
    retrieval in canonical snapshot mode
Review notes:
  - Codex reviewed Claude Task 1-3 scope and found no ChatContextFactory or
    Advisor changes outside the requested boundary
  - Codex fixed nondeterministic legacy compatibility item timestamps by using
    capturedAt instead of Instant.now()
  - local surefire fork occasionally left Maven parent sessions waiting; focused
    and full gates were run with -DforkCount=0 to avoid that environment issue
Verification (2026-06-24, Asia/Shanghai):
  - focused Phase 3A3a suite:
    21 tests, 0 failures, 0 errors, 0 skipped
  - compatibility gates:
    40 tests, 0 failures, 0 errors, 0 skipped
  - full suite:
    757 tests, 0 failures, 0 errors, 0 skipped
  - compatibility and full gates loaded local
    /Users/hanbingzheng/springclaw/.env.local without printing secrets
Known limitations:
  - canonical mode still derives a REST personal SessionAccessClaim in
    ChatContextFactory; Phase 3A3b must use the real accepted
    RunState.sessionAccessClaim
  - ContextAssembler still exists and remains active in default/legacy mode
  - SemanticMemoryAdvisor still exists and remains active in default/legacy mode
  - ContextSnapshotFactory is a bridge class, not yet the default production
    owner
  - projection-only Advisor remains deferred
  - removing legacy context producer and old retrieval paths remains Phase 3A3b
Rollback order:
  - set SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false
  - set SPRINGCLAW_MEMORY_FRAME_ENABLED=false if memory frame retrieval also
    needs to be disabled
  - revert 6db1ec2 to restore Advisor selection
  - revert 84c66cb to remove ChatContextFactory bridge/config flag
  - revert ad0a9e1, bde2754, and 20c48fa to remove snapshot factory/view bridge
  - revert f90aae2 last if ContextSnapshot.memoryFrame must be removed
Next dependency:
  - Phase 3A3b should make canonical snapshot ownership the default, use the
    real accepted SessionAccessClaim, replace SemanticMemoryAdvisor with
    projection-only behavior if needed, and stop production runtime reads from
    ContextAssembler.
```

## Update: Phase 3A3b canonical ContextSnapshot ownership

```text
Task: Phase 3A3b canonical context ownership default-on
Owner split:
  - Claude: Task 1 Spring wiring/defaults
  - Codex: Task 2 request factory review/fix, Task 3 ChatContextFactory
    accepted-run ownership, Task 4 LegacyLifecycleObserver split, Task 5-6
    gates and ledger
Design and plan:
  - 8bfb3c3 docs: design phase 3a3b canonical context ownership
  - 595b466 docs: plan phase 3a3b canonical context ownership
Implementation commits:
  - 833f662 feat: enable canonical snapshot wiring by default
  - c38aad7 feat: derive snapshot requests from accepted runs
  - 89ad901 feat: use accepted run state for canonical context
  - e3ef509 feat: skip legacy context observation in canonical mode
Modified paths:
  - src/main/java/com/springclaw/config/ContextSnapshotConfig.java
  - src/main/java/com/springclaw/config/MemoryFrameConfig.java
  - src/main/java/com/springclaw/runtime/bridge/RunStateContextSnapshotRequestFactory.java
  - src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java
  - src/main/java/com/springclaw/runtime/bridge/LegacyLifecycleObserver.java
  - src/main/resources/application.yml
  - .env.example
  - focused tests under src/test/java/com/springclaw/runtime/bridge,
    src/test/java/com/springclaw/runtime/contract,
    src/test/java/com/springclaw/service/chat/impl, and lifecycle projection
    compatibility fixtures
Default behavior:
  - SPRINGCLAW_MEMORY_FRAME_ENABLED=true
  - SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=true
  - ContextSnapshotFactory, LegacyContextViewAdapter, and
    RunStateContextSnapshotRequestFactory are wired by default when
    MemoryCoordinator is available
  - ChatContextFactory canonical branch uses the accepted RunState and its
    SessionAccessClaim as the MemoryFrame/ContextSnapshot authority source
  - LegacyLifecycleObserver no longer emits a second contextObserved event in
    canonical mode
Rollback behavior:
  - set SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false to return
    ChatContextFactory to ContextAssembler and keep legacy lifecycle context
    observation
  - set SPRINGCLAW_MEMORY_FRAME_ENABLED=false if MemoryCoordinator/frame
    retrieval must also be disabled
Compatibility notes:
  - route policy, final-answer ownership, stream termination, tool approval,
    proposal lifecycle, workspace guard, and tool runtime safety were not
    intentionally changed
  - legacy lifecycle projection tests that validate old event ordering now pass
    contextSnapshotFactoryEnabled=false explicitly
Verification (2026-06-24, Asia/Shanghai):
  - focused Phase 3A3b suite:
    24 tests, 0 failures, 0 errors, 0 skipped
  - compatibility gates:
    40 tests, 0 failures, 0 errors, 0 skipped
  - full suite:
    769 tests, 0 failures, 0 errors, 0 skipped
  - compatibility and full gates loaded local
    /Users/hanbingzheng/springclaw/.env.local without printing secrets
  - final gates used MAVEN_OPTS=-Djdk.lang.Process.launchMechanism=FORK
    because this local macOS/JDK environment otherwise intermittently fails
    Java ProcessBuilder sh/python3 launches with "Failed to exec spawn helper"
Known limitations:
  - ContextAssembler and SemanticMemoryAdvisor remain present for explicit
    rollback/legacy paths
  - canonical context ownership is default-on, but full runtime reducer
    ownership is still incremental; future phases should make canonical
    contextReady projection explicit rather than relying on legacy bridge
    compatibility fixtures
  - projection-only Advisor cleanup and removal of old retrieval paths remain
    deferred
Rollback order:
  - set SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false
  - revert e3ef509 to restore legacy contextObserved in observer default
  - revert 89ad901 to restore reconstructed ChatContextFactory snapshot request
  - revert c38aad7 to remove RunStateContextSnapshotRequestFactory behavior
  - revert 833f662 to return canonical snapshot wiring/memory frame defaults to
    their previous default-off state
Next dependency:
  - Next phase should decide whether to remove or quarantine duplicate legacy
    retrieval paths, and should make canonical RunCoordinator contextReady
    projection explicit for the default runtime path.
```

## Update: Phase 3A4 explicit contextReady projection and retrieval quarantine

```text
Task: Phase 3A4 explicit contextReady projection and retrieval quarantine
Owner split:
  - Claude: Task 1-2 implementation
  - Codex: review, Task 3-4 completion, Task 5 gates and ledger
Design and plan:
  - a2ce9eb docs: plan phase 3a4 context projection
Implementation commits:
  - 0075078 feat: project canonical context snapshots to run state
  - 3e75b41 feat: project chat snapshots to context ready
  - 95381a3 test: prove canonical retrieval boundaries
Modified paths:
  - src/main/java/com/springclaw/config/ContextSnapshotConfig.java
  - src/main/java/com/springclaw/runtime/bridge/CanonicalContextReadyProjector.java
  - src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java
  - src/test/java/com/springclaw/runtime/bridge/CanonicalContextReadyProjectorTest.java
  - src/test/java/com/springclaw/runtime/bridge/LegacyLifecycleObserverCanonicalModeTest.java
  - src/test/java/com/springclaw/runtime/contract/ContextSnapshotFactorySpringWiringTest.java
  - src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalLifecycleProjectionTest.java
  - src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalOwnershipTest.java
  - src/test/java/com/springclaw/service/chat/impl/ChatContextFactoryCanonicalSnapshotTest.java
  - src/test/java/com/springclaw/architecture/CanonicalRetrievalBoundaryTest.java
Default behavior:
  - ContextSnapshotConfig wires CanonicalContextReadyProjector in canonical mode
  - ChatContextFactory projects the canonical ContextSnapshot into RunState
    CONTEXT_READY using snapshot.capturedAt()
  - LegacyLifecycleObserver canonical mode then emits DECISION_MADE without a
    duplicate legacy contextObserved event
  - canonical mode tests prove ContextAssembler is not called
Rollback behavior:
  - set SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false to return
    ChatContextFactory to ContextAssembler and legacy context observation
  - set SPRINGCLAW_MEMORY_FRAME_ENABLED=false if MemoryCoordinator/frame
    retrieval must also be disabled
Verification (2026-06-25, Asia/Shanghai):
  - focused Phase 3A4 suite:
    19 tests, 0 failures, 0 errors, 0 skipped
  - compatibility gates:
    40 tests, 0 failures, 0 errors, 0 skipped
  - full suite:
    776 tests, 0 failures, 0 errors, 0 skipped
  - compatibility and full gates loaded local
    /Users/hanbingzheng/springclaw/.env.local without printing secrets
  - final gates used MAVEN_OPTS=-Djdk.lang.Process.launchMechanism=FORK
    because this local macOS/JDK environment otherwise intermittently fails
    Java ProcessBuilder sh/python3 launches with "Failed to exec spawn helper"
  - final gates overrode spring.datasource.url with allowPublicKeyRetrieval=true
    because the local MySQL account uses caching_sha2_password and rejected
    public key retrieval with the default application.yml URL
  - Redis dependency was satisfied by the existing Docker container
    openclaw-redis on 127.0.0.1:6379
Known limitations:
  - ContextAssembler and SemanticMemoryAdvisor remain present for rollback
  - legacy code is quarantined by tests, not deleted
  - production smoke validation with a real accepted run remains required before
    deleting old retrieval code
Rollback order:
  - set SPRINGCLAW_CONTEXT_SNAPSHOT_FACTORY_ENABLED=false
  - revert 95381a3 to remove the added boundary tests
  - revert 3e75b41 to stop projecting snapshots from ChatContextFactory
  - revert 0075078 to remove CanonicalContextReadyProjector and wiring
Next dependency:
  - run a production-like smoke test with a real accepted run, then decide
    whether to delete or permanently quarantine ContextAssembler and
    SemanticMemoryAdvisor legacy retrieval paths.
```

## Update: Phase 3A5 accepted-run smoke and confirmation approval guard

```text
Task: Phase 3A5 accepted-run smoke and confirmation approval guard
Branch:
  - codex/unified-runtime-phase-3a5-smoke-confirmation
Design and plan:
  - docs/superpowers/plans/2026-06-25-unified-runtime-phase-3a5-accepted-run-smoke-and-confirmation-guard.md
Implementation commits:
  - 65aab2c fix: guard confirmation approval state
  - 8d40ce5 test: smoke canonical accepted run lifecycle
Modified paths:
  - src/main/java/com/springclaw/runtime/lifecycle/RunCoordinator.java
  - src/test/java/com/springclaw/runtime/lifecycle/RunCoordinatorTest.java
  - src/test/java/com/springclaw/service/chat/impl/AcceptedRunCanonicalSmokeTest.java
Behavior:
  - RunCoordinator.confirmationApproved now explicitly requires
    WAITING_CONFIRMATION, matching confirmationRejected's state guard
  - stale approval before WAITING_CONFIRMATION fails with a clear boundary
    message and does not mutate the run state
  - accepted-run canonical smoke uses real InMemoryRunLifecycleStore,
    RunCoordinator, ContextSnapshotFactory, RunStateContextSnapshotRequestFactory,
    CanonicalContextReadyProjector, LegacyContextViewAdapter, and
    LegacyLifecycleObserver
  - smoke mocks external dependencies only: auth/session/model/routing/skill
    lookup and memory retrieval source
Smoke event sequence:
  - RUN_CREATED
  - CONTEXT_READY
  - DECISION_MADE
  - STRATEGY_STARTED
  - VERIFICATION_COMPLETED
  - RUN_DEGRADED
Verification (2026-06-25, Asia/Shanghai):
  - RED check for confirmation approval guard failed before implementation
    because the lower-level transition/schema validation raised a non-specific
    error instead of the confirmation WAITING_CONFIRMATION boundary
  - RunCoordinatorTest:
    10 tests, 0 failures, 0 errors, 0 skipped
  - AcceptedRunCanonicalSmokeTest:
    1 test, 0 failures, 0 errors, 0 skipped
  - focused Phase 3A5 gate:
    11 tests, 0 failures, 0 errors, 0 skipped
  - full suite:
    778 tests, 0 failures, 0 errors, 0 skipped
  - full gate loaded local
    /Users/hanbingzheng/springclaw/.env.local without printing secrets
  - full gate used MAVEN_OPTS=-Djdk.lang.Process.launchMechanism=FORK
  - full gate overrode spring.datasource.url with allowPublicKeyRetrieval=true
    for the local MySQL auth mode
Known limitations:
  - smoke is production-like but still not a live HTTP/controller test and does
    not call a real model provider
  - ContextAssembler and SemanticMemoryAdvisor remain present for rollback
  - ChatContext still reflects the normalized request identity while canonical
    snapshot construction reads the accepted RunState; controller normalization
    remains the production boundary for mismatched request identity
Rollback order:
  - revert 8d40ce5 to remove the accepted-run smoke test
  - revert 65aab2c to restore the previous confirmationApproved behavior
Next dependency:
  - after PR review/merge, decide whether Phase 3B should add an HTTP-level
    accepted-run smoke or proceed to permanently quarantining legacy retrieval
    behind explicit rollback flags.
```

## Update: Phase 3B HTTP accepted-run smoke

```text
Task: Phase 3B HTTP accepted-run smoke
Branch:
  - codex/unified-runtime-phase-3b-http-smoke
Design and plan:
  - docs/superpowers/plans/2026-06-25-unified-runtime-phase-3b-http-accepted-run-smoke.md
Modified paths:
  - src/test/java/com/springclaw/service/chat/impl/ChatControllerCanonicalHttpSmokeTest.java
Runtime path proven:
  - MockMvc POST /api/chat/send
  - real ChatController normalizes the authenticated request
  - real DefaultLegacyRuntimeBridge accepts the run
  - real ChatServiceImpl consumes AcceptedChatCommand with the same runId
  - real ChatContextFactory builds canonical ContextSnapshot
  - real CanonicalContextReadyProjector advances the run to CONTEXT_READY
  - real LegacyLifecycleObserver in canonical mode emits decision/running/terminal
  - external model/session/auth/skill/routing/memory source dependencies are mocked
Smoke event sequence:
  - RUN_CREATED
  - CONTEXT_READY
  - DECISION_MADE
  - STRATEGY_STARTED
  - VERIFICATION_COMPLETED
  - RUN_DEGRADED
Verification (2026-06-25, Asia/Shanghai):
  - ChatControllerCanonicalHttpSmokeTest:
    1 test, 0 failures, 0 errors, 0 skipped
  - focused Phase 3B gate:
    12 tests, 0 failures, 0 errors, 0 skipped
  - full suite:
    779 tests, 0 failures, 0 errors, 0 skipped
  - full gate loaded local
    /Users/hanbingzheng/springclaw/.env.local without printing secrets
  - full gate used MAVEN_OPTS=-Djdk.lang.Process.launchMechanism=FORK
  - full gate overrode spring.datasource.url with allowPublicKeyRetrieval=true
    for the local MySQL auth mode
Known limitations:
  - smoke uses MockMvc standalone rather than a full SpringBoot web server
  - smoke does not call a real model provider, real Redis memory source, or real
    MySQL session store
  - ContextAssembler and SemanticMemoryAdvisor remain present for rollback and
    were not deleted in this phase
Rollback order:
  - revert the Phase 3B smoke test commit
Next dependency:
  - ask for review, merge if accepted, then decide between full SpringBoot HTTP
    integration smoke or permanent legacy retrieval removal/quarantine work.
```

## Update: Phase 3C SpringBoot real-env canonical HTTP smoke

```text
Task: Phase 3C SpringBoot real-env canonical HTTP smoke
Branch:
  - codex/unified-runtime-phase-3c-springboot-smoke
Design and plan:
  - docs/superpowers/plans/2026-06-25-unified-runtime-phase-3c-springboot-real-env-smoke.md
Modified paths:
  - src/test/java/com/springclaw/controller/ChatControllerSpringBootCanonicalSmokeIT.java
Runtime path proven:
  - MockMvc POST /api/chat/send through the real SpringBoot web context
  - real TokenAuthenticationInterceptor authenticates Bearer token into
    RequestUserContextHolder
  - real ChatController normalizes the authenticated request and accepts the run
  - real ChatServiceImpl consumes AcceptedChatCommand with the same runId
  - real ChatContextFactory uses the canonical ContextSnapshotFactory branch
  - real ContextSnapshotFactory calls real MemoryCoordinator
  - real MemoryCoordinator retrieves seeded Redis short-term memory, seeded
    MySQL memory_record semantic/procedural memories, and docs/memory-bank
    project memory
  - real CanonicalContextReadyProjector advances the run to CONTEXT_READY
  - real LegacyLifecycleObserver in canonical mode emits decision/running/terminal
  - external model/auth/decision/engine dependencies are mocked
Smoke event sequence:
  - RUN_CREATED
  - CONTEXT_READY
  - DECISION_MADE
  - STRATEGY_STARTED
  - VERIFICATION_COMPLETED
  - RUN_DEGRADED
Verification (2026-06-25, Asia/Shanghai):
  - RED 1:
    mvn -Dtest=ChatControllerSpringBootCanonicalSmokeIT test
    failed at test compile due to wrong ContextSnapshot accessor
    sourceSummary() vs contextSourceSummary()
  - RED 2:
    same command failed Spring context startup because local MySQL requires
    allowPublicKeyRetrieval=true
  - RED 3:
    same command with allowPublicKeyRetrieval=true failed because isolated
    worktree did not load the main project .env.local, so default
    MYSQL_PASSWORD=root was used
  - GREEN:
    set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a;
    mvn -Dtest=ChatControllerSpringBootCanonicalSmokeIT test
    1 test, 0 failures, 0 errors, 0 skipped
  - Phase 3B + Phase 3C smoke gate:
    set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a;
    mvn -Dtest=ChatControllerCanonicalHttpSmokeTest,ChatControllerSpringBootCanonicalSmokeIT test
    2 tests, 0 failures, 0 errors, 0 skipped
  - Memory adapter gate:
    set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a;
    mvn -Dtest=MemoryManagementServiceIT,MySqlMemoryStoresIT,RedisShortTermMemoryStoreTest test
    27 tests, 0 failures, 0 errors, 0 skipped
  - full suite:
    set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a;
    MAVEN_OPTS='-Djdk.lang.Process.launchMechanism=FORK' mvn test
    806 tests, 0 failures, 0 errors, 0 skipped
Known limitations:
  - smoke still mocks external model provider, auth token verification,
    AgentDecisionService, and EngineSelector/AgentEngine execution
  - smoke does not start an actual TCP web server; it uses MockMvc against the
    real Spring web application context
  - in this isolated worktree, MySQL verification requires loading the main
    project /Users/hanbingzheng/springclaw/.env.local without printing secrets
Rollback order:
  - revert the Phase 3C smoke test commit
Next dependency:
  - ask for review, merge if accepted, then proceed to the next legacy
    retrieval retirement/quarantine task only after this SpringBoot gate remains
    green.
```

## Update: Phase 3D retrieval advisor quarantine

```text
Task: Phase 3D retrieval advisor quarantine
Branch:
  - codex/unified-runtime-phase-3d-retrieval-quarantine
Design and plan:
  - docs/superpowers/plans/2026-06-25-unified-runtime-phase-3d-retrieval-quarantine.md
Modified paths:
  - src/main/java/com/springclaw/service/chat/impl/ConversationAdvisorSupport.java
  - src/test/java/com/springclaw/service/chat/impl/ConversationAdvisorSupportCanonicalModeTest.java
Runtime path tightened:
  - canonical ContextSnapshot mode now attaches no Spring AI retrieval advisors
    at model-call time
  - ContextSnapshotFactory/MemoryCoordinator remains the single context/memory
    retrieval source before model execution
  - rollback/default mode still preserves existing advisor behavior:
    SemanticMemoryAdvisor when Spring AI chat memory is off, and
    MessageChatMemoryAdvisor + SemanticMemoryAdvisor when it is on
RED evidence:
  - mvn -Dtest=ConversationAdvisorSupportCanonicalModeTest test
  - failed because canonical mode still attached MessageChatMemoryAdvisor when
    springclaw.chat.spring-ai-chat-memory-enabled=true
Verification (2026-06-25, Asia/Shanghai):
  - advisor quarantine + rollback characterization:
    mvn -Dtest=ConversationAdvisorSupportCanonicalModeTest,ContextPropagationCharacterizationTest test
    7 tests, 0 failures, 0 errors, 0 skipped
  - canonical boundary + HTTP smoke gate:
    set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a;
    mvn -Dtest=CanonicalRetrievalBoundaryTest,ChatControllerCanonicalHttpSmokeTest,ChatControllerSpringBootCanonicalSmokeIT test
    4 tests, 0 failures, 0 errors, 0 skipped
  - full suite:
    set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a;
    MAVEN_OPTS='-Djdk.lang.Process.launchMechanism=FORK' mvn test
    807 tests, 0 failures, 0 errors, 0 skipped
Known limitations:
  - this phase does not delete ContextAssembler, SemanticMemoryAdvisor, or
    MessageChatMemoryAdvisor because they remain rollback/default components
  - this phase does not change MemoryController manual recall endpoints
Rollback order:
  - revert the Phase 3D commit to restore canonical-mode MessageChatMemoryAdvisor
    attachment when the Spring AI chat memory flag is on
Next dependency:
  - review and merge Phase 3D, then continue to either persistent RunState
    storage or deeper legacy component deletion once rollback policy allows it.
```

## Update: Phase 3E MySQL run lifecycle persistence

```text
Task: Phase 3E MySQL RunLifecycleStore
Branch:
  - codex/unified-runtime-phase-3e-run-lifecycle-mysql
Design and plan:
  - docs/superpowers/plans/2026-06-25-unified-runtime-phase-3e-mysql-run-lifecycle-store.md
Modified paths:
  - src/main/java/com/springclaw/config/RuntimeLifecycleStoreConfig.java
  - src/main/java/com/springclaw/config/RuntimeLifecycleSchemaInitializer.java
  - src/main/java/com/springclaw/runtime/lifecycle/InMemoryRunLifecycleStore.java
  - src/main/java/com/springclaw/runtime/lifecycle/MySqlRunLifecycleStore.java
  - src/main/resources/application.yml
  - src/main/resources/sql/migrations/2026-06-25-runtime-run-lifecycle.sql
  - src/test/java/com/springclaw/config/RuntimeLifecycleSchemaInitializerTest.java
  - src/test/java/com/springclaw/runtime/lifecycle/RuntimeLifecycleStoreConfigTest.java
  - src/test/java/com/springclaw/runtime/lifecycle/MySqlRunLifecycleStoreIT.java
Runtime path added:
  - default runtime.lifecycle store remains process-local InMemoryRunLifecycleStore
  - springclaw.runtime.lifecycle.store=mysql selects MySqlRunLifecycleStore
  - runtime_run_state stores the latest canonical RunState JSON with revision
    and query columns
  - runtime_run_event stores append-only RunEvent JSON with per-run sequence
  - MySqlRunLifecycleStore preserves current RunLifecycleStore semantics:
    idempotent same acceptance, conflicting create rejection, stale revision
    rejection, transition-policy validation, and observation append without
    state revision mutation
RED evidence:
  - mvn -q -Dtest=RuntimeLifecycleStoreConfigTest,RuntimeLifecycleSchemaInitializerTest,MySqlRunLifecycleStoreIT test
  - failed at test compile because RuntimeLifecycleStoreConfig and
    MySqlRunLifecycleStore did not exist
Verification (2026-06-25, Asia/Shanghai):
  - config/schema initializer gate:
    mvn -q -Dtest=RuntimeLifecycleStoreConfigTest,RuntimeLifecycleSchemaInitializerTest test
    4 tests, 0 failures, 0 errors, 0 skipped
  - lifecycle regression gate:
    mvn -q -Dtest=InMemoryRunLifecycleStoreTest,RunCoordinatorTest,RuntimeLifecycleStoreConfigTest,RuntimeLifecycleSchemaInitializerTest test
    20 tests, 0 failures, 0 errors, 0 skipped
  - compile gate:
    mvn -q -DskipTests test
    passed
  - MySQL IT with default worktree env first failed because the isolated
    worktree did not load /Users/hanbingzheng/springclaw/.env.local and fell
    back to MYSQL_PASSWORD=root:
    mvn -q -Dtest=MySqlRunLifecycleStoreIT test
    Access denied for user 'root'@'localhost' (using password: YES)
  - MySQL IT gate:
    set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a;
    mvn -q -Dtest=MySqlRunLifecycleStoreIT test
    3 tests, 0 failures, 0 errors, 0 skipped
  - final Phase 3E gate:
    set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a;
    mvn -q -Dtest=InMemoryRunLifecycleStoreTest,RunCoordinatorTest,RuntimeLifecycleStoreConfigTest,RuntimeLifecycleSchemaInitializerTest,MySqlRunLifecycleStoreIT test
    23 tests, 0 failures, 0 errors, 0 skipped
Known limitations:
  - MySqlRunLifecycleStoreIT requires loading the main project .env.local from
    isolated worktrees unless MYSQL_USER/MYSQL_PASSWORD/MYSQL_DB are exported
  - this phase does not migrate existing in-memory runtime rows because no such
    durable rows existed before Phase 3E
  - this phase does not remove legacy runtime console tables; it only adds the
    canonical lifecycle authority tables
Rollback order:
  - set SPRINGCLAW_RUNTIME_LIFECYCLE_STORE=memory
  - or revert the Phase 3E branch commit
Next dependency:
  - request review/merge, then continue to the next legacy runtime retirement
    task once Phase 3E is accepted
```

## Update: Phase 3F legacy lifecycle name retirement

```text
Task: Phase 3F legacy lifecycle name retirement
Branch:
  - codex/unified-runtime-phase-3f-legacy-retirement
Design and plan:
  - docs/superpowers/plans/2026-06-26-unified-runtime-phase-3f-legacy-lifecycle-name-retirement.md
Modified paths:
  - src/main/java/com/springclaw/runtime/bridge/RunLifecycleBridge.java
  - src/main/java/com/springclaw/runtime/bridge/DefaultRunLifecycleBridge.java
  - src/main/java/com/springclaw/runtime/bridge/RunLifecycleObserver.java
  - src/main/java/com/springclaw/runtime/bridge/LegacyRuntimeBridge.java
  - src/main/java/com/springclaw/runtime/bridge/DefaultLegacyRuntimeBridge.java
  - src/main/java/com/springclaw/runtime/bridge/LegacyLifecycleObserver.java
  - src/main/java/com/springclaw/controller/ChatController.java
  - src/main/java/com/springclaw/service/webhook/WebhookRouterService.java
  - src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java
  - src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java
  - src/main/java/com/springclaw/service/chat/impl/BasicStreamEngine.java
  - src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java
  - src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java
  - src/main/java/com/springclaw/service/proposal/ToolProposalExecutionService.java
  - src/main/java/com/springclaw/service/proposal/ToolProposalLifecycleListener.java
  - src/main/java/com/springclaw/scheduled/ToolProposalCleanupTask.java
Runtime path tightened:
  - production code now injects RunLifecycleBridge and RunLifecycleObserver
    instead of LegacyRuntimeBridge and LegacyLifecycleObserver
  - LegacyRuntimeBridge, DefaultLegacyRuntimeBridge, and
    LegacyLifecycleObserver remain as deprecated compatibility shims
  - no rollback/default context or advisor components were deleted
  - an architecture test now prevents production code outside runtime/bridge
    from importing the retired legacy lifecycle names
RED evidence:
  - mvn -q -Dtest=LegacyLifecycleNameQuarantineTest test
    failed while production callers still imported LegacyRuntimeBridge and
    LegacyLifecycleObserver
  - mvn -q -Dtest=RunLifecycleBridgeTest,RunLifecycleObserverTest test
    failed because the canonical bridge and observer did not exist yet
Verification (2026-06-26, Asia/Shanghai):
  - lifecycle name quarantine + compatibility gate:
    mvn -q -Dtest=LegacyLifecycleNameQuarantineTest,RunLifecycleBridgeTest,RunLifecycleObserverTest,LegacyRuntimeBridgeTest,LegacyLifecycleObserverTest,LegacyLifecycleObserverCanonicalModeTest,ChatControllerAuthTest,WebhookRouterServiceTest,TaskExecutionServiceTest,ChatServiceImplLifecycleProjectionTest,ToolProposalLifecycleListenerTest,ToolProposalCleanupTaskTest,ToolProposalExecutionServiceTest test
    passed
  - canonical smoke + MySQL lifecycle gate:
    set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a;
    mvn -q -Dtest=CanonicalRetrievalBoundaryTest,ConversationAdvisorSupportCanonicalModeTest,ChatControllerCanonicalHttpSmokeTest,ChatControllerSpringBootCanonicalSmokeIT,MySqlRunLifecycleStoreIT test
    passed
Known limitations:
  - legacy lifecycle compatibility tests still instantiate the deprecated
    classes intentionally to prove older code paths remain source-compatible
  - this phase does not delete ContextAssembler, SemanticMemoryAdvisor,
    MessageChatMemoryAdvisor, LegacyRunContextAdapter, or LegacyContextViewAdapter
Rollback order:
  - revert the Phase 3F commit to restore LegacyRuntimeBridge and
    LegacyLifecycleObserver as production-facing injection names
Next dependency:
  - review and merge Phase 3F, then decide whether the next safe slice is
    endpoint/API contract hardening or a deeper rollback-component deletion plan
```

## Update: Phase 3G API contract hardening

```text
Task: Phase 3G API contract hardening
Branch:
  - codex/unified-runtime-phase-3g-api-contract
Design and plan:
  - docs/superpowers/plans/2026-06-26-unified-runtime-phase-3g-api-contract-hardening.md
Modified paths:
  - src/main/java/com/springclaw/dto/chat/ChatResponse.java
  - src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java
  - src/test/java/com/springclaw/controller/ChatControllerAuthTest.java
  - src/test/java/com/springclaw/service/chat/impl/ChatServiceImplLifecycleProjectionTest.java
  - src/test/java/com/springclaw/service/chat/impl/ChatControllerCanonicalHttpSmokeTest.java
  - src/test/java/com/springclaw/service/chat/async/ChatMessageConsumerTest.java
  - src/test/java/com/springclaw/service/webhook/WebhookRouterServiceTest.java
  - src/test/java/com/springclaw/architecture/CanonicalTransportIdentityTest.java
  - src/test/java/com/springclaw/architecture/TransportParityCharacterizationTest.java
Runtime path tightened:
  - synchronous chat responses now expose data.requestId equal to the accepted
    canonical run id
  - ChatServiceImpl propagates AcceptedChatCommand.runId into ChatResponse
  - async/result/trace/proposal APIs remain on the same requestId correlation
    model
  - response change is additive: sessionKey, answer, model, and timestamp remain
RED evidence:
  - mvn -q -Dtest=ChatControllerAuthTest#syncAndStreamCreateCanonicalRunsBeforeLegacyExecution test
  - failed at test compile because ChatResponse had no requestId accessor and
    no five-argument constructor
Verification (2026-06-26, Asia/Shanghai):
  - sync controller identity contract:
    mvn -q -Dtest=ChatControllerAuthTest#syncAndStreamCreateCanonicalRunsBeforeLegacyExecution test
    passed
  - service lifecycle identity contract:
    mvn -q -Dtest=ChatServiceImplLifecycleProjectionTest test
    passed
  - HTTP contract smoke:
    set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a;
    mvn -q -Dtest=ChatControllerCanonicalHttpSmokeTest test
    passed
  - API contract gate:
    mvn -q -Dtest=ChatControllerAuthTest,ChatServiceImplLifecycleProjectionTest,ChatMessageConsumerTest,WebhookControllerTest,ToolProposalControllerTest,RuntimeConsoleControllerTest test
    passed
  - review fix: public DTO shape architecture gate:
    mvn -q -Dtest=CanonicalTransportIdentityTest test
    passed
  - expanded API contract gate after review fix:
    mvn -q -Dtest=CanonicalTransportIdentityTest,TransportParityCharacterizationTest,ChatControllerAuthTest,ChatServiceImplLifecycleProjectionTest,ChatMessageConsumerTest,WebhookControllerTest,ToolProposalControllerTest,RuntimeConsoleControllerTest test
    passed
  - canonical HTTP/MySQL smoke:
    set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a;
    mvn -q -Dtest=ChatControllerCanonicalHttpSmokeTest,ChatControllerSpringBootCanonicalSmokeIT,MySqlRunLifecycleStoreIT test
    passed
  - compile gate:
    mvn -q -DskipTests test
    passed
Known limitations:
  - SSE event payloads already include requestId in SseEventBridge but were not
    redesigned in this phase
  - this phase does not delete rollback/memory/lifecycle components
Rollback order:
  - revert the Phase 3G commit to remove the additive ChatResponse.requestId
    field and restore the previous four-field ChatResponse constructor
Next dependency:
  - request review/merge, then choose between SSE event contract hardening and
    rollback-component deletion analysis
```

## Update: Phase 3H canonical trace read

```text
Task: Phase 3H canonical trace read
Branch:
  - codex/unified-runtime-phase-3h-canonical-trace-read
Design and plan:
  - docs/superpowers/plans/2026-06-26-unified-runtime-phase-3h-canonical-trace-read.md
Modified paths:
  - src/main/java/com/springclaw/service/agent/AgentRunTraceService.java
  - src/test/java/com/springclaw/service/agent/AgentRunTraceServiceTest.java
Runtime path tightened:
  - /api/chat/runs/{requestId}/trace now prefers canonical RunEventStore
    events through AgentRunTraceService.listTrace()
  - canonical RunEvent values are projected into the existing public
    AgentRunTraceEvent response shape
  - legacy message_event SYSTEM/TRACE rows remain the fallback when canonical
    events are absent
  - user isolation is preserved: if canonical RunState exists and the requested
    userId does not match, the trace read returns empty and does not fall back
    to legacy message_event rows
RED evidence:
  - mvn -q -Dtest=AgentRunTraceServiceTest#listTracePrefersCanonicalRunEventsOverLegacyTraceRows test
  - failed because listTrace() returned no canonical events and still relied on
    legacy message_event rows
Verification (2026-06-26, Asia/Shanghai):
  - trace service gate:
    mvn -q -Dtest=AgentRunTraceServiceTest test
    passed
  - focused Phase 3H gate:
    mvn -q -Dtest=AgentRunTraceServiceTest,ChatControllerAuthTest,ChatControllerCanonicalHttpSmokeTest test
    passed
  - compile gate:
    mvn -q -DskipTests test
    passed
Known limitations:
  - admin replay and runtime-console runs still read legacy structured tables
  - this phase does not delete message_event trace fallback or legacy structured
    trace tables
Rollback order:
  - revert the Phase 3H commit to restore listTrace() to legacy-only reads
Next dependency:
  - request review/merge, then migrate runtime-console runs or admin replay in
    another small slice
```

## Update: Phase 3I canonical runs list read

```text
Task: Phase 3I canonical runs list read
Branch:
  - codex/unified-runtime-phase-3i-canonical-runs-list
Design and plan:
  - docs/superpowers/plans/2026-06-26-unified-runtime-phase-3i-canonical-runs-list.md
Modified paths:
  - src/main/java/com/springclaw/runtime/lifecycle/RunStateRepository.java
  - src/main/java/com/springclaw/runtime/lifecycle/InMemoryRunLifecycleStore.java
  - src/main/java/com/springclaw/runtime/lifecycle/MySqlRunLifecycleStore.java
  - src/main/java/com/springclaw/service/agent/AgentRunTraceService.java
  - src/test/java/com/springclaw/runtime/lifecycle/InMemoryRunLifecycleStoreTest.java
  - src/test/java/com/springclaw/runtime/lifecycle/MySqlRunLifecycleStoreIT.java
  - src/test/java/com/springclaw/service/agent/AgentRunTraceServiceTest.java
Runtime path tightened:
  - /api/runtime-console/runs and the runtime-console overview now prefer
    canonical RunState rows through AgentRunTraceService.recentRuns()
  - canonical rows are projected into the existing public map shape:
    requestId, sessionKey, userId, lastStep, status, detail, timestamp,
    channel, and responseMode
  - latest canonical RunEvent supplies lastStep/detail/timestamp when present
  - legacy message_event SYSTEM/TRACE rows remain the fallback when no
    canonical rows are available for the requested user
  - user filtering is applied before returning canonical rows; the read path
    fetches a bounded canonical window before filtering so another user's newer
    runs do not incorrectly hide the current user's runs
RED evidence:
  - mvn -q -Dtest=AgentRunTraceServiceTest#recentRunsPrefersCanonicalRunStatesOverLegacyTraceRows test
  - failed because recentRuns() still queried legacy message_event rows first
Verification (2026-06-26, Asia/Shanghai):
  - trace service gate:
    mvn -q -Dtest=AgentRunTraceServiceTest test
    passed
  - in-memory lifecycle recent query gate:
    mvn -q -Dtest=InMemoryRunLifecycleStoreTest test
    passed
  - runtime-console controller regression:
    mvn -q -Dtest=RuntimeConsoleControllerTest test
    passed
  - MySQL lifecycle recent query gate:
    set -a; . /Users/hanbingzheng/springclaw/.env.local; set +a;
    mvn -q -Dtest=MySqlRunLifecycleStoreIT test
    passed
  - focused Phase 3I gate:
    mvn -q -Dtest=AgentRunTraceServiceTest,RuntimeConsoleControllerTest,InMemoryRunLifecycleStoreTest test
    passed
  - compile gate:
    mvn -q -DskipTests test
    passed
Known limitations:
  - admin replay still reads legacy structured trace/run tables
  - legacy message_event trace rows and structured agent_run tables are not
    deleted in this phase
  - the canonical recent query is intentionally bounded to 500 rows for this
    small read-path slice; deeper paging/search can be designed separately
Rollback order:
  - revert the Phase 3I commit to restore runtime-console run lists to
    legacy-only message_event reads
Next dependency:
  - request review/merge, then migrate admin replay or plan the next rollback
    component retirement slice
```

## Update: Phase 3J canonical admin replay read

```text
Task: Phase 3J canonical admin replay read
Branch:
  - codex/unified-runtime-phase-3j-canonical-replay
Design and plan:
  - docs/superpowers/plans/2026-06-27-unified-runtime-phase-3j-canonical-admin-replay.md
Modified paths:
  - src/main/java/com/springclaw/service/agent/AgentRunTraceService.java
  - src/test/java/com/springclaw/service/agent/AgentRunTraceServiceTest.java
Runtime path tightened:
  - /api/admin/manage/runs/{requestId}/replay now prefers canonical
    RunState + RunEvent data through AgentRunTraceService.replayRun()
  - canonical replay returns the existing broad replay map contract with
    request_id, session_key, channel, user_id, response_mode, status,
    started_at, finished_at, duration_ms, error_message, steps, and
    toolInvocations
  - canonical replay rows include source=canonical to make provenance explicit
  - legacy agent_run / agent_run_step / tool_invocation SQL replay remains the
    fallback when canonical state is absent
  - controller 404 behavior remains unchanged because empty service result still
    means Run not found
RED evidence:
  - mvn -q -Dtest=AgentRunTraceServiceTest#replayRunPrefersCanonicalLifecycleOverLegacyStructuredTables test
  - first failed as expected because replayRun() returned an empty legacy result
    for a canonical-only run instead of projecting canonical lifecycle data
Verification (2026-06-27, Asia/Shanghai):
  - focused canonical replay gate:
    mvn -q -Dtest=AgentRunTraceServiceTest#replayRunPrefersCanonicalLifecycleOverLegacyStructuredTables test
    passed
  - trace service gate:
    mvn -q -Dtest=AgentRunTraceServiceTest test
    passed
  - legacy replay contract fallback:
    mvn -q -Dtest=TurnContractTest test
    passed
  - focused Phase 3J gate:
    mvn -q -Dtest=AgentRunTraceServiceTest,TurnContractTest,RuntimeConsoleControllerTest test
    passed
  - compile gate:
    mvn -q -DskipTests test
    passed
Known limitations:
  - legacy structured runtime tables are still written and retained for
    fallback compatibility
  - canonical replay is a read projection only; no schema or migration changes
  - canonical replay projects toolInvocations from RunState.toolInvocations();
    the current RunCoordinator records tool lifecycle as RunEvent rows but does
    not yet populate RunState.toolInvocations(), so canonical replay exposes
    tool activity through steps while toolInvocations remains empty
  - replay remains admin-only at the controller layer and does not add per-user
    filtering
Rollback order:
  - revert the Phase 3J commit to restore admin replay to legacy-only
    structured-table reads
Next dependency:
  - request review/merge, then the remaining work is rollback/legacy component
    retirement planning rather than another obvious external read-path migration
```

## Update: Phase 3K legacy / rollback retirement audit

```text
Task: Phase 3K legacy / rollback retirement audit
Branch:
  - codex/unified-runtime-phase-3k-legacy-retirement-audit
Design and plan:
  - docs/superpowers/plans/2026-06-27-unified-runtime-phase-3k-legacy-retirement-audit.md
Modified paths:
  - docs/superpowers/plans/2026-06-27-unified-runtime-phase-3k-legacy-retirement-audit.md
  - docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md
Runtime path tightened:
  - documentation-only audit; no production code changed
  - classified remaining legacy/rollback/runtime trace components after PR #1-#13
  - identified the first safe deletion slice as deprecated lifecycle name shims:
    LegacyRuntimeBridge, DefaultLegacyRuntimeBridge, and LegacyLifecycleObserver
  - confirmed ContextAssembler, SemanticMemoryAdvisor, MessageChatMemoryAdvisor,
    LegacyRunContextAdapter, LegacyContextViewAdapter, AssembledContext, and
    ContextInjection are not safe deletion targets yet
  - confirmed message_event and structured agent_run / agent_run_step /
    tool_invocation tables remain product/fallback data, not deletion targets
Verification (2026-06-27, Asia/Shanghai):
  - baseline compile gate:
    mvn -q -DskipTests test
    passed
  - production/test reference scans:
    rg legacy/context/memory/trace/replay patterns across src/main/java,
    src/test/java, docs/superpowers/plans, and docs
    completed
Key decision:
  - next implementation phase should be Phase 3K1: delete deprecated lifecycle
    name shims only
  - do not delete rollback context/memory components until rollback mode is
    formally retired
  - do not delete structured trace tables until canonical ToolInvocation details
    and historical data policy are resolved
Known limitations:
  - this phase did not modify or remove any production class
  - audit uses static references plus current tests; runtime production metrics
    should still be checked before removing rollback flags in a later release
Rollback order:
  - revert this documentation-only commit if the audit classification needs to
    be rewritten
Next dependency:
  - request review/merge, then implement Phase 3K1 as a small deletion PR
```
