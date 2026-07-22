package com.springclaw.runtime.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.RunTransitionPolicy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class MySqlRunLifecycleStore implements RunLifecycleStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MySqlRunLifecycleStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .registerModule(new JavaTimeModule());
    }

    @Override
    public Optional<RunState> findByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                """
                        SELECT state_json
                        FROM runtime_run_state
                        WHERE run_id = ?
                        """,
                rs -> {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(read(rs.getString("state_json"), RunState.class));
                },
                runId
        );
    }

    @Override
    public List<RunState> findRecent(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jdbcTemplate.query(
                """
                        SELECT state_json
                        FROM runtime_run_state
                        ORDER BY updated_at DESC, run_id DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> read(rs.getString("state_json"), RunState.class),
                safeLimit
        );
    }

    @Override
    public List<RunEvent> findEventsByRunId(String runId) {
        if (runId == null || runId.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                        SELECT event_json
                        FROM runtime_run_event
                        WHERE run_id = ?
                        ORDER BY sequence_no ASC
                        """,
                (rs, rowNum) -> read(rs.getString("event_json"), RunEvent.class),
                runId
        );
    }

    @Override
    @Transactional
    public RunState create(RunState initialState, RunEvent.Draft creationEvent) {
        requireCreation(initialState, creationEvent);
        Optional<RunState> existing = findByRunId(initialState.runId());
        if (existing.isPresent()) {
            if (sameAcceptance(existing.get(), initialState)) {
                return existing.get();
            }
            throw new IllegalStateException(
                    "conflicting run creation: " + initialState.runId()
            );
        }
        RunEvent persisted = persist(creationEvent, 1);
        try {
            insertEvent(persisted);
            insertState(initialState);
        } catch (DuplicateKeyException duplicate) {
            RunState afterRace = findByRunId(initialState.runId()).orElse(null);
            if (afterRace != null && sameAcceptance(afterRace, initialState)) {
                return afterRace;
            }
            throw duplicate;
        }
        return initialState;
    }

    @Override
    @Transactional
    public RunState commit(
            long expectedRevision,
            RunState nextState,
            RunEvent.Draft event
    ) {
        requireMatchingEvent(nextState, event);
        RunState current = findByRunId(nextState.runId())
                .orElseThrow(() -> new IllegalStateException(
                        "run not found: " + nextState.runId()
                ));
        if (current.revision() != expectedRevision) {
            throw stale(expectedRevision, current.revision());
        }
        RunTransitionPolicy.validate(current, nextState);
        RunEvent persisted = persist(event, nextSequence(nextState.runId()));
        insertEvent(persisted);
        int changed = updateState(expectedRevision, nextState);
        if (changed != 1) {
            throw stale(expectedRevision, current.revision());
        }
        return nextState;
    }

    @Override
    @Transactional
    public RunEvent append(long expectedRevision, RunEvent.Draft event) {
        RunState current = findByRunId(event.runId())
                .orElseThrow(() -> new IllegalStateException(
                        "run not found: " + event.runId()
                ));
        if (current.revision() != expectedRevision) {
            throw stale(expectedRevision, current.revision());
        }
        if (event.status() != current.status()) {
            throw new IllegalArgumentException(
                    "observation event status must match current state status"
            );
        }
        RunEvent persisted = persist(event, nextSequence(event.runId()));
        insertEvent(persisted);
        return persisted;
    }

    private void insertState(RunState state) {
        LocalDateTime now = toLocalDateTime(state.updatedAt());
        jdbcTemplate.update(
                """
                        INSERT INTO runtime_run_state (
                            run_id, request_id, session_key, channel, user_id,
                            status, revision, accepted_at, updated_at, deadline_at,
                            state_json, create_time, update_time
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                state.runId(),
                state.requestId(),
                state.sessionKey(),
                state.channel(),
                state.userId(),
                state.status().name(),
                state.revision(),
                toLocalDateTime(state.acceptedAt()),
                toLocalDateTime(state.updatedAt()),
                toLocalDateTime(state.deadlineAt()),
                write(state),
                now,
                now
        );
    }

    private int updateState(long expectedRevision, RunState state) {
        return jdbcTemplate.update(
                """
                        UPDATE runtime_run_state
                        SET request_id = ?,
                            session_key = ?,
                            channel = ?,
                            user_id = ?,
                            status = ?,
                            revision = ?,
                            accepted_at = ?,
                            updated_at = ?,
                            deadline_at = ?,
                            state_json = ?,
                            update_time = ?
                        WHERE run_id = ?
                          AND revision = ?
                        """,
                state.requestId(),
                state.sessionKey(),
                state.channel(),
                state.userId(),
                state.status().name(),
                state.revision(),
                toLocalDateTime(state.acceptedAt()),
                toLocalDateTime(state.updatedAt()),
                toLocalDateTime(state.deadlineAt()),
                write(state),
                toLocalDateTime(state.updatedAt()),
                state.runId(),
                expectedRevision
        );
    }

    private void insertEvent(RunEvent event) {
        jdbcTemplate.update(
                """
                        INSERT INTO runtime_run_event (
                            event_id, run_id, sequence_no, event_type, stage,
                            status, occurred_at, correlation_id, event_json, create_time
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                event.eventId(),
                event.runId(),
                event.sequence(),
                event.eventType().name(),
                event.stage(),
                event.status() == null ? null : event.status().name(),
                toLocalDateTime(event.timestamp()),
                event.correlationId(),
                write(event),
                toLocalDateTime(event.timestamp())
        );
    }

    private long nextSequence(String runId) {
        Long next = jdbcTemplate.queryForObject(
                """
                        SELECT COALESCE(MAX(sequence_no), 0) + 1
                        FROM runtime_run_event
                        WHERE run_id = ?
                        """,
                Long.class,
                runId
        );
        return next == null ? 1L : next;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("runtime lifecycle JSON write failed", ex);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("runtime lifecycle JSON read failed", ex);
        }
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(
                Objects.requireNonNull(instant, "instant"),
                ZoneOffset.UTC
        );
    }

    private static IllegalStateException stale(
            long expectedRevision,
            long actualRevision
    ) {
        return new IllegalStateException(
                "stale run revision: expected " + expectedRevision
                        + " but was " + actualRevision
        );
    }

    private static RunEvent persist(RunEvent.Draft draft, long sequence) {
        return draft.persisted(
                UUID.randomUUID().toString().replace("-", ""),
                sequence
        );
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

    private static boolean sameAcceptance(RunState existing, RunState candidate) {
        return existing.runId().equals(candidate.runId())
                && existing.requestId().equals(candidate.requestId())
                && existing.sessionKey().equals(candidate.sessionKey())
                && existing.channel().equals(candidate.channel())
                && existing.userId().equals(candidate.userId())
                && existing.sessionAccessClaim().equals(candidate.sessionAccessClaim())
                && existing.roleCodeAtAcceptance().equals(candidate.roleCodeAtAcceptance())
                && existing.originalMessage().equals(candidate.originalMessage())
                && existing.responseMode().equals(candidate.responseMode())
                && Objects.equals(existing.paradigm(), candidate.paradigm())
                && existing.acceptedAt().equals(candidate.acceptedAt())
                && existing.deadlineAt().equals(candidate.deadlineAt());
    }
}
