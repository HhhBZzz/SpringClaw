package com.springclaw.service.memory;

import com.springclaw.service.agent.AgentRunTraceEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLearningServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldPersistFailedTraceAsReviewableLearningEntry() throws Exception {
        AgentLearningService service = new AgentLearningService(true, true, tempDir.toString(), 320);
        AgentRunTraceEvent event = new AgentRunTraceEvent(
                "req-learn-1",
                "WorkspaceEditToolPack.workspaceRunCommand",
                "tool",
                "failed",
                "workspace guard denied parent path segment",
                12L,
                1710000000000L,
                null,
                "",
                "",
                AgentRunTraceEvent.TIMELINE_STEP_SCHEMA,
                "tool",
                "command.run",
                "../outside",
                "workspace",
                "write"
        );

        var entry = service.captureTraceFailure(event);

        assertThat(entry).isPresent();
        String learning = Files.readString(tempDir.resolve("agent-learnings.md"));
        assertThat(learning)
                .contains("springclaw.agent-learning.v1")
                .contains("status: active")
                .contains("requestId: req-learn-1")
                .contains("source: trace-failure")
                .contains("rule:")
                .contains("counterexample:")
                .contains("workspace guard denied parent path segment")
                .contains("signature:");
    }

    @Test
    void shouldDeduplicateSameLearningSignature() throws Exception {
        AgentLearningService service = new AgentLearningService(true, true, tempDir.toString(), 320);
        AgentRunTraceEvent event = new AgentRunTraceEvent(
                "req-learn-2",
                "workspaceRunCommand",
                "tool",
                "failed",
                "same failure detail",
                1L,
                1710000000000L,
                null,
                "",
                "",
                AgentRunTraceEvent.TIMELINE_STEP_SCHEMA,
                "tool",
                "command.run",
                "mvn test",
                "workspace",
                "write"
        );

        service.captureTraceFailure(event);
        service.captureTraceFailure(event);

        String learning = Files.readString(tempDir.resolve("agent-learnings.md"));
        assertThat(learning.split("requestId: req-learn-2", -1)).hasSize(2);
    }

    @Test
    void shouldSkipSuccessfulTraceAndDisabledLearning() throws Exception {
        AgentRunTraceEvent success = new AgentRunTraceEvent(
                "req-ok",
                "final",
                "final",
                "success",
                "done",
                1L,
                1710000000000L
        );

        AgentLearningService disabled = new AgentLearningService(false, true, tempDir.toString(), 320);
        AgentLearningService enabled = new AgentLearningService(true, true, tempDir.toString(), 320);

        assertThat(disabled.captureTraceFailure(success)).isEmpty();
        assertThat(enabled.captureTraceFailure(success)).isEmpty();
        assertThat(Files.exists(tempDir.resolve("agent-learnings.md"))).isFalse();
    }

    @Test
    void shouldSkipTraceFailureWhenTraceLearningIsDisabled() {
        AgentLearningService service = new AgentLearningService(true, false, tempDir.toString(), 320);
        AgentRunTraceEvent event = new AgentRunTraceEvent(
                "req-trace-disabled",
                "workspaceRunCommand",
                "tool",
                "failed",
                "guard denied",
                1L,
                1710000000000L
        );

        assertThat(service.captureTraceFailure(event)).isEmpty();
        assertThat(Files.exists(tempDir.resolve("agent-learnings.md"))).isFalse();
    }
}
