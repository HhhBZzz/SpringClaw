package com.springclaw.common.exception;

import com.springclaw.service.agent.exception.AgentErrorPayload;
import com.springclaw.service.agent.exception.ParameterExtractFailedException;
import com.springclaw.service.agent.lifecycle.AgentPhase;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerAgentExceptionTest {

    @Test
    void shouldExposeStructuredAgentErrorPayload() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ParameterExtractFailedException exception = new ParameterExtractFailedException(
                "req-1",
                AgentPhase.SLOT_BIND,
                "缺少必要参数",
                Map.of("slot", "city")
        );

        var response = handler.handleAgentExecution(exception);

        assertThat(response.getCode()).isEqualTo(46000);
        assertThat(response.getMessage()).isEqualTo("缺少必要参数");
        assertThat(response.getData()).isInstanceOf(AgentErrorPayload.class);
        assertThat(response.getData().phase()).isEqualTo("SLOT_BIND");
        assertThat(response.getData().errorCode()).isEqualTo("PARAMETER_EXTRACT_FAILED");
        assertThat(response.getData().metadata()).containsEntry("slot", "city");
    }
}
