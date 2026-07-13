# Approved Command Execution Design

## Objective

Make an approved, low-risk system command execute through SpringClaw's existing durable `ToolInvocationProposal` path. The approved tool name and arguments must be the exact values later executed. The current legacy `guarded_action` path must remain only for scheduled-task domain confirmation and must no longer be used for supported command requests.

## Scope

This slice accepts only the explicit input form `请执行命令 <command>` and supports a small safe command set:

- `echo <text>`
- `pwd`
- `git status`

The command is run in the existing configured workspace with the existing `SystemToolPack` timeout, blocking rules, audit, permission checks, and proposal hash verification. File writes keep their dedicated proposal flow. Arbitrary shells, redirection, chaining, pipes, substitutions, environment expansion, network commands, and all commands outside the list above are rejected before a proposal is created.

## Existing Boundary

`ToolInvocationProposal` is the durable source of truth for an approved runtime tool. Its confirm endpoint transitions a proposal to `EXECUTING`, publishes an execution request, and `DefaultToolGateway` resumes it through `ToolInvoker` and `ToolRuntimeAspect`. The aspect rechecks the stored tool name, request identity, user identity, run identity, and arguments hash before the tool can run.

`ChatServiceImpl.streamActionRequired` currently only creates a durable tool proposal for a local file write. Every other confirmation routes to `AgentActionProposalService`, which deliberately returns `executed=false`. That is appropriate for its domain-only scheduled-task proposal but not for a command that the user expects to execute.

## Design

Introduce one focused planner, `ApprovedCommandProposalPlanner`, with this contract:

```java
Optional<ToolInvocationSnapshot> plan(ChatContext context)
```

The planner returns a snapshot only when all of the following are true:

1. The decision requires confirmation and has the `model_control` intent.
2. The user text starts with the exact Chinese command prefix after trimming.
3. The parsed command matches one of the three supported command shapes.
4. The command contains no shell control character or token outside the shape it declares.

The snapshot is captured with the existing `ToolInvocationSnapshotService` for the concrete `SystemToolPack.runCommand` tool and its canonical arguments. It therefore uses the same persisted tool proposal schema, risk level, hashes, expiry, confirmation endpoint, asynchronous executor, result storage, audit records, and lifecycle projection as an Aspect-created proposal.

`ChatServiceImpl.streamActionRequired` checks the existing local-file planner first, then this command planner. When either produces a tool proposal it sends the existing `tool_action_required` event and does not create a legacy action proposal. Unsupported command text continues to the existing legacy confirmation response; it is not executable and its user-facing wording must say that this version cannot execute it.

No endpoint, SSE event name, confirmation status, or frontend API contract changes. The existing tool-approval card and proposal monitor must receive the durable proposal and show its real `EXECUTED` or `FAILED` state.

## Safety Invariants

1. No approved command is reconstructed from the original free-form prompt or sent to the model a second time.
2. The persisted proposal contains the exact command that is executed; a changed argument hash fails execution.
3. A rejected, expired, non-owner, or non-`EXECUTING` proposal cannot reach `SystemToolPack`.
4. Unsupported or shell-composed commands never create an executable proposal.
5. The existing `SystemToolPack` blacklist and command timeout remain active defense in depth, even for allowlisted commands.
6. Scheduled-task and local-file-write behavior remain unchanged.

## Test Strategy

Characterize the planner independently with real snapshot creation:

- accepted `echo`, `pwd`, and `git status` inputs return the expected tool name and canonical argument values;
- unsupported commands and shell metacharacters return empty;
- a supported command causes `ChatServiceImpl` to emit `tool_action_required`, not `action_required`;
- confirmation executes only through `ToolGateway` and records an `EXECUTED` result;
- rejection and tamper attempts never call the tool invoker.

Run the focused Java suites, the full Maven suite, frontend typecheck/build, and a real browser flow: submit `echo springclaw-approval-e2e`, see the approval, confirm it, and observe an `EXECUTED` proposal result. The test command has no file, network, or process-management side effect beyond writing its fixed text to standard output.

## Non-goals

- Supporting arbitrary shell syntax or arbitrary natural-language command extraction.
- Replaying a command request through a model after approval.
- Changing the scheduled-task confirmation domain.
- Replacing or refactoring the existing tool-proposal execution architecture.
- Expanding file-write, web, email, or external service operations.
