# Approved Command Execution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make an explicitly requested, allowlisted system command execute only after the owner confirms the immutable durable tool proposal.

**Architecture:** Add a narrow `ApprovedCommandProposalService` beside the existing local-file proposal service. It parses only exact approved command forms, captures a `SystemToolPack.runCommand` snapshot, and creates the existing durable proposal. Route this service from `ChatServiceImpl` before the legacy generic action proposal, and classify `runCommand` as an execution-risk tool so confirmation resume performs the existing identity and argument-hash checks.

**Tech Stack:** Java 17, Spring Boot 3.5, Spring AI `@Tool`, JUnit 5, Mockito, AssertJ, existing `ToolInvocationProposal`/`ToolGateway` lifecycle, Vue task workspace for browser verification.

## Global Constraints

- Accept only `请执行命令 echo <text>`, `请执行命令 pwd`, and `请执行命令 git status` after trimming the user text.
- Reject shell grammar and unsupported commands before proposal creation; never replay the original free-form prompt through a model.
- Store and execute `SystemToolPack.runCommand` with its exact canonical positional argument array.
- Reuse the existing `/api/tool-proposals/**`, `tool_action_required`, `ToolGateway`, `ToolRuntimeAspect`, audit, permission, expiry, and hash validation paths.
- Preserve scheduled-task and local-file-write proposal behavior and do not change any frontend REST or SSE contract.
- `SystemToolPack.runCommand` remains admin-only under the existing default role permission policy; no user/guest privilege expansion is part of this slice.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `src/main/java/com/springclaw/service/chat/impl/ApprovedCommandProposalService.java` | Parse the three supported user-command forms, reject unsafe grammar, capture the exact durable tool snapshot, and create the pending proposal. |
| `src/test/java/com/springclaw/service/chat/impl/ApprovedCommandProposalServiceTest.java` | Characterize accepted forms, rejected forms, frozen arguments, and tool execution context. |
| `src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java` | Prefer an executable tool proposal for a supported command over the legacy non-executing action proposal. |
| `src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPendingApprovalTest.java` | Prove supported commands emit `tool_action_required` and never create an `AgentActionProposal`. |
| `src/main/java/com/springclaw/tool/runtime/ToolRuntimeAspect.java` | Treat `SystemToolPack.runCommand` as `execution` risk so resumed approval revalidates identity and arguments hash. |
| `src/test/java/com/springclaw/tool/runtime/ToolRuntimeAspectGuardTest.java` | Prove the system command uses a pending proposal without approval and executes only with an approved immutable proposal. |
| `src/main/java/com/springclaw/service/proposal/ToolInvocationSnapshotService.java` | Render the command in the durable preview summary. |
| `src/main/resources/application.yml` | Set the development default command policy to the exact three command binaries (`echo,pwd,git`) in whitelist mode. |

### Task 1: Define and test the approved command proposal boundary

**Files:**
- Create: `src/main/java/com/springclaw/service/chat/impl/ApprovedCommandProposalService.java`
- Create: `src/test/java/com/springclaw/service/chat/impl/ApprovedCommandProposalServiceTest.java`

**Interfaces:**
- Produces `Optional<ToolInvocationProposal> createProposalIfSupported(ChatContext context)`.
- Consumes `ToolInvocationSnapshotService.capture(String, String, Object[], String)` and `ToolInvocationProposalService.createPending(ToolInvocationSnapshot, ToolExecutionContext)`.
- Uses exact constants `SystemToolPack.runCommand`, `system`, and `execution`.

- [ ] **Step 1: Write failing tests for parsing, freezing, and rejecting.**

  Create `ApprovedCommandProposalServiceTest` with a mocked snapshot/proposal service and a real `ChatContext` whose decision is `new AgentDecision("model_control", "agent_tools", List.of("system"), "side_effect", true, "command")`. Add these tests:

  ```java
  @Test
  void createsAnExecutionProposalForAnExactEchoCommand() {
      ChatContext context = context("请执行命令 echo springclaw-approval-e2e");
      ToolInvocationSnapshot snapshot = snapshot("[\"echo springclaw-approval-e2e\"]");
      when(snapshotService.capture(eq("SystemToolPack.runCommand"), eq("system"), argsCaptor.capture(), eq("execution")))
              .thenReturn(snapshot);
      when(proposalService.createPending(eq(snapshot), contextCaptor.capture())).thenReturn(proposal());

      assertThat(service.createProposalIfSupported(context)).contains(proposal());
      assertThat(argsCaptor.getValue()).containsExactly("echo springclaw-approval-e2e");
      assertThat(contextCaptor.getValue().userId()).isEqualTo("admin");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "请执行命令 rm -rf /", "请执行命令 git status; pwd",
      "请执行命令 echo $(whoami)", "请执行命令 curl https://example.com",
      "执行命令 echo missing-prefix"
  })
  void neverCreatesAProposalForUnsupportedOrShellComposedInput(String message) {
      assertThat(service.createProposalIfSupported(context(message))).isEmpty();
      verifyNoInteractions(snapshotService, proposalService);
  }
  ```

