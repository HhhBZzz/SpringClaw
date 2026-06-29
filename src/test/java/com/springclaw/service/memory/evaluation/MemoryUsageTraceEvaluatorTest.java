package com.springclaw.service.memory.evaluation;

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

class MemoryUsageTraceEvaluatorTest {

    private static final Instant T0 = Instant.parse("2026-06-29T00:00:00Z");

    @Test
    void reportsNoInjectionWhenFrameHasNoMemoryItems() {
        MemoryUsageTrace trace = new MemoryUsageTraceEvaluator().evaluate(frame(List.of()), "answer");

        assertThat(trace.memoryInjected()).isFalse();
        assertThat(trace.memoryReferencedInAnswer()).isFalse();
        assertThat(trace.memoryReferenceKind()).isEqualTo(MemoryUsageTrace.ReferenceKind.NONE);
        assertThat(trace.memoryUseJudgedBy()).isEqualTo("deterministic");
    }

    @Test
    void detectsExplicitMemoryReferenceInAnswer() {
        MemoryUsageTrace trace = new MemoryUsageTraceEvaluator().evaluate(
                frame(List.of(item("pref-1", "Alice prefers short Chinese summaries."))),
                "I will keep this short in Chinese because Alice prefers short Chinese summaries."
        );

        assertThat(trace.memoryInjected()).isTrue();
        assertThat(trace.memoryReferencedInAnswer()).isTrue();
        assertThat(trace.memoryReferenceKind()).isEqualTo(MemoryUsageTrace.ReferenceKind.EXPLICIT);
    }

    @Test
    void detectsParaphrasedMemoryReferenceInAnswer() {
        MemoryUsageTrace trace = new MemoryUsageTraceEvaluator().evaluate(
                frame(List.of(item("pref-1", "Alice prefers short Chinese summaries."))),
                "我会用中文简短同步进展。"
        );

        assertThat(trace.memoryInjected()).isTrue();
        assertThat(trace.memoryReferencedInAnswer()).isTrue();
        assertThat(trace.memoryReferenceKind()).isEqualTo(MemoryUsageTrace.ReferenceKind.PARAPHRASE);
    }

    private static MemoryFrame frame(List<MemoryFrameItem> semantic) {
        return new MemoryFrame(
                "run-1",
                MemoryScope.user("api", "session-1", "alice"),
                List.of(),
                List.of(),
                List.of(),
                semantic,
                List.of(),
                List.of(),
                Map.of("schema", "test"),
                List.of(),
                T0,
                "frame-hash"
        );
    }

    private static MemoryFrameItem item(String sourceId, String content) {
        return new MemoryFrameItem(
                sourceId,
                MemoryFrameSourceKind.MEMORY_RECORD,
                MemoryFrameLayer.SEMANTIC_FACT,
                sourceId,
                sourceId + ":v1",
                MemoryType.SEMANTIC,
                MemoryScopeType.USER,
                "alice",
                content,
                sourceId + "-hash",
                List.of("event-" + sourceId),
                0.9,
                0.9,
                0.9,
                1,
                T0
        );
    }
}
