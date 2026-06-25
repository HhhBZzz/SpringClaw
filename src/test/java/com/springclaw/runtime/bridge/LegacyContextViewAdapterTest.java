package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryFrameItem;
import com.springclaw.runtime.memory.contract.MemoryFrameLayer;
import com.springclaw.runtime.memory.contract.MemoryFrameSourceKind;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryScopeType;
import com.springclaw.runtime.memory.contract.MemoryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyContextViewAdapterTest {

    private static final Instant T0 = Instant.parse("2026-06-24T00:00:00Z");

    @Test
    void projectsSnapshotIntoLegacyAssembledContextAndInjection() {
        ContextSnapshot snapshot = snapshot();

        LegacyContextView view = new LegacyContextViewAdapter().adapt(snapshot);

        assertThat(view.assembled().observePrompt())
                .contains("# 项目记忆（Memory Bank）")
                .contains("project fact")
                .contains("# 短期会话上下文（事件流）")
                .contains("USER: hello")
                .contains("# 长期语义记忆（同会话优先）")
                .contains("semantic fact")
                .contains("# 程序化记忆（规则/经验）")
                .contains("always verify");
        assertThat(view.injection().observePrompt())
                .isEqualTo(view.assembled().observePrompt());
        assertThat(view.injection().metadata())
                .containsEntry("memoryFrameHash", "frame-hash-1");
    }

    private static ContextSnapshot snapshot() {
        return new ContextSnapshot(
                "run-1",
                "session-1",
                "alice",
                "api",
                "alice",
                "USER",
                "hello",
                "hello",
                "system",
                "",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                frame(),
                T0,
                "snapshot-hash-1"
        );
    }

    private static MemoryFrame frame() {
        MemoryScope scope = MemoryScope.from(SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api", "session-1", "alice"
        ));
        return new MemoryFrame(
                "run-1",
                scope,
                List.of(),
                List.of(item("USER: hello", MemoryFrameLayer.SHORT_TERM)),
                List.of(),
                List.of(item("semantic fact", MemoryFrameLayer.SEMANTIC_FACT)),
                List.of(item("always verify", MemoryFrameLayer.PROCEDURAL_RULE)),
                List.of(item("project fact", MemoryFrameLayer.PROJECT)),
                Map.of("source", "test"),
                List.of(),
                T0,
                "frame-hash-1"
        );
    }

    private static MemoryFrameItem item(String content, MemoryFrameLayer layer) {
        return new MemoryFrameItem(
                "source-" + layer.name(),
                MemoryFrameSourceKind.MESSAGE_EVENT,
                layer,
                null,
                null,
                MemoryType.EPISODIC,
                MemoryScopeType.PERSONAL_SESSION,
                null,
                content,
                "hash-" + content.hashCode(),
                List.of(),
                0.0,
                0.0,
                0.0,
                1,
                T0
        );
    }
}
