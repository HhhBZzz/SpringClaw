package com.springclaw.service.chat.impl;

import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OparPromptSupportTypedContextTest {

    private static final Instant T0 = Instant.parse("2026-06-18T00:00:00Z");

    private final OparPromptSupport support = new OparPromptSupport();

    @Test
    void planPromptRendersTypedSnapshotContextWhenAvailable() {
        String prompt = support.renderPlanPrompt(context(), "history", 1, "format");

        assertThat(prompt).contains("SNAPSHOT-QUESTION");
        assertThat(prompt).contains("SNAPSHOT-SHORT-TERM");
        assertThat(prompt).contains("SNAPSHOT-SEMANTIC");
        assertThat(prompt).contains("SNAPSHOT-RULE");
    }

    @Test
    void actionPromptRendersTypedSnapshotContextWhenAvailable() {
        String prompt = support.renderActionPrompt(context(), "plan", "history", 1);

        assertThat(prompt).contains("SNAPSHOT-QUESTION");
        assertThat(prompt).contains("SNAPSHOT-SHORT-TERM");
        assertThat(prompt).contains("SNAPSHOT-SEMANTIC");
        assertThat(prompt).contains("SNAPSHOT-RULE");
    }

    @Test
    void finalAnswerPromptsUseTypedSnapshotQuestionWhenAvailable() {
        String reflect = support.renderReflectPrompt(context(), "plan", "action");
        String repair = support.renderMetaRepairPrompt(context(), "plan", "action", "bad");

        assertThat(reflect).contains("SNAPSHOT-QUESTION");
        assertThat(repair).contains("SNAPSHOT-QUESTION");
    }

    private static ChatContext context() {
        return new ChatContext(
                null,
                "api",
                "u1",
                "USER",
                "legacy user message",
                "legacy effective message",
                "req-1",
                "system",
                new AssembledContext(
                        "session:test",
                        "api",
                        "u1",
                        "LEGACY-QUESTION",
                        "",
                        "",
                        "# 当前问题\nLEGACY-QUESTION"
                ),
                null,
                "opar",
                "test",
                "agent",
                "general",
                com.springclaw.service.agent.AgentDecision.general("test"),
                ContextInjection.empty(),
                snapshotForTest()
        );
    }

    static ContextSnapshot snapshotForTest() {
        return new ContextSnapshot(
                "req-1",
                "session:test",
                "u1",
                "api",
                "u1",
                "USER",
                "legacy user message",
                "SNAPSHOT-QUESTION",
                "system",
                "SNAPSHOT-PROJECT",
                List.of("SNAPSHOT-SHORT-TERM"),
                List.of("SNAPSHOT-SEMANTIC"),
                List.of("SNAPSHOT-RULE"),
                List.of("web"),
                Map.of("providerId", "provider"),
                Map.of("schema", "test"),
                new MemoryFrame(
                        "req-1",
                        MemoryScope.from(SessionAccessClaim.personal(
                                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                                "api",
                                "session:test",
                                "u1"
                        )),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of("source", "test"),
                        List.of(),
                        T0,
                        "frame-hash"
                ),
                T0,
                "snapshot-hash"
        );
    }
}
