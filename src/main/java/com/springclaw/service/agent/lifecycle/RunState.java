package com.springclaw.service.agent.lifecycle;

import com.springclaw.service.agent.exception.AgentExecutionException;

import java.time.Instant;
import java.util.List;

public record RunState(String requestId,
                       AgentPhase currentPhase,
                       List<RunTraceEvent> traces,
                       List<ExecutionEvidence> evidence,
                       List<AgentExecutionException> errors,
                       QualityReport quality,
                       Instant startedAt,
                       Instant updatedAt) {
    public RunState {
        requestId = requestId == null ? "" : requestId.trim();
        currentPhase = currentPhase == null ? AgentPhase.INPUT_NORMALIZE : currentPhase;
        traces = traces == null ? List.of() : List.copyOf(traces);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        errors = errors == null ? List.of() : List.copyOf(errors);
        quality = quality == null ? QualityReport.empty() : quality;
        startedAt = startedAt == null ? Instant.now() : startedAt;
        updatedAt = updatedAt == null ? startedAt : updatedAt;
    }

    public static RunState started(String requestId) {
        Instant now = Instant.now();
        return new RunState(requestId, AgentPhase.INPUT_NORMALIZE, List.of(), List.of(), List.of(), QualityReport.empty(), now, now);
    }

    public RunState advanceTo(AgentPhase nextPhase) {
        AgentPhase target = nextPhase == null ? currentPhase : nextPhase;
        return new RunState(
                requestId,
                target,
                appendTrace(RunTraceEvent.success(requestId, target, "phase=" + target.name())),
                evidence,
                errors,
                quality,
                startedAt,
                Instant.now()
        );
    }

    private List<RunTraceEvent> appendTrace(RunTraceEvent event) {
        java.util.ArrayList<RunTraceEvent> next = new java.util.ArrayList<>(traces);
        next.add(event);
        return List.copyOf(next);
    }
}
