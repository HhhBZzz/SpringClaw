# Tool Gateway Convergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish one runtime boundary for write-tool authorization, confirmation, and asynchronous resume without changing engine selection or tool policy.

**Architecture:** `ToolGateway` wraps the existing durable proposal service, proxied `ToolInvoker`, and lifecycle observation. `ToolRuntimeAspect` delegates pending-proposal creation to it, the REST controller delegates confirmation to it, and `ToolProposalExecutionService` becomes an asynchronous event adapter that delegates resume. Existing Aspect revalidation and `WorkspaceGitGuard` remain unchanged.

**Tech Stack:** Java 17, Spring Boot, Spring AOP, Spring events, MyBatis proposal repository, JUnit 5, Mockito, Maven.

## Global Constraints

- Write/dangerous tools must not execute before a durable proposal reaches `EXECUTING`.
- Resumes must use persisted proposal identity and invoke through the Spring-proxied `ToolInvoker`.
- `ToolRuntimeAspect` remains the only around-`@Tool` authorization enforcement point.
- Gateway outcomes may observe success/failure but must not claim run completion.
- Read tools, engine selection, and scheduled-task domain confirmation are out of scope.

---

### Task 1: Introduce the ToolGateway boundary and move pending creation

**Files:**
- Create: `src/main/java/com/springclaw/service/proposal/ToolGateway.java`
- Create: `src/main/java/com/springclaw/service/proposal/DefaultToolGateway.java`
- Modify: `src/main/java/com/springclaw/tool/runtime/ToolRuntimeAspect.java`
- Create: `src/test/java/com/springclaw/service/proposal/DefaultToolGatewayTest.java`
- Modify: `src/test/java/com/springclaw/tool/runtime/ToolRuntimeAspectGuardTest.java`

**Interfaces:**

```java
public interface ToolGateway {
    ToolInvocationProposal requestApproval(ToolInvocationSnapshot snapshot, ToolExecutionContext context);
    ToolInvocationProposal confirm(String proposalId, String reason);
    void resume(String proposalId);
}
```

- [ ] **Step 1: Write failing delegation tests**

```java
@Test
void requestApprovalDelegatesTheExactFrozenSnapshotAndContext() {
    ToolInvocationSnapshot snapshot = snapshot();
    ToolExecutionContext context = context();
    when(proposalService.createPending(snapshot, context)).thenReturn(proposal());

    assertThat(gateway.requestApproval(snapshot, context)).isEqualTo(proposal());
    verify(proposalService).createPending(snapshot, context);
}

@Test
void writeToolCreatesPendingProposalThroughGateway() throws Throwable {
    when(gateway.requestApproval(any(), any())).thenReturn(pendingProposal());

    assertThatThrownBy(() -> aspect.aroundTool(joinPoint))
            .isInstanceOf(PendingToolApprovalException.class);

    verify(gateway).requestApproval(any(ToolInvocationSnapshot.class), eq(context));
}
```

- [ ] **Step 2: Verify RED**

Run: `mvn -q -Dtest=DefaultToolGatewayTest,ToolRuntimeAspectGuardTest test`  
Expected: compilation fails because `ToolGateway` does not exist and Aspect has no gateway dependency.

- [ ] **Step 3: Implement the boundary**

```java
@Component
public class DefaultToolGateway implements ToolGateway {
    private final ToolInvocationProposalService proposalService;

    @Override
    public ToolInvocationProposal requestApproval(
            ToolInvocationSnapshot snapshot, ToolExecutionContext context) {
        return proposalService.createPending(snapshot, context);
    }
}
```

Add `ToolGateway` to the Aspect constructor and replace only:

```java
ToolInvocationProposal proposal = proposalService.createPending(snapshot, context);
```

with:

```java
ToolInvocationProposal proposal = toolGateway.requestApproval(snapshot, context);
```

- [ ] **Step 4: Verify GREEN and commit**

Run: `mvn -q -Dtest=DefaultToolGatewayTest,ToolRuntimeAspectGuardTest,ToolRuntimeAspectInterceptionIT test`  
Expected: exit code 0.

```bash
git add src/main/java/com/springclaw/service/proposal/ToolGateway.java \
  src/main/java/com/springclaw/service/proposal/DefaultToolGateway.java \
  src/main/java/com/springclaw/tool/runtime/ToolRuntimeAspect.java \
  src/test/java/com/springclaw/service/proposal/DefaultToolGatewayTest.java \
  src/test/java/com/springclaw/tool/runtime/ToolRuntimeAspectGuardTest.java
git commit -m "feat: add runtime tool gateway boundary"
```

### Task 2: Centralize confirmation and resume in ToolGateway

**Files:**
- Modify: `src/main/java/com/springclaw/service/proposal/DefaultToolGateway.java`
- Modify: `src/main/java/com/springclaw/service/proposal/ToolProposalExecutionService.java`
- Modify: `src/main/java/com/springclaw/controller/proposal/ToolProposalController.java`
- Modify: `src/test/java/com/springclaw/service/proposal/DefaultToolGatewayTest.java`
- Modify: `src/test/java/com/springclaw/service/proposal/ToolProposalExecutionServiceTest.java`
- Modify: `src/test/java/com/springclaw/controller/proposal/ToolProposalControllerTest.java`

**Interfaces:** `ToolGateway.confirm` delegates the durable state transition. `ToolGateway.resume` reconstructs a `ToolExecutionContext` from the proposal and invokes `ToolInvoker` inside `ToolExecutionContextHolder` with `ApprovedProposalContext`.

- [ ] **Step 1: Write failing resume tests**

