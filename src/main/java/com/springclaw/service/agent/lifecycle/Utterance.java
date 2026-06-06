package com.springclaw.service.agent.lifecycle;

public record Utterance(UtteranceType type, double confidence, String reason) {
    public Utterance {
        type = type == null ? UtteranceType.UNKNOWN : type;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        reason = reason == null ? "" : reason.trim();
    }

    public static Utterance unknown() {
        return new Utterance(UtteranceType.UNKNOWN, 0.0, "");
    }
}
