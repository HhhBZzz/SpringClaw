package com.springclaw.runtime.lifecycle;

import com.springclaw.runtime.contract.RunState;

import java.util.List;
import java.util.Optional;

public interface RunStateRepository {

    Optional<RunState> findByRunId(String runId);

    default List<RunState> findRecent(int limit) {
        return List.of();
    }

    default RunState requireByRunId(String runId) {
        return findByRunId(runId)
                .orElseThrow(() -> new IllegalStateException("run not found: " + runId));
    }
}
