package com.springclaw.service.agent.lifecycle;

public record MemorySlice(String shortTermConversation, String semanticMemory) {
    public MemorySlice {
        shortTermConversation = shortTermConversation == null ? "" : shortTermConversation;
        semanticMemory = semanticMemory == null ? "" : semanticMemory;
    }

    public static MemorySlice empty() {
        return new MemorySlice("", "");
    }
}
