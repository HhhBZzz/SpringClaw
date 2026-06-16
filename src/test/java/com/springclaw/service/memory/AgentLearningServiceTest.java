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
    void shouldClassifyTraceFailureFromFactsInsteadOfDefaultRuleTemplate() {
        AgentLearningService service = new AgentLearningService(true, true, tempDir.toString(), 320);
        AgentRunTraceEvent event = new AgentRunTraceEvent(
                "req-learn-context",
                "ContextAssembler.assemble",
                "model",
                "failed",
                "context window missed the relevant file mapping",
                12L,
                1710000000000L,
                null,
                "",
                "",
                AgentRunTraceEvent.TIMELINE_STEP_SCHEMA,
                "model",
                "context.load",
                "prompt",
                "context",
                "read"
        );

        service.captureTraceFailure(event);

        var entries = service.listEntries(10);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).counterexampleCategory()).isEqualTo("model_or_context");
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

    @Test
    void shouldUpdateLearningStatusBySignature() throws Exception {
        AgentLearningService service = new AgentLearningService(true, true, tempDir.toString(), 320);
        var entry = service.capture(new AgentLearningService.AgentLearningCandidate(
                "req-review-1",
                "manual",
                "重复失败命令",
                "同一个失败命令需要先改变条件。",
                "不要原样重复执行失败命令。",
                "连续两次执行同一失败 shell。",
                "trace failed"
        )).orElseThrow();

        var update = service.updateStatus(entry.signature(), "disabled", "规则过宽，容易阻止合法重试");

        assertThat(update).isPresent();
        assertThat(update.get().signature()).isEqualTo(entry.signature());
        assertThat(update.get().previousStatus()).isEqualTo("active");
        assertThat(update.get().status()).isEqualTo("disabled");
        String learning = Files.readString(tempDir.resolve("agent-learnings.md"));
        assertThat(learning)
                .contains("- status: disabled")
                .contains("- reviewedAt:")
                .contains("- reviewReason: 规则过宽，容易阻止合法重试")
                .doesNotContain("- status: active");

        MemoryBankService memoryBankService = new MemoryBankService(true, tempDir.toString(), 1200);
        assertThat(memoryBankService.renderContext()).doesNotContain("不要原样重复执行失败命令。");
    }

    @Test
    void shouldSkipStatusUpdateWhenSignatureOrStatusIsInvalid() throws Exception {
        AgentLearningService service = new AgentLearningService(true, true, tempDir.toString(), 320);
        service.capture(new AgentLearningService.AgentLearningCandidate(
                "req-review-2",
                "manual",
                "失败工具",
                "先复盘失败原因。",
                "不要忽略失败原因。",
                "直接重试。",
                "trace failed"
        ));
        String before = Files.readString(tempDir.resolve("agent-learnings.md"));

        assertThat(service.updateStatus("missing-signature", "disabled", "not found")).isEmpty();
        assertThat(service.updateStatus("missing-signature", "active", "not found")).isEmpty();
        assertThat(service.updateStatus("missing-signature", "approved", "not found")).isEmpty();
        assertThat(service.updateStatus("missing-signature", "rejected", "not found")).isEmpty();
        assertThat(service.updateStatus("missing-signature", "superseded", "not found")).isEmpty();
        assertThat(service.updateStatus("missing-signature", "pending", "bad status")).isEmpty();
        assertThat(Files.readString(tempDir.resolve("agent-learnings.md"))).isEqualTo(before);
    }

    @Test
    void shouldListLearningEntriesForReview() throws Exception {
        AgentLearningService service = new AgentLearningService(true, true, tempDir.toString(), 320);
        var active = service.capture(new AgentLearningService.AgentLearningCandidate(
                "req-list-1",
                "manual",
                "失败命令",
                "先分析失败条件。",
                "不要原样重复失败命令。",
                "连续重试失败 shell。",
                "trace failed"
        )).orElseThrow();
        var disabled = service.capture(new AgentLearningService.AgentLearningCandidate(
                "req-list-2",
                "manual",
                "错误路径",
                "路径越界要停下。",
                "不要访问 workspace 外路径。",
                "../outside",
                "guard denied"
        )).orElseThrow();
        service.updateStatus(disabled.signature(), "disabled", "已由 workspace guard 覆盖");

        var entries = service.listEntries(20);

        assertThat(entries).hasSize(2);
        assertThat(entries)
                .extracting(AgentLearningService.AgentLearningReviewItem::signature)
                .containsExactly(active.signature(), disabled.signature());
        assertThat(entries.get(0).status()).isEqualTo("active");
        assertThat(entries.get(0).rule()).isEqualTo("不要原样重复失败命令。");
        assertThat(entries.get(0).counterexampleCategory()).isEqualTo("tool_failure");
        assertThat(entries.get(0).contextIncluded()).isTrue();
        assertThat(entries.get(0).contextImpact()).isEqualTo("included_in_context");
        assertThat(entries.get(1).status()).isEqualTo("disabled");
        assertThat(entries.get(1).counterexampleCategory()).isEqualTo("permission_boundary");
        assertThat(entries.get(1).contextIncluded()).isFalse();
        assertThat(entries.get(1).contextImpact()).isEqualTo("filtered_from_context");
        assertThat(entries.get(1).reviewReason()).isEqualTo("已由 workspace guard 覆盖");
        assertThat(entries.get(1).reviewedAt()).isNotBlank();
    }
}
