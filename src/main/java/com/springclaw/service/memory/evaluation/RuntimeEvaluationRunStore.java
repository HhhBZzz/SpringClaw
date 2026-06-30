package com.springclaw.service.memory.evaluation;

import java.util.List;
import java.util.Optional;

public interface RuntimeEvaluationRunStore {

    RuntimeEvaluationRun insert(RuntimeEvaluationRun run);

    List<RuntimeEvaluationRun> listByType(String evaluationType, int limit);

    Optional<RuntimeEvaluationRun> latestByType(String evaluationType);
}
