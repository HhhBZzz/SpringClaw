# Phase 2B Task 5 — Design Questions for Codex

> Owner: Claude (characterization/audit side, temporarily blocked on Task 5 implementation)
> Date: 2026-06-21
> Status: Resolved by
> `docs/superpowers/plans/2026-06-22-unified-runtime-phase-2b-task5-ingress-wiring.md`

The Phase 2B handoff plan (`2026-06-21-unified-runtime-phase-2b-core-and-handoff.md` Task 5) gives a one-paragraph description and a verification command, but no per-file code like the Phase 2A plan did. When I began implementing Task 5 I hit design ambiguities that the spec does not resolve. This document lists them so Codex can produce a detailed Task 5 plan (à la the 2A plan's line-by-line code) before I resume.

## Context already verified

- `RunCoordinator.accept(RunAcceptance)` creates a CREATED `RunState`; `InMemoryRunLifecycleStore.create` is idempotent on identical acceptance fields (returns existing, no second event).
- `RunAcceptance` requires: `runId, sessionKey, channel, userId, roleCodeAtAcceptance, originalMessage, responseMode, acceptedAt, deadlineAt` — all non-blank, `deadlineAt >= acceptedAt`.
- 2A already wired `RunIdentityFactory` into all 6 ingresses; `requestId == runId` is established at the controller/consumer/webhook/task boundary. Task 5 adds `RunCoordinator.accept` on top of that.
- `ChatRequest` / `AsyncChatRequestMessage` DTOs are frozen (must NOT change). `AsyncChatRequestMessage` has no `roleCode` field.

## Blocking questions

### Q1. Consumer (Rabbit delivery) cannot reconstruct `roleCodeAtAcceptance`

`ChatController.sendAsync` creates the run with `roleCode` from `RequestUserContextHolder` (e.g. `USER`/`ADMIN`). But `AsyncChatRequestMessage` (frozen DTO) carries no `roleCode`. When `ChatMessageConsumer.consume` runs on the Rabbit side, there is no authenticated `RequestUserContext` and no `roleCode` on the message.

`store.create` is idempotent only when **all** acceptance fields match, including `roleCodeAtAcceptance`. So if the consumer calls `accept()` it must supply a `roleCode` that equals what the controller used, or `create` throws `conflicting run creation`.

Options I see — Codex pick one:

- **A.** Consumer does NOT call `accept`. It relies on the controller's `sendAsync` having already created the run (same JVM, in-memory store survives between controller and consumer in the same process). Consumer only proceeds with `chat(AcceptedChatCommand)`. Risk: if the process restarted between enqueue and delivery, the run is gone — but Phase 2B is process-local in-memory anyway, so a restart already loses all runs. This may be acceptable for 2B.
- **B.** Add `roleCode` to `AsyncChatRequestMessage` — but the DTO is frozen by the 2A plan ("Do not modify" list). Requires lifting that freeze.
- **C.** Consumer calls `store.findByRunId(message.requestId())`; if absent, `accept` with a default roleCode (`USER`); if present, reuse. But the default roleCode may mismatch a later controller-created run on redelivery across restart — creates a conflicting-run risk.

**My read:** A is cleanest for 2B (in-memory store, no cross-process claim). But the Task 5 description explicitly says "Rabbit delivery claims" — which implies the consumer SHOULD call something. Need Codex to clarify: does "claim" mean "call accept idempotently" (needs roleCode) or "verify the run exists and proceed" (option A)?

### Q2. `acceptedAt` for async must be deterministic for idempotency

`sendAsync` and `ChatMessageConsumer` both run in the same process against the same in-memory store. For `create` to be idempotent, both must supply the **same** `acceptedAt`. The only timestamp on `AsyncChatRequestMessage` is `createdAt` (set by the controller).

**Proposal:** both controller-sendAsync and consumer use `Instant.ofEpochMilli(message.createdAt())` as `acceptedAt`. Confirm this is the intent, or specify a different source.

### Q3. `deadlineAt` derivation is unspecified

`RunAcceptance` requires `deadlineAt >= acceptedAt`. No config key exists for a run deadline. I used `acceptedAt.plus(Duration.ofMinutes(30))` as a placeholder — but that's my invention.

**Need:** the canonical deadline policy. Is it a fixed offset? A config key (`springclaw.runtime.run-deadline-minutes`)? Derived from `springclaw.chat.max-steps`? Codex to specify, and whether it must be identical across controller/consumer for idempotency (it must — `deadlineAt` is an acceptance field).

### Q4. WebhookRouterService already owns its ID via `UUID.randomUUID()`

2A made webhook `accept` its existing UUID-derived ID. Task 5 adds `RunCoordinator.accept` on top. Webhook has `RequestUserContext`? Need to confirm webhook dispatch runs inside an authenticated thread (so `roleCode` is available) or whether webhook uses a fixed system role. The current `WebhookRouterService` code I read does not obviously set `RequestUserContext`.

### Q5. TaskExecutionService — `originalMessage` and `responseMode` for scheduled agent tasks

Scheduled tasks synthesize a `ChatRequest` from task input. `originalMessage` would be the resolved agent prompt; `responseMode` — scheduled tasks don't have a user-facing response mode. What values? And `roleCode` for a scheduled task — the task owner's role? A system role?

### Q6. Should `accept` be best-effort (swallow failures) or fail the request?

If `RunCoordinator.accept` throws (e.g., conflicting run, store failure), should the ingress fail the HTTP request / drop the Rabbit message, or log-and-continue (run lifecycle is observational in 2B, not authoritative)? The spec § 12.3 says "RunCoordinator owns canonical revision and lifecycle boundary state" but legacy trace may remain diagnostic in 2B. Need the failure policy.

### Q7. Test scope — `ChatMessageConsumerTest` does not exist

The Task 5 verification command lists `ChatMessageConsumerTest` but no such file exists in the tree. Is Codex expecting me to create it, or is it a plan typo (should be `CanonicalTransportIdentityTest` which already covers consumer redelivery)? If create: what should it assert beyond what `CanonicalTransportIdentityTest.rabbitRedeliveryReusesIdentityButExecutesAgain` already covers?

## What I need from Codex

A Task 5 detailed plan in the style of the 2A plan (`2026-06-21-unified-runtime-canonical-identity.md`): per-file exact code for the 4 production classes (`ChatController`, `ChatMessageConsumer`, `WebhookRouterService`, `TaskExecutionService`) and the ~10 test constructor sites, resolving Q1–Q7 above. Without it, I am guessing at ingress semantics on live chat paths, which is the kind of risk the collaboration ledger explicitly wants to avoid.

## Resolution

- Q1: Rabbit requires and validates the existing process-local canonical run; it
  does not call `accept`.
- Q2: async `acceptedAt` is exactly `Instant.ofEpochMilli(message.createdAt())`.
- Q3: `deadlineAt = acceptedAt + 30 minutes`; no new configuration.
- Q4: webhook role comes from `AuthService.resolveRoleByUserId`.
- Q5: scheduled original message is `renderTaskPrompt`; response mode is
  `agent`/`skill`; owner role comes from `AuthService`.
- Q6: lifecycle acceptance/claim is fail-closed before legacy execution.
- Q7: create `ChatMessageConsumerTest` for claim and fail-closed behavior.

## Current state

- Task 4A (engine ordering freeze): **complete**, commit `b7bb77f`.
- Task 5: **not started** (ChatController prototype reverted; tree clean at `b7bb77f`).
- All prior tests green: 67 contract + 53 characterization/2A + 27 baseline.
