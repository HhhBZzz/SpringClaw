package com.springclaw.service.memory.frame;

import com.springclaw.runtime.memory.contract.MemoryFrameLayer;

import java.util.EnumMap;
import java.util.Map;

public final class MemoryFrameBudget {

    private static final int MIN_TOTAL_CHARS = 1_000;

    private final int totalChars;
    private final int maxLayerLimit;
    private final Map<MemoryFrameLayer, Integer> limits;

    private MemoryFrameBudget(int totalChars) {
        if (totalChars < MIN_TOTAL_CHARS) {
            throw new IllegalArgumentException(
                    "total memory frame budget must be at least " + MIN_TOTAL_CHARS
            );
        }
        this.totalChars = totalChars;
        this.maxLayerLimit = (int) Math.floor(totalChars * 0.50d);
        this.limits = buildLimits(totalChars);
    }

    public static MemoryFrameBudget of(int totalChars) {
        return new MemoryFrameBudget(totalChars);
    }

    public int totalChars() {
        return totalChars;
    }

    public int maxLayerLimit() {
        return maxLayerLimit;
    }

    public int limitFor(MemoryFrameLayer layer) {
        if (layer == null) {
            return 0;
        }
        return limits.getOrDefault(layer, 0);
    }

    private static Map<MemoryFrameLayer, Integer> buildLimits(int totalChars) {
        EnumMap<MemoryFrameLayer, Integer> result =
                new EnumMap<>(MemoryFrameLayer.class);
        result.put(MemoryFrameLayer.SHORT_TERM, share(totalChars, 0.35d));
        result.put(MemoryFrameLayer.EPISODIC, share(totalChars, 0.15d));
        result.put(MemoryFrameLayer.SEMANTIC_FACT, share(totalChars, 0.20d));
        result.put(MemoryFrameLayer.PROJECT, share(totalChars, 0.20d));
        result.put(MemoryFrameLayer.PROCEDURAL_RULE, share(totalChars, 0.10d));
        result.put(MemoryFrameLayer.WORKING_MEMORY, 0);
        return Map.copyOf(result);
    }

    private static int share(int totalChars, double ratio) {
        return (int) Math.floor(totalChars * ratio);
    }
}
