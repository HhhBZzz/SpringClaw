package com.springclaw.runtime.lifecycle;

import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.RunTransitionPolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public final class InMemoryRunLifecycleStore implements RunLifecycleStore {

    private final ConcurrentMap<String, RunState> states = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<RunEvent>> events = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    @Override
    public Optional<RunState> findByRunId(String runId) {
        return Optional.ofNullable(states.get(runId));
    }

    @Override
    public List<RunEvent> findEventsByRunId(String runId) {
        Object lock = lockFor(runId);
        synchronized (lock) {
            return List.copyOf(events.getOrDefault(runId, List.of()));
        }
    }

    @Override
    public RunState create(RunState initialState, RunEvent.Draft creationEvent) {
        requireCreation(initialState, creationEvent);
        Object lock = lockFor(initialState.runId());
        synchronized (lock) {
            RunState existing = states.get(initialState.runId());
            if (existing != null) {
                if (existing.equals(initialState)) {
                    return existing;
                }
                throw new IllegalStateException(
                        "conflicting run creation: " + initialState.runId()
                );
            }
            List<RunEvent> runEvents = new ArrayList<>();
            runEvents.add(persist(creationEvent, 1));
            states.put(initialState.runId(), initialState);
            events.put(initialState.runId(), runEvents);
            return initialState;
        }
    }

    @Override
    public RunState commit(
            long expectedRevision,
            RunState nextState,
            RunEvent.Draft event
    ) {
        requireMatchingEvent(nextState, event);
        Object lock = lockFor(nextState.runId());
        synchronized (lock) {
            RunState current = states.get(nextState.runId());
            if (current == null) {
                throw new IllegalStateException("run not found: " + nextState.runId());
            }
            if (current.revision() != expectedRevision) {
                throw new IllegalStateException(
                        "stale run revision: expected " + expectedRevision
                                + " but was " + current.revision()
                );
            }
            RunTransitionPolicy.validate(current, nextState);
            List<RunEvent> runEvents = events.get(nextState.runId());
            long sequence = runEvents.size() + 1L;
            RunEvent persisted = persist(event, sequence);
            states.put(nextState.runId(), nextState);
            runEvents.add(persisted);
            return nextState;
        }
    }

    private Object lockFor(String runId) {
        return locks.computeIfAbsent(runId, ignored -> new Object());
    }

    private static void requireCreation(
            RunState initialState,
            RunEvent.Draft creationEvent
    ) {
        if (initialState.revision() != 0 || initialState.status() != RunStatus.CREATED) {
            throw new IllegalArgumentException(
                    "initial state must be CREATED at revision 0"
            );
        }
        requireMatchingEvent(initialState, creationEvent);
    }

    private static void requireMatchingEvent(
            RunState state,
            RunEvent.Draft event
    ) {
        if (!state.runId().equals(event.runId())) {
            throw new IllegalArgumentException("event runId must match state runId");
        }
        if (event.status() != state.status()) {
            throw new IllegalArgumentException("event status must match state status");
        }
    }

    private static RunEvent persist(RunEvent.Draft draft, long sequence) {
        return draft.persisted(
                UUID.randomUUID().toString().replace("-", ""),
                sequence
        );
    }
}
