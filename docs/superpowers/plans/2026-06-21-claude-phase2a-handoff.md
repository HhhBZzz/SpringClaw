# Claude Phase 2A Handoff

> Base this work on the prepared Claude branch/worktree recorded below. Follow
> `superpowers:subagent-driven-development` or `superpowers:executing-plans`.

## Objective

Complete and independently verify canonical identity Phase 2A. Do not implement
RunCoordinator, lifecycle storage, routing migration, or transport projectors.

## Starting point

```text
Source commit: 9660548
Source branch: codex/unified-agent-runtime
Claude branch: claude/canonical-identity-acceptance
Claude worktree: /Users/hanbingzheng/springclaw/.claude/worktrees/claude-canonical-identity-acceptance
Implementation plan: docs/superpowers/plans/2026-06-21-unified-runtime-canonical-identity.md
```

## Work to complete

1. Independently review commit `9660548` against Phase 2A Task 2.
2. Fix only verified P0/P1 issues. Preserve DTO shapes and existing runtime behavior.
3. Execute Task 3 from the canonical identity plan:
   - populate the accepted ID into existing `ToolExecutionContext.requestId/runId`;
   - ensure proposal rows and resumed tool execution retain the same identity;
   - do not change risk classification, confirmation policy, `ToolRuntimeAspect`,
     workspace guards, or proposal authorization.
4. Execute Task 4 acceptance:
   - 67 contract tests;
   - 47 production-backed characterization tests;
   - focused baseline tests using the current actual count;
   - all new identity and ownership tests;
   - compile and test-compile.
5. Record exact commits, files, test counts, MySQL environment warnings, and rollback
   boundary in the collaboration ledger.

## Allowed production paths

```text
src/main/java/com/springclaw/controller/ChatController.java
src/main/java/com/springclaw/service/chat/ChatService.java
src/main/java/com/springclaw/service/chat/impl/ChatContextFactory.java
src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java
src/main/java/com/springclaw/service/chat/async/ChatMessageConsumer.java
src/main/java/com/springclaw/service/webhook/WebhookRouterService.java
src/main/java/com/springclaw/service/task/executor/TaskExecutionService.java
src/main/java/com/springclaw/service/chat/impl/SimplifiedOparEngine.java
src/main/java/com/springclaw/service/chat/impl/OparLoopEngine.java
src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java
src/main/java/com/springclaw/service/chat/impl/ModelLedStreamEngine.java
src/main/java/com/springclaw/service/agent/executor/WorkspaceCapabilityExecutor.java
src/main/java/com/springclaw/service/agent/executor/LocalFilesCapabilityExecutor.java
src/main/java/com/springclaw/service/agent/executor/WebCapabilityExecutor.java
src/main/java/com/springclaw/service/agent/executor/SystemHealthCapabilityExecutor.java
src/main/java/com/springclaw/service/agent/executor/SkillCapabilityExecutor.java
src/main/java/com/springclaw/service/agent/executor/RealtimeCapabilityExecutor.java
```

Directly affected tests and the collaboration ledger are also allowed.

## Forbidden changes

```text
src/main/java/com/springclaw/runtime/contract/**
src/main/java/com/springclaw/tool/runtime/ToolRuntimeAspect.java
src/main/java/com/springclaw/service/workspace/**
src/main/java/com/springclaw/service/proposal/ToolInvocationProposalService.java
src/main/java/com/springclaw/service/agent/EngineSelector.java
src/main/java/com/springclaw/dto/**
src/main/java/com/springclaw/runtime/bridge/**
```

Do not suppress duplicate async execution in Phase 2A. Redelivery reuses the same ID
but may execute again; lifecycle idempotency belongs to Phase 2B.

## Return format

```text
Status:
Branch:
Commits:
Files:
Tests:
Review findings:
Environmental warnings:
Rollback:
Next dependency:
```
