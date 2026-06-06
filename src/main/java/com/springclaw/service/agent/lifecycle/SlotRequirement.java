package com.springclaw.service.agent.lifecycle;

public record SlotRequirement(String name, boolean required, String description) {
    public SlotRequirement {
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
    }
}
