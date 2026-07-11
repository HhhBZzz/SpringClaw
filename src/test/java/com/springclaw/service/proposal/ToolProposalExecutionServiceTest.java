package com.springclaw.service.proposal;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ToolProposalExecutionServiceTest {

    @Test
    void executionEventDelegatesResumeToGateway() {
        ToolGateway gateway = mock(ToolGateway.class);
        ToolProposalExecutionService service = new ToolProposalExecutionService(gateway);

        service.onExecutionRequested(new ToolProposalExecutionRequestedEvent("tip-1"));

        verify(gateway).resume("tip-1");
    }
}
