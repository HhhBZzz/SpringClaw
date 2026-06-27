# Memory B Product Differentiation Roadmap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adjust the memory roadmap from engineering stability only to product differentiation: SpringClaw should visibly understand user preferences, stable facts, prior decisions, and run outcomes across sessions.

**Architecture:** R1 remains valid as the stability foundation. The B-target roadmap inserts R3.5 for semantic write L1 and terminal reflection L2 before engine prompt migration, then adds R6 for memory effectiveness redlines and consolidation L3. All memory writes remain asynchronous, evidence-grounded, reviewable, and backed by MySQL `memory_record` authority.

**Tech Stack:** Java 17, Spring Boot, MySQL 8 `memory_record`, Redis short-term memory, Redis VectorStore, Spring AI `ChatClient`, existing `AiProviderService` providers (`coding-plan`, `primary`, `deepseek`), JUnit 5, Runtime Console.

---

## 1. Revised roadmap

The previous R1-R5 roadmap answered "how memory avoids breaking the runtime." The B target adds "how memory makes the agent better."

```text
R1  Architecture consolidation audit                 [merged]
R2  Redis short-term default activation               [engineering stability]
R3  memory_record authority + retire storeConversationTurn authority behavior
R3.5 semantic write L1 + terminal reflection L2       [product differentiation]
R4  engine prompt rendering migration
R5  controlled Knowledge Source injection
R6  effectiveness redlines + consolidation L3         [product differentiation]
```

R3.5 is required after R3 because R3 intentionally retires direct `VectorMemoryService.storeConversationTurn(...)` authority behavior. Without R3.5, the semantic index can become safer but less useful: fewer writes, fewer remembered preferences, and no run-level learning.

---

## 2. R3.5 scope: semantic write L1

### 2.1 Trigger and timing

R3.5 semantic extraction must run after terminal persistence, not in the first-token or first-response path.

```text
ChatResultPersister persists terminal result
  -> message_event user/assistant rows are durable
  -> run reaches terminal state
  -> asynchronous MemoryExtractionJob is queued
  -> extractor reads source run/event evidence
  -> writes memory_record CANDIDATE or ACTIVE
```

Hard rule:

- extraction failure must not fail the chat request;
- extraction timeout must not delay SSE completion;
- extracted memories must reference source evidence.

### 2.2 Provider decision

Use provider-specific configuration, not the mutable active chat provider:

```yaml
springclaw:
  memory:
    semantic-extraction:
      enabled: false
      extractor-provider-id: coding-plan
      judge-provider-id: primary
      fallback-provider-id: deepseek
      timeout-seconds: 20
      max-source-events: 12
      auto-active-importance-threshold: 0.75
      auto-active-confidence-threshold: 0.82
```

Decision:

- extractor provider: `coding-plan`
- judge provider: `primary`
- fallback provider: `deepseek`

Reasoning:

- `coding-plan` is already configured as a low-temperature structured provider and is the best default for JSON extraction.
- `primary` is used for judge because it should be stricter than the extractor for memory that can affect future behavior.
- `deepseek` is fallback only, not the default, to avoid making memory quality depend on a third provider unless the first two are unavailable.

If cost becomes a problem, R3.5 can judge only borderline candidates:

```text
confidence >= 0.90 and evidence strong -> skip judge in later optimization
0.60 <= confidence < 0.90 -> judge required
confidence < 0.60 -> reject/no-write
```

Do not implement that optimization in the first R3.5 slice.

### 2.3 Extraction schema

The extractor must return strict JSON only:

```json
{
  "schema": "springclaw.semantic-memory-extraction.v1",
  "candidates": [
    {
      "kind": "USER_PREFERENCE",
      "content": "User prefers short Chinese progress summaries.",
      "subject": "user",
      "scopeType": "PERSONAL_USER",
      "importance": 0.82,
      "confidence": 0.88,
      "sourceEventKeys": ["chat:req-1:user", "chat:req-1:assistant:terminal"],
      "sourceRunId": "req-1",
      "reason": "The user explicitly asked for concise Chinese progress updates.",
      "hypothetical": false
    }
  ]
}
```

Allowed `kind` values:

```text
USER_PREFERENCE
TECH_STACK
REPORTING_RELATIONSHIP
HISTORICAL_DECISION
WORKFLOW_PREFERENCE
NEGATIVE_PREFERENCE
```

Do not extract:

- hypothetical statements: "I might...", "maybe later...", "if I ever...";
- one-off task details that do not generalize;
- assistant guesses without user confirmation;
- secrets, passwords, API keys, private tokens;
- ungrounded inferred personality traits.

### 2.4 Memory write policy

Every write must go through `MemoryManagementService`.

