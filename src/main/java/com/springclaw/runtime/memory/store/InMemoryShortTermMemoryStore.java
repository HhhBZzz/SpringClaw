package com.springclaw.runtime.memory.store;

import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryScopeType;
import com.springclaw.runtime.memory.contract.ShortTermMemoryEntry;
import com.springclaw.runtime.memory.port.ShortTermMemoryStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryShortTermMemoryStore implements ShortTermMemoryStore {

    private static final int MAX_ENTRIES_PER_SCOPE = 40;
    private static final int MAX_SCOPES = 5_000;

    private final Map<ScopeKey, ScopeWindow> windows =
            new ConcurrentHashMap<>();
    private final Object scopeCapacityLock = new Object();

    @Override
    public void append(MemoryScope scope, ShortTermMemoryEntry entry) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(entry, "entry");
        ScopeWindow window = windowForWrite(scope);
        synchronized (window) {
            if (!window.eventKeys.add(entry.eventKey())) {
                return;
            }
            window.entries.add(entry);
            normalize(window);
        }
    }

    @Override
    public List<ShortTermMemoryEntry> readRecent(
            MemoryScope scope,
            int limit
    ) {
        Objects.requireNonNull(scope, "scope");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        ScopeWindow window = windows.get(ScopeKey.from(scope));
        if (window == null) {
            return List.of();
        }
        synchronized (window) {
            int fromIndex = Math.max(0, window.entries.size() - limit);
            return List.copyOf(window.entries.subList(
                    fromIndex,
                    window.entries.size()
            ));
        }
    }

    @Override
    public void mergeRecovery(
            MemoryScope scope,
            long watermark,
            List<ShortTermMemoryEntry> persistedEntries
    ) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(persistedEntries, "persistedEntries");
        List<ShortTermMemoryEntry> eligible = persistedEntries.stream()
                .map(entry -> Objects.requireNonNull(entry, "persisted entry"))
                .filter(entry -> entry.eventId() <= watermark)
                .toList();
        ScopeWindow window = windowForWrite(scope);
        synchronized (window) {
            window.entries.removeIf(entry -> entry.eventId() <= watermark);
            window.eventKeys.clear();
            window.eventKeys.addAll(window.entries.stream()
                    .map(ShortTermMemoryEntry::eventKey)
                    .toList());
            for (ShortTermMemoryEntry entry : eligible) {
                if (window.eventKeys.add(entry.eventKey())) {
                    window.entries.add(entry);
                }
            }
            normalize(window);
        }
    }

    private ScopeWindow windowForWrite(MemoryScope scope) {
        ScopeKey scopeKey = ScopeKey.from(scope);
        ScopeWindow existing = windows.get(scopeKey);
        if (existing != null) {
            return existing;
        }
        synchronized (scopeCapacityLock) {
            existing = windows.get(scopeKey);
            if (existing != null) {
                return existing;
            }
            if (windows.size() >= MAX_SCOPES) {
                throw new IllegalStateException(
                        "short-term scope capacity exhausted"
                );
            }
            ScopeWindow created = new ScopeWindow();
            windows.put(scopeKey, created);
            return created;
        }
    }

    private static void normalize(ScopeWindow window) {
        window.entries.sort(Comparator.comparingLong(
                ShortTermMemoryEntry::eventId
        ));
        while (window.entries.size() > MAX_ENTRIES_PER_SCOPE) {
            ShortTermMemoryEntry removed = window.entries.remove(0);
            window.eventKeys.remove(removed.eventKey());
        }
    }

    private static final class ScopeWindow {
        private final List<ShortTermMemoryEntry> entries = new ArrayList<>();
        private final Set<String> eventKeys = new HashSet<>();
    }

    private record ScopeKey(
            MemoryScopeType scopeType,
            String scopeId
    ) {
        private static ScopeKey from(MemoryScope scope) {
            return new ScopeKey(scope.scopeType(), scope.scopeId());
        }
    }
}
