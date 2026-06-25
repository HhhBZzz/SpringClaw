package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryFrameItem;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class LegacyContextViewAdapter {

    public LegacyContextView adapt(ContextSnapshot snapshot) {
        MemoryFrame frame = snapshot.memoryFrame();
        String project = renderLayer(frame.projectItems());
        String shortTerm = renderLayer(frame.shortTermTurns());
        String semantic = renderLayer(frame.semanticFacts());
        String procedural = renderLayer(frame.proceduralRules());
        String observe = """
                # 当前问题
                %s

                # 项目记忆（Memory Bank）
                %s

                # 短期会话上下文（事件流）
                %s

                # 长期语义记忆（同会话优先）
                %s

                # 程序化记忆（规则/经验）
                %s
                """.formatted(
                snapshot.effectiveMessage(),
                project,
                shortTerm,
                semantic,
                procedural
        );
        AssembledContext assembled = new AssembledContext(
                snapshot.sessionKey(),
                snapshot.channel(),
                snapshot.userId(),
                snapshot.effectiveMessage(),
                shortTerm,
                semantic,
                observe,
                parseInt(snapshot.contextSourceSummary().get("memoryLearningActiveCount")),
                parseInt(snapshot.contextSourceSummary().get("memoryLearningFilteredCount"))
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("contextSummary", assembled.sourceSummary());
        metadata.put("memoryFrameHash", frame.frameHash());
        metadata.put("memoryFrameSourceSummary", frame.sourceSummary());
        metadata.put("contextSnapshotHash", snapshot.snapshotHash());
        return new LegacyContextView(
                assembled,
                new ContextInjection(observe, "", "", metadata)
        );
    }

    private static String renderLayer(java.util.List<MemoryFrameItem> items) {
        return items.stream()
                .map(MemoryFrameItem::content)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private static int parseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