```text
semantic candidate
  -> MemoryWriteCommand
  -> memoryType = SEMANTIC
  -> requestedStatus = CANDIDATE or ACTIVE
  -> evidence_refs_json includes source event keys and run id
  -> source_kind = MESSAGE_EVENT
  -> source_identity = runId + ":" + eventKey set hash
  -> extraction_policy_version = semantic-extraction-v1
```

Promotion rule:

```text
importance >= auto-active-importance-threshold
AND confidence >= auto-active-confidence-threshold
AND evidence_refs is non-empty
AND judge verdict = ACCEPT
  -> ACTIVE
else
  -> CANDIDATE
```

Idempotency rule:

- use source run/event identity, not candidate text;
- repeated extraction of the same event set must return the existing automatic-source memory or no-op.

### 2.5 Judge policy

The judge prompt must answer:

```json
{
  "schema": "springclaw.semantic-memory-judge.v1",
  "verdict": "ACCEPT",
  "confidence": 0.91,
  "evidenceGrounded": true,
  "hypothetical": false,
  "sensitive": false,
  "reason": "The statement is directly supported by the user's explicit request."
}
```

Allowed verdicts:

```text
ACCEPT
DOWNGRADE_TO_CANDIDATE
REJECT
```

If JSON parsing fails after one retry, do not write memory. Record a trace/audit event only.

---

## 3. R3.5 scope: terminal reflection L2

### 3.1 Trigger and timing

Terminal reflection is a separate asynchronous job after run completion.

```text
run terminal event
  -> MemoryReflectionJob
  -> reads RunState + RunEvent + selected trace evidence
  -> proposes one EPISODIC meta-knowledge candidate
  -> writes memory_record CANDIDATE by default
```

Terminal reflection is not the same as semantic preference extraction:

- semantic extraction remembers stable user facts/preferences;
- reflection remembers run outcome lessons.

### 3.2 Reflection schema

```json
{
  "schema": "springclaw.terminal-reflection.v1",
  "outcome": "SUCCESS",
  "lesson": "When the task is PR review handoff, first verify PR state before planning the next phase.",
  "applicability": "future GitHub PR handoff tasks",
  "failureMode": "",
  "evidenceRefs": [
    "run:req-1",
    "event:req-1:CONTEXT_READY",
    "event:req-1:DECISION_MADE"
  ],
  "confidence": 0.78
}
```

Write target:

```text
memoryType = EPISODIC
requestedStatus = CANDIDATE
source_kind = RUN_REFLECTION
source_identity = runId
extraction_policy_version = terminal-reflection-v1
```

Do not auto-activate all reflections. R3.5 may auto-activate only when:

```text
confidence >= 0.90
AND evidenceRefs non-empty
AND judge verdict = ACCEPT
AND reflection is procedural enough to be reusable
```

The safer first implementation is CANDIDATE-only for all reflections.

### 3.3 Reflection grounding

Every reflection must cite evidence. If evidence cannot be found, no memory write occurs.

This prevents self-reinforcing false lessons:

```text
bad: "The agent is good at deployment tasks."
good: "In run req-1, deployment failed because MySQL credentials were unavailable; ask for env variables before rerunning DB integration tests."
```

Subtask-level MUSE-style reflection is out of scope for R3.5 and belongs in R6.

---

## 4. R3.5 review queue and UI decision

Decision: R3.5 includes backend review queue and a minimal Runtime Console review UI. Do not defer all UI to a later phase.

Reason:

- R3.5 intentionally creates low-confidence `CANDIDATE` memories.
- Without a review surface, those candidates become invisible backlog and the product value is delayed.
- Existing Runtime Console already has Learning review patterns that can be reused.

R3.5 minimum review scope:

```text
Backend:
  GET  /api/runtime-console/memory/candidates?status=CANDIDATE&limit=...
  POST /api/runtime-console/memory/candidates/status

Frontend:
  Runtime Console -> Memory Candidates panel
  show content, type, confidence, importance, evidence refs, judge reason
  actions: approve(active), reject, disable, supersede
```

The frontend can be visually simple. It must exist before low-confidence candidates are produced in real runs.

If implementation capacity is tight, split R3.5 into:

```text
R3.5a backend extraction + backend review API + no default enablement
R3.5b minimal Runtime Console UI + enablement flag
```

Do not enable extraction by default until R3.5b exists.

---

## 5. R6 scope: effectiveness redlines and consolidation L3

### 5.1 Evaluation harness decision

Use two layers:

```text
Layer 1: deterministic JUnit redline fixtures
Layer 2: independent memory evaluation harness for real provider runs
```

Layer 1 must run in normal CI because it should not require model API keys:

- service-level memory write policy;
- idempotency by source event identity;
- conflict replacement;
- selective forgetting;
- irrelevant memory rejection;
- budget saturation behavior.

