package com.springclaw.service.agent.lifecycle;

public record ResolvedInput(String text,
                            ResolutionType type,
                            double confidence,
                            String reason,
                            boolean changed) {
    public ResolvedInput {
        text = text == null ? "" : text.trim();
        type = type == null ? ResolutionType.UNCHANGED : type;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        reason = reason == null ? "" : reason.trim();
    }

    public static ResolvedInput unchanged(String text, String reason) {
        return new ResolvedInput(text, ResolutionType.UNCHANGED, 1.0, reason, false);
    }

    public static ResolvedInput bypassed(String text, String reason) {
        return new ResolvedInput(text, ResolutionType.BYPASSED, 1.0, reason, false);
    }
}
