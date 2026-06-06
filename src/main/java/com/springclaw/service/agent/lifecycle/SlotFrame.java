package com.springclaw.service.agent.lifecycle;

import java.util.List;

public record SlotFrame(List<SlotValue> values,
                        List<SlotRequirement> missing,
                        double confidence) {
    public SlotFrame {
        values = values == null ? List.of() : List.copyOf(values);
        missing = missing == null ? List.of() : List.copyOf(missing);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    public static SlotFrame empty() {
        return new SlotFrame(List.of(), List.of(), 0.0);
    }

    public boolean complete() {
        return missing.isEmpty();
    }
}
