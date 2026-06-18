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
| Production implementation authorized | No |

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

- [ ] **C0.4 — Create Claude characterization worktree**
  - Owner: Claude
  - Base: `36ca396`
  - Branch: `claude/runtime-characterization`
  - Required result:

    ```text
    clean worktree
    no production Java changes
    ```

## 5. Phase 1 — Parallel Architecture Discovery

Phase 1 work may run in parallel because Codex writes the architecture spec while Claude writes audit documentation and characterization tests.

### Codex workstream A — Canonical Runtime Design

- [ ] **A1 — Map the four complete request lifecycles**
  - Owner: Codex
  - Cover:
    - synchronous chat
    - SSE streaming chat
    - RabbitMQ asynchronous chat
    - proposal confirmation and resumed execution
  - Required output: lifecycle diagrams and exact owner classes in the new spec.

- [ ] **A2 — Build the responsibility source-of-truth matrix**
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

- [ ] **A3 — Define canonical domain contracts**
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

- [ ] **A4 — Define the Run state machine**
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

- [ ] **A5 — Define incremental migration and rollback**
  - Owner: Codex
  - Acceptance:
    - initial runtime delegates through compatibility adapters;
    - one responsibility migrates per phase;
    - old owner is disabled in the same phase;
    - rollback unit and compatibility acceptance are defined for every phase.

- [ ] **A6 — Write and self-review the architecture spec**
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

### Claude workstream B — Characterization and Evidence

- [ ] **B1 — Audit current responsibility ownership**
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

- [ ] **B2 — Characterize routing**
  - Owner: Claude
  - Create:

    ```text
    src/test/java/com/springclaw/architecture/RuntimeRouteCharacterizationTest.java
    ```

  - Tests must record current behavior without changing production code.

- [ ] **B3 — Characterize context propagation**
  - Owner: Claude
  - Create:

    ```text
    src/test/java/com/springclaw/architecture/ContextPropagationCharacterizationTest.java
    ```

  - Acceptance: asserts which paths receive `ContextInjection`, ChatMemory Advisor, semantic memory, and Memory Bank content.

- [ ] **B4 — Characterize tool safety**
  - Owner: Claude
  - Create:

    ```text
    src/test/java/com/springclaw/architecture/ToolSafetyPathCharacterizationTest.java
    ```

  - Acceptance: asserts write calls cannot bypass `ToolRuntimeAspect` and proposal confirmation.

- [ ] **B5 — Characterize final-answer ownership**
  - Owner: Claude
  - Create:

    ```text
    src/test/java/com/springclaw/architecture/FinalAnswerOwnershipCharacterizationTest.java
    ```

  - Acceptance: records every path that composes, repairs, narrates, or replaces the final answer.

- [ ] **B6 — Characterize transport parity**
  - Owner: Claude
  - Create:

    ```text
    src/test/java/com/springclaw/architecture/TransportParityCharacterizationTest.java
    ```

  - Acceptance: records sync/SSE/async differences in result, persistence, trace, and completion behavior.

- [ ] **B7 — Commit Phase 1 evidence**
  - Owner: Claude
  - Required commits:

    ```text
    docs: audit current runtime responsibility ownership
    test: characterize runtime routing and context propagation
    test: characterize safety completion and transport parity
    ```

  - Claude reports SHAs; Codex records them in this ledger.

## 6. Phase 1 Review Gate

No implementation plan may be written until all checks pass:

- [ ] Codex spec includes the four lifecycle maps.
- [ ] Codex spec defines one owner per runtime responsibility.
- [ ] Claude audit and characterization tests are available for review.
- [ ] P0 invariants map to explicit target-runtime enforcement points.
- [ ] The design does not assume a fixed engine count.
- [ ] The user approves the new spec.

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
