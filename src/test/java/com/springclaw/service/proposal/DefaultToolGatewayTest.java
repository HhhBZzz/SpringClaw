package com.springclaw.service.proposal;

import com.springclaw.runtime.bridge.RollbackRunContextAdapter;
import com.springclaw.runtime.bridge.RunExecutionDecisionProjector;
import com.springclaw.runtime.bridge.RunLifecycleBridge;
import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.runtime.bridge.RunResultProjector;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultToolGatewayTest {

    private ToolInvocationProposalService proposalService;
    private ToolInvoker toolInvoker;
    private RunLifecycleBridge lifecycleBridge;
    private ToolGateway gateway;

    @BeforeEach
    void setUp() {
        proposalService = mock(ToolInvocationProposalService.class);
        toolInvoker = mock(ToolInvoker.class);
        lifecycleBridge = mock(RunLifecycleBridge.class);
        gateway = new DefaultToolGateway(
                proposalService,
                toolInvoker,
                new RunLifecycleObserver(
                        lifecycleBridge,
                        new RollbackRunContextAdapter(),
                        new RunExecutionDecisionProjector(),
                        new RunResultProjector()
                )
        );
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContextHolder.clearApprovedProposal();
    }

    @Test
    void requestApprovalDelegatesTheExactFrozenSnapshotAndContext() {
        ToolInvocationSnapshot snapshot = snapshot();
        ToolExecutionContext context = context();
        ToolInvocationProposal proposal = proposal("tip-1");
        when(proposalService.createPending(snapshot, context)).thenReturn(proposal);

        assertThat(gateway.requestApproval(snapshot, context)).isSameAs(proposal);

        verify(proposalService).createPending(snapshot, context);
    }

    @Test
    void resumeRebuildsContextFromPersistedProposalAndInvokesProxy() {
        ToolInvocationProposal proposal = proposal("tip-1");
        when(proposalService.findByProposalId("tip-1")).thenReturn(Optional.of(proposal));
        AtomicReference<ToolExecutionContext> contextDuringInvoke = new AtomicReference<>();
        AtomicReference<ApprovedProposalContext> approvalDuringInvoke = new AtomicReference<>();
        when(toolInvoker.invoke(proposal.toolName(), proposal.argumentsCanonicalJson())).thenAnswer(invocation -> {
            contextDuringInvoke.set(ToolExecutionContextHolder.get());
            approvalDuringInvoke.set(ToolExecutionContextHolder.getApprovedProposal());
            return "ok";
        });

        gateway.resume("tip-1");

        verify(toolInvoker).invoke(proposal.toolName(), proposal.argumentsCanonicalJson());
        assertThat(contextDuringInvoke.get()).isEqualTo(context());
        assertThat(approvalDuringInvoke.get()).isEqualTo(ApprovedProposalContext.from(proposal));
        assertThat(ToolExecutionContextHolder.get()).isNull();
        assertThat(ToolExecutionContextHolder.getApprovedProposal()).isNull();
    }

    private static ToolInvocationSnapshot snapshot() {
        return new ToolInvocationSnapshot(
                "WorkspaceEditToolPack.workspaceWriteFile", "workspace", "[\"src/A.java\",\"content\"]",
                "hash", "write", List.of("src/A.java"), "preview", false, java.util.Set.of(), "head"
        );
    }

    private static ToolExecutionContext context() {
        return new ToolExecutionContext("session-A", "api", "u1", "run-1", "proposal-execution", "run-1", "USER");
    }

    private static ToolInvocationProposal proposal(String proposalId) {
        LocalDateTime now = LocalDateTime.now();
        return new ToolInvocationProposal(
                1L, proposalId, "run-1", "run-1", "session-A", "u1", "USER",
                "WorkspaceEditToolPack.workspaceWriteFile", "workspace", "[\"src/A.java\",\"content\"]", "hash",
                "write", List.of("src/A.java"), "preview", false, List.of(),
                ToolInvocationProposalStatus.EXECUTING, 0, null, null, null,
                "head", null, null, List.of(), null, null, now, now, now.plusMinutes(15)
        );
    }
}