```java
@Test
void resumeRebuildsContextFromPersistedProposalAndInvokesProxy() {
    ToolInvocationProposal proposal = executingProposal("tip-1");
    when(proposalService.findByProposalId("tip-1")).thenReturn(Optional.of(proposal));

    gateway.resume("tip-1");

    verify(toolInvoker).invoke(proposal.toolName(), proposal.argumentsCanonicalJson());
    assertThat(ToolExecutionContextHolder.get()).isNull();
    assertThat(ToolExecutionContextHolder.getApprovedProposal()).isNull();
}

@Test
void confirmEndpointDelegatesToGatewayInsteadOfProposalService() {
    controller.confirm("tip-1", Map.of("reason", "ok"));
    verify(gateway).confirm("tip-1", "ok");
}
```

- [ ] **Step 2: Verify RED**

Run: `mvn -q -Dtest=DefaultToolGatewayTest,ToolProposalExecutionServiceTest,ToolProposalControllerTest test`  
Expected: failures because `resume` has no implementation and controller still calls `ToolInvocationProposalService.confirm`.

- [ ] **Step 3: Implement centralized confirm/resume**

`DefaultToolGateway.resume` must:

```java
ToolInvocationProposal proposal = proposalService.findByProposalId(proposalId).orElse(null);
if (proposal == null || proposal.status() != ToolInvocationProposalStatus.EXECUTING) return;
ToolExecutionContext context = new ToolExecutionContext(
        proposal.sessionKey(), "api", proposal.userId(), proposal.requestId(),
        "proposal-execution", proposal.runId(), proposal.roleCode());
try (ToolExecutionContextHolder.Scope ignored = ToolExecutionContextHolder.open(context)) {
    ToolExecutionContextHolder.setApprovedProposal(ApprovedProposalContext.from(proposal));
    toolInvoker.invoke(proposal.toolName(), proposal.argumentsCanonicalJson());
    projectToolSucceeded(proposal);
} catch (Throwable ex) {
    proposalService.markFailed(proposalId, failureDetail(ex));
    projectToolFailed(proposal, ex);
} finally {
    ToolExecutionContextHolder.clearApprovedProposal();
}
```

Move `projectConfirmationApproved`, `projectToolSucceeded`, and `projectToolFailed` unchanged from `ToolProposalExecutionService` into the gateway. Make the execution service call only `toolGateway.resume(event.proposalId())`; retain `@Async` and `@TransactionalEventListener` there. Make the controller depend on `ToolGateway` and call `gateway.confirm` after its existing ownership check.

- [ ] **Step 4: Verify GREEN and commit**

Run: `mvn -q -Dtest=DefaultToolGatewayTest,ToolProposalExecutionServiceTest,ToolProposalControllerTest,ToolRuntimeAspectGuardTest test`  
Expected: exit code 0.

```bash
git add src/main/java/com/springclaw/service/proposal/DefaultToolGateway.java \
  src/main/java/com/springclaw/service/proposal/ToolProposalExecutionService.java \
  src/main/java/com/springclaw/controller/proposal/ToolProposalController.java \
  src/test/java/com/springclaw/service/proposal/DefaultToolGatewayTest.java \
  src/test/java/com/springclaw/service/proposal/ToolProposalExecutionServiceTest.java \
  src/test/java/com/springclaw/controller/proposal/ToolProposalControllerTest.java
git commit -m "refactor: route proposal resume through tool gateway"
```

### Task 3: Characterize activation and verify the phase

**Files:**
- Modify: `src/test/java/com/springclaw/architecture/ToolSafetyPathCharacterizationTest.java`
- Modify: `docs/superpowers/specs/2026-07-11-tool-gateway-convergence-design.md`

**Interfaces:** No new production interface. The characterization test verifies `ToolRuntimeAspect` delegates pending creation to `ToolGateway` and that confirmation resume still reaches the Aspect-protected proxy path.

- [ ] **Step 1: Write failing activation characterization**

```java
@Test
void writeToolPendingPathUsesGatewayAndNeverProceedsDirectly() throws Throwable {
    when(gateway.requestApproval(any(), any())).thenReturn(pendingProposal());

    assertThatThrownBy(() -> aspect.aroundTool(joinPoint))
            .isInstanceOf(PendingToolApprovalException.class);

    verify(gateway).requestApproval(any(), any());
    verify(joinPoint, never()).proceed();
}
```

- [ ] **Step 2: Verify RED**

Run: `mvn -q -Dtest=ToolSafetyPathCharacterizationTest test`  
Expected: test cannot construct the Aspect with a gateway until Task 1 is complete; after Task 1 it must prove the gateway call.

- [ ] **Step 3: Update evidence**

Mark the design status `Implemented and verified`. Record the focused suite and the full Maven suite result, including Flyway validation from integration tests.

- [ ] **Step 4: Verify phase and commit**

Run:

```bash
mvn -q -Dtest=DefaultToolGatewayTest,ToolRuntimeAspectGuardTest,ToolRuntimeAspectInterceptionIT,ToolProposalExecutionServiceTest,ToolInvocationProposalServiceConfirmTest,ToolProposalControllerTest,ToolSafetyPathCharacterizationTest test
mvn -q test
```

Expected: both exit code 0.

```bash
git add src/test/java/com/springclaw/architecture/ToolSafetyPathCharacterizationTest.java \
  docs/superpowers/specs/2026-07-11-tool-gateway-convergence-design.md
git commit -m "test: verify tool gateway convergence"
```

## Acceptance checklist

- [ ] Pending runtime write proposals are created through `ToolGateway`.
- [ ] Confirmation endpoint uses `ToolGateway.confirm` after access control.
- [ ] Async event handling delegates resume to `ToolGateway.resume`.
- [ ] Gateway resume reconstructs context solely from the durable proposal and re-enters the Aspect via `ToolInvoker`.
- [ ] Gateway observes outcomes without completing a run.
- [ ] Focused and full Maven suites pass.