- [ ] **Step 2: Run the focused test and verify RED.**

  Run:

  ```bash
  mvn -Dtest=ApprovedCommandProposalServiceTest test
  ```

  Expected: compilation fails because `ApprovedCommandProposalService` does not exist.

- [ ] **Step 3: Implement the minimal planner/service.**

  Create a Spring `@Service` that:

  ```java
  private static final String PREFIX = "请执行命令";
  private static final String TOOL_NAME = "SystemToolPack.runCommand";
  private static final String TOOLSET_ID = "system";

  public Optional<ToolInvocationProposal> createProposalIfSupported(ChatContext context) {
      if (!isCommandDecision(context)) return Optional.empty();
      return parseApprovedCommand(context.effectiveUserMessage())
              .map(command -> createProposal(context, command));
  }
  ```

  `parseApprovedCommand` must return exactly `pwd`, `git status`, or an `echo ` value with a nonblank text suffix. Reject `\\`, a line break, and every character in `;|&<>`$(){}[]` before accepting the `echo` suffix. `createProposal` captures `new Object[]{command}` as `execution` and uses a `ToolExecutionContext` built from the context session, channel, user, request ID, and role. Do not call `ProcessBuilder`, `SystemToolPack`, or the model here.

- [ ] **Step 4: Run the focused test and verify GREEN.**

  Run:

  ```bash
  mvn -Dtest=ApprovedCommandProposalServiceTest test
  ```

  Expected: all accepted and rejected input assertions pass.

- [ ] **Step 5: Commit the planner boundary.**

  ```bash
  git add src/main/java/com/springclaw/service/chat/impl/ApprovedCommandProposalService.java src/test/java/com/springclaw/service/chat/impl/ApprovedCommandProposalServiceTest.java
  git commit -m "feat: plan approved system commands"
  ```

### Task 2: Route approved commands to the durable proposal lifecycle

**Files:**
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPendingApprovalTest.java`

**Interfaces:**
- Consumes `ApprovedCommandProposalService.createProposalIfSupported(ChatContext)`.
- Produces the existing `tool_action_required` SSE event and avoids `AgentActionProposalService.createProposal` for a supported command.
- Keeps `LocalFileWriteProposalService` first so its current specialized behavior is unchanged.

- [ ] **Step 1: Add a failing stream characterization test.**

  In `ChatServiceImplPendingApprovalTest`, inject a mocked `ApprovedCommandProposalService` through a new optional setter, configure it to return a `PENDING` `ToolInvocationProposal`, stream an execution-risk model-control `ChatContext`, and assert:

  ```java
  verify(approvedCommandProposalService).createProposalIfSupported(context);
  verify(sseEventBridge).sendToolActionRequired(eq(emitter), eq(proposal));
  verify(actionProposalService, never()).createProposal(any(), any(), any(), any(), any(), any(), any());
  ```

- [ ] **Step 2: Run the focused test and verify RED.**

  Run:

  ```bash
  mvn -Dtest=ChatServiceImplPendingApprovalTest test
  ```

  Expected: the new verification fails because ChatService does not consult the approved-command service.

- [ ] **Step 3: Wire the optional service and preserve precedence.**

  Add a nullable field plus `@Autowired(required = false)` setter, following the existing local-file setter style. In `streamActionRequired`, replace the one-line local-file lookup with:

  ```java
  ToolInvocationProposal toolProposal = localFileWriteProposalService == null
          ? null
          : localFileWriteProposalService.createProposalIfSupported(context).orElse(null);
  if (toolProposal == null && approvedCommandProposalService != null) {
      toolProposal = approvedCommandProposalService.createProposalIfSupported(context).orElse(null);
  }
  ```

  Keep the existing `sendToolActionRequired`, persistence, trace, lock release, and legacy action-proposal fallback intact.

- [ ] **Step 4: Run focused chat tests and verify GREEN.**

  Run:

  ```bash
  mvn -Dtest=ChatServiceImplPendingApprovalTest,ApprovedCommandProposalServiceTest test
  ```

  Expected: both suites pass, and no scheduled-task/local-file test changes are required.

- [ ] **Step 5: Commit the chat routing change.**

  ```bash
  git add src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPendingApprovalTest.java
  git commit -m "feat: route approved commands through tool proposals"
  ```

