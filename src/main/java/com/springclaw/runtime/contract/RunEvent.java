package com.springclaw.runtime.contract;

import java.time.Instant;
import java.util.Objects;

/**
 * Ordered, append-only fact describing one accepted runtime transition or observation.
 *
 * <p>Both {@code eventId} and sequence assignment belong to {@code RunEventStore}.
 * Strategies emit immutable {@link Draft} values without either store-owned field,
 * then the store converts a draft with {@link Draft#persisted(String, long)}. An
 * event does not mutate state by itself. Transport delivery failure cannot rewrite
 * a terminal business event, and duplicate delivery must be idempotent by
 * {@code eventId} — see unified-runtime architecture spec § 7.9.
 */
public record RunEvent(
        String eventId,
        String runId,
        long sequence,
        RunEventType eventType,
        String stage,
        RunStatus status,
        Instant timestamp,
        long durationMs,
        String payloadSchema,
        String payload,
        String causationId,
        String correlationId
) {
    public RunEvent {
        eventId = requireText(eventId, "eventId");
        runId = requireText(runId, "runId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        eventType = Objects.requireNonNull(eventType, "eventType");
        stage = requireText(stage, "stage");
        status = status; // nullable: not every event carries a run status
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must not be negative");
        }
        payloadSchema = requireText(payloadSchema, "payloadSchema");
        payload = Objects.requireNonNullElse(payload, "{}");
        causationId = causationId; // nullable: root events have no causation
        correlationId = requireText(correlationId, "correlationId");
    }

    /**
     * Unpersisted event data emitted by a runtime strategy. Identity and ordering
     * are deliberately absent because the event store owns both.
     */
    public record Draft(
            String runId,
            RunEventType eventType,
            String stage,
            RunStatus status,
            Instant timestamp,
            long durationMs,
            String payloadSchema,
            String payload,
            String causationId,
            String correlationId
    ) {
        public Draft {
            runId = requireText(runId, "runId");
            eventType = Objects.requireNonNull(eventType, "eventType");
            stage = requireText(stage, "stage");
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            if (durationMs < 0) {
                throw new IllegalArgumentException("durationMs must not be negative");
            }
            payloadSchema = requireText(payloadSchema, "payloadSchema");
            payload = Objects.requireNonNullElse(payload, "{}");
            correlationId = requireText(correlationId, "correlationId");
        }

        public RunEvent persisted(String eventId, long sequence) {
            return new RunEvent(
                    eventId,
                    runId,
                    sequence,
                    eventType,
                    stage,
                    status,
                    timestamp,
                    durationMs,
                    payloadSchema,
                    payload,
                    causationId,
                    correlationId
            );
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
