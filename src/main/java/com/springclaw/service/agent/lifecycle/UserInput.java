package com.springclaw.service.agent.lifecycle;

public record UserInput(String text, String normalizedText) {
    public UserInput {
        text = text == null ? "" : text.trim();
        normalizedText = normalizedText == null || normalizedText.isBlank()
                ? text.replaceAll("\\s+", " ").trim()
                : normalizedText.trim();
    }
}
