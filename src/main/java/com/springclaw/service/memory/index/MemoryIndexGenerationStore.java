package com.springclaw.service.memory.index;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class MemoryIndexGenerationStore {

    private final AtomicReference<String> activeGeneration = new AtomicReference<>("gen-1");
    private final AtomicLong sequence = new AtomicLong(1);
    private final AtomicBoolean degraded = new AtomicBoolean(false);

    public String activeGeneration() {
        return activeGeneration.get();
    }

    public String nextGeneration() {
        return "gen-" + sequence.incrementAndGet();
    }

    public void activate(String generation) {
        activeGeneration.set(requireText(generation, "generation"));
    }

    public void markDegraded(boolean degraded) {
        this.degraded.set(degraded);
    }

    public boolean degraded() {
        return degraded.get();
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
