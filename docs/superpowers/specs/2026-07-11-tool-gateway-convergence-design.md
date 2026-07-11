# Tool Gateway Convergence Design

**Date:** 2026-07-11  
**Status:** Approved by the user's continuous-delivery authorization; implementation planning in progress  
**Branch:** `codex/tool-gateway-convergence`  
**Base:** `3a8dba9` (`codex/flyway-schema-migration`)

## Objective

Make the durable `ToolInvocationProposal` flow the single authorization and resume path for runtime write tools. The change must preserve `ToolRuntimeAspect`, permission checks, argument-hash verification, `WorkspaceGitGuard`, and the existing six engine implementations.

## Evidence and problem

`ToolInvocation` already defines the intended immutable runtime contract, but no `ToolGateway` implements it. The operational path is split:

1. `ToolRuntimeAspect.aroundTool` captures a snapshot and creates the durable proposal.
2. `ToolProposalExecutionService` independently reconstructs a `ToolExecutionContext`, invokes a tool through `ToolInvoker`, and projects only success/failure observations.
3. `AgentActionProposalService` maintains a separate in-memory `guarded_action` confirmation flow; its confirmation explicitly performs no side effect.

This leaves confirmation resume outside the selected runtime strategy and makes it impossible to prove a single authoritative tool-invocation lifecycle from request through outcome.

## Options considered

1. Replace all engine tool calls and proposal code at once. Rejected: six engines and streaming paths make rollback and behavioral verification too risky.
2. Keep the Aspect as the policy enforcement point, introduce a gateway that owns proposal creation, confirm/resume dispatch, and result projection, then migrate callers incrementally. Selected: it creates one runtime boundary without weakening existing security controls.
3. Only remove `AgentActionProposalService`. Rejected: it removes one duplicate path but leaves the actual runtime confirmation lifecycle fragmented.

## Target slice

This first slice introduces the gateway as an orchestration boundary, not a new tool executor:

```text
engine / @Tool call
  -> ToolRuntimeAspect (permission, rate limit, audit)
  -> ToolGateway.requestWrite
  -> snapshot + durable ToolInvocationProposal (WAITING_CONFIRMATION)

confirm endpoint
  -> ToolGateway.confirm
  -> proposal PENDING -> EXECUTING
  -> ToolGateway.resume
  -> ToolInvoker proxy -> ToolRuntimeAspect revalidation -> WorkspaceGitGuard
  -> durable proposal outcome + lifecycle observation
```

- `ToolGateway` owns runtime proposal orchestration and emits a typed outcome.
- `ToolRuntimeAspect` delegates only the write-authorization branches to the gateway; it remains the sole around-`@Tool` enforcement point.
- `ToolProposalExecutionService` becomes a thin asynchronous adapter that asks the gateway to resume a proposal. It does not reconstruct business policy itself.
- The gateway always creates the resume `ToolExecutionContext` from the immutable proposal row and calls the proxied `ToolInvoker`, preserving aspect interception.
- Initial activation leaves `AgentActionProposalService` intact for scheduled-task domain confirmation. Its generic `guarded_action` creation is removed from runtime chat handling only after characterization tests show the durable runtime proposal path is used.

## Invariants

1. A write/dangerous tool has no side effect before a durable proposal reaches `EXECUTING`.
2. A resumed invocation uses exactly the persisted tool name, canonical arguments, hash, user, run, and request identity.
3. Every resume re-enters `ToolRuntimeAspect` and therefore rechecks the database proposal state and argument hash.
4. Gateway result projection cannot claim completion; Phase 4B retains completion ownership.
5. Failure, rejection, expiry, and timeout remain idempotent and project to the same run.
6. No existing read-tool path or engine selection behavior changes in this slice.

## Testing and rollout

Characterize current pending/confirm/failed behavior first. Add gateway tests for pending creation, confirm dispatch, proxy revalidation, exact context reconstruction, and idempotent failure projection. Keep existing Aspect, proposal-service, workspace-guard, and canonical lifecycle tests green. The gateway activation is one rollback unit: revert the Aspect delegation and execution-service delegation together; durable rows remain audit records.

## Out of scope

- Engine consolidation or changes to model planning.
- Completion verification and final answer ownership.
- Replacing the scheduled-task confirmation domain.
- New workspace lease/fencing storage beyond the existing `WorkspaceGitGuard` protections; that follows after gateway ownership is proven.
