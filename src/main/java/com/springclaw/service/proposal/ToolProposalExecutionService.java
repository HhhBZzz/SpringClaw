package com.springclaw.service.proposal;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Asynchronous event adapter for confirmed runtime tool proposals.
 * Runtime policy and resume context construction belong to {@link ToolGateway}.
 */
@Component
public class ToolProposalExecutionService {

    private final ToolGateway toolGateway;

    public ToolProposalExecutionService(ToolGateway toolGateway) {
        this.toolGateway = toolGateway;
    }

    @Async("proposalExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onExecutionRequested(ToolProposalExecutionRequestedEvent event) {
        toolGateway.resume(event.proposalId());
    }
}
