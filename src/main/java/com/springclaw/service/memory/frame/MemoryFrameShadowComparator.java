package com.springclaw.service.memory.frame;

import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryFrameOmission;
import com.springclaw.service.context.AssembledContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public class MemoryFrameShadowComparator {

    public MemoryFrameShadowComparison compare(
            String runId,
            AssembledContext legacy,
            MemoryFrame frame
    ) {
        Objects.requireNonNull(legacy, "legacy");
        Objects.requireNonNull(frame, "frame");
        return new MemoryFrameShadowComparison(
                runId,
                sha256(legacy.observePrompt()),
                frame.frameHash(),
                legacy.memoryLearningActiveCount(),
                legacy.memoryLearningFilteredCount(),
                frameLayerCounts(frame),
                omissionCounts(frame)
        );
    }

    private static Map<String, Integer> frameLayerCounts(MemoryFrame frame) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("workingMemory", frame.workingMemoryRefs().size());
        counts.put("shortTerm", frame.shortTermTurns().size());
        counts.put("episodic", frame.episodicItems().size());
        counts.put("semantic", frame.semanticFacts().size());
        counts.put("procedural", frame.proceduralRules().size());
        counts.put("project", frame.projectItems().size());
        return counts;
    }

    private static Map<MemoryFrameOmission.Category, Integer> omissionCounts(
            MemoryFrame frame
    ) {
        EnumMap<MemoryFrameOmission.Category, Integer> counts =
                new EnumMap<>(MemoryFrameOmission.Category.class);
        for (MemoryFrameOmission omission : frame.omissions()) {
            counts.merge(omission.category(), 1, Integer::sum);
        }
        return counts;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