Layer 2 runs manually or nightly with provider credentials:

- actual extractor JSON quality;
- judge false positive/false negative rate;
- cross-session preference recall;
- whether injected memory was actually used by the answer.

### 5.2 R6 redline list

R6 promotes the R1 spec §16 evaluation ideas into required gates. The redline suite must include:

1. Cross-session user preference recall.
2. Conflict fact replacement.
3. Irrelevant memory rejection.
4. Selective forgetting / disabled memory exclusion.
5. Token budget saturation.
6. Source evidence preservation.
7. Hypothetical statement rejection.
8. Sensitive data non-write.
9. Stale vector hit rejection.
10. Injected-memory usage trace.

R3.5 should already introduce the first two partial fixtures:

```text
preference recall fixture
conflict replacement fixture
```

### 5.3 Injected-memory usage trace

Add a proxy metric:

```text
memoryInjected = true/false
memoryReferencedInAnswer = true/false
memoryReferenceKind = explicit|paraphrase|none
memoryUseJudgedBy = deterministic|llm-judge
```

This is not perfect causality, but it is enough to detect useless recall.

### 5.4 Consolidation L3

R6 introduces consolidation:

```text
multiple EPISODIC candidates
  -> cluster by user/task/topic/evidence
  -> propose one SEMANTIC or PROCEDURAL_RULE memory
  -> CANDIDATE
  -> review
  -> ACTIVE only after review or high-confidence policy
```

Do not implement consolidation in R3.5.

---

## 6. Updated implementation order

### R2 remains first

R2 still activates Redis short-term as canonical hot context. The TDD anchor must be changed from "MemoryCoordinator reads short-term" to "default activation + recovery behavior":

```text
RED:
  canonical Spring context should expose ShortTermMemoryStore by default
  and should not fail when Redis append fails

GREEN:
  enable Redis short-term canonical wiring behind explicit safe defaults
  keep MySQL message_event as recovery source
```

### R3 remains second

R3 still makes `memory_record` the authority and retires direct `storeConversationTurn` authority behavior.

### R3.5 becomes mandatory before R4/R5

R3.5 fills the semantic write vacuum created by R3.

Do not do R4 engine rendering migration before R3.5 unless a separate product decision explicitly accepts that semantic memory write value is deferred.

---

## 7. Direct answers to the three review questions

### Q1: Which provider and when?

Use:

```text
extractor-provider-id = coding-plan
judge-provider-id = primary
fallback-provider-id = deepseek
```

Call timing:

```text
terminal async only
after message_event and run lifecycle terminal state are durable
never in first-token path
never blocking SSE completion
```

### Q2: Is review UI included in R3.5?

Yes, minimally.

R3.5 must include:

- backend candidate list/status API;
- minimal Runtime Console memory candidate panel;
- extraction disabled-by-default until review UI exists.

If needed, split into R3.5a backend and R3.5b UI, but do not enable production extraction after only R3.5a.

### Q3: Where does R6 evaluation run?

Use both:

- deterministic JUnit fixtures as CI redlines;
- independent harness for real provider evaluation.

R3.5 starts with JUnit fixtures for preference recall and conflict replacement. R6 expands the full redline suite and adds the provider-backed harness.

---

## 8. Do-not-do list remains unchanged

The following remain hard boundaries:

- do not delete MySQL;
- Redis is not the authority;
- Knowledge Source does not enter user memory;
- Agent Learning and memory candidates remain governed;
- canonical mode keeps `MessageChatMemoryAdvisor` quarantined;
- do not delete `ContextAssembler` until engine migration is complete;
- do not merge memory effectiveness work with trace double-write retirement.

---

## 9. Acceptance criteria for this roadmap amendment

- R1 remains valid and merge-safe.
- R2/R3 remain engineering prerequisites.
- R3.5 is explicitly inserted before R4/R5.
- R3.5 defines provider choice, async timing, schema, review policy, and minimal UI requirement.
- R6 defines evaluation redlines, JUnit versus harness split, and consolidation L3 scope.
- No production Java, schema, or config behavior changes are made in this amendment.

---

## 10. Verification commands for this documentation phase

Run:

```bash
rg -n "R3\\.5|R6|coding-plan|judge-provider-id|Memory Candidates|redline|consolidation" docs/superpowers/plans/2026-06-28-memory-b-product-differentiation-roadmap.md
rg -n "Memory B|R3\\.5|R6" docs/superpowers/plans/2026-06-18-unified-agent-runtime-collaboration.md docs/superpowers/plans/2026-06-28-memory-r1-architecture-consolidation.md
git diff --check
```

Expected:

- first command finds the B-target roadmap decisions;
- second command finds the amended route from the ledger/R1 plan;
- `git diff --check` passes.