### Task 3: Enforce execution risk and validate the end-to-end safety path

**Files:**
- Modify: `src/main/java/com/springclaw/tool/runtime/ToolRuntimeAspect.java`
- Modify: `src/main/java/com/springclaw/service/proposal/ToolInvocationSnapshotService.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/springclaw/tool/runtime/ToolRuntimeAspectGuardTest.java`

**Interfaces:**
- `ToolRuntimeAspect.resolveRiskLevel("SystemToolPack", "runCommand")` returns `execution`.
- `ToolInvocationSnapshotService.capture("SystemToolPack.runCommand", "system", new Object[]{command}, "execution")` exposes a command preview.
- The existing `DefaultToolGateway.resume` invokes the proxy and allows the aspect to validate the approved proposal state and argument hash.

- [ ] **Step 1: Add failing risk/preview tests.**

  Add one aspect test that invokes `SystemToolPack.runCommand` without an approved context and expects `PendingToolApprovalException`; add one snapshot test asserting `previewSummary()` equals `runCommand: echo springclaw-approval-e2e`. The test must use a real `ToolRuntimeAspect` path, not a direct `ProcessBuilder` mock.

- [ ] **Step 2: Run the focused suites and verify RED.**

  Run:

  ```bash
  mvn -Dtest=ToolRuntimeAspectGuardTest,ToolInvocationSnapshotServiceTest test
  ```

  Expected: the command is currently classified as `read`, so no pending proposal is created and the preview omits the command.

- [ ] **Step 3: Implement the smallest enforcement changes.**

  At the start of `resolveRiskLevel`, add:

  ```java
  if ("SystemToolPack".equals(simpleClass) && "runCommand".equals(methodName)) {
      return "execution";
  }
  ```

  Extend `ToolInvocationSnapshotService.buildPreview` to treat `runCommand` exactly like `workspaceRunCommand`, rendering the first argument with the existing 240-character truncation. Change `application.yml` system command defaults to:

  ```yaml
  command-mode: ${SPRINGCLAW_SYSTEM_COMMAND_MODE:whitelist}
  allowed-commands: ${SPRINGCLAW_SYSTEM_ALLOWED_COMMANDS:echo,pwd,git}
  ```

  Keep the existing blocked-command list and timeout unchanged.

- [ ] **Step 4: Run the focused suites and verify GREEN.**

  Run:

  ```bash
  mvn -Dtest=ToolRuntimeAspectGuardTest,ToolInvocationSnapshotServiceTest,DefaultToolGatewayTest test
  ```

  Expected: approved-command execution is proposal-gated, previews show the exact command, and gateway resume tests remain green.

- [ ] **Step 5: Run regression and real-browser validation.**

  Run:

  ```bash
  mvn test
  cd frontend && npm test && npm run typecheck && npm run build
  ```

  Start the backend with the existing `.env.local` provider configuration and Docker MySQL port 3307. In the real task workspace, submit `请执行命令 echo springclaw-approval-e2e`; assert `等待确认`, confirm exactly once, poll the existing proposal endpoint through the UI, and assert terminal `EXECUTED` with the fixed command output. Then submit the same command and reject it; assert terminal `REJECTED` with no tool output.

- [ ] **Step 6: Commit and push the completed feature.**

  ```bash
  git add src/main/java/com/springclaw/service/chat/impl/ApprovedCommandProposalService.java \
    src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java \
    src/main/java/com/springclaw/tool/runtime/ToolRuntimeAspect.java \
    src/main/java/com/springclaw/service/proposal/ToolInvocationSnapshotService.java \
    src/main/resources/application.yml \
    src/test/java/com/springclaw/service/chat/impl/ApprovedCommandProposalServiceTest.java \
    src/test/java/com/springclaw/service/chat/impl/ChatServiceImplPendingApprovalTest.java \
    src/test/java/com/springclaw/tool/runtime/ToolRuntimeAspectGuardTest.java \
    docs/superpowers/specs/2026-07-13-approved-command-execution-design.md \
    docs/superpowers/plans/2026-07-13-approved-command-execution.md
  git commit -m "feat: execute approved safe commands"
  git push origin codex/flyway-schema-migration
  ```

## Plan Self-Review

- Spec coverage: Task 1 implements the exact grammar and immutable proposal; Task 2 removes the legacy chat detour; Task 3 restores execution-risk revalidation, preview clarity, restrictive defaults, and end-to-end validation.
- Placeholder scan: no deferred behavior, generic validation language, or unspecified commands remain.
- Type consistency: all tasks use `SystemToolPack.runCommand`, `ToolInvocationSnapshot`, `ToolInvocationProposal`, `ToolExecutionContext`, and existing proposal endpoints consistently.
