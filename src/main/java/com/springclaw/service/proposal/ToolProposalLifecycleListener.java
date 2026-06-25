package com.springclaw.service.proposal;

import com.springclaw.runtime.bridge.LegacyLifecycleObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;

/**
 * Projects persisted tool-proposal lifecycle transitions onto the canonical run.
 *
 * <p>This listener is the unique owner of persisted tool-proposal suspension
 * (confirmationRequired) and rejection (confirmationRejected). Task 6's
 * PendingToolApproval rendering path must not call these observer methods.
 */
@Component
public class ToolProposalLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(ToolProposalLifecycleListener.class);

    private final LegacyLifecycleObserver observer;

    public ToolProposalLifecycleListener(LegacyLifecycleObserver observer) {
        this.observer = observer;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProposalCreated(ToolProposalCreatedEvent event) {
        if (event.runId() == null || event.runId().isBlank()) {
            return;
        }
        try {
            observer.confirmationRequired(event.runId(), event.proposalId(), Instant.now());
        } catch (RuntimeException ex) {
            // Canonical projection must not break the proposal persistence path.
            log.warn("canonical confirmationRequired projection failed, proposalId={}, reason={}",
                    event.proposalId(), ex.getMessage());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProposalRejected(ToolProposalRejectedEvent event) {
        if (event.runId() == null || event.runId().isBlank()) {
            return;
        }
        try {
            observer.confirmationRejected(event.runId(), event.reason(), Instant.now());
        } catch (RuntimeException ex) {
            log.warn("canonical confirmationRejected projection failed, proposalId={}, reason={}",
                    event.proposalId(), ex.getMessage());
        }
    }
}
