package com.springclaw.service.agent.lifecycle;

public record SlotValue(String name,
                        String value,
                        String sourceText,
                        double confidence,
                        SlotSource source) {
    public SlotValue {
        name = safe(name);
        value = safe(value);
        sourceText = safe(sourceText);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        source = source == null ? SlotSource.CURRENT_INPUT : source;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
