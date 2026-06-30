package com.springclaw.service.memory.evaluation;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.springclaw.domain.entity.RuntimeEvaluationRunEntity;
import com.springclaw.mapper.RuntimeEvaluationRunMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class MySqlRuntimeEvaluationRunStore implements RuntimeEvaluationRunStore {

    private final RuntimeEvaluationRunMapper mapper;

    public MySqlRuntimeEvaluationRunStore(RuntimeEvaluationRunMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public RuntimeEvaluationRun insert(RuntimeEvaluationRun run) {
        RuntimeEvaluationRunEntity entity =
                RuntimeEvaluationRunEntity.fromDomain(run);
        mapper.insert(entity);
        return entity.toDomain();
    }

    @Override
    public List<RuntimeEvaluationRun> listByType(String evaluationType, int limit) {
        QueryWrapper<RuntimeEvaluationRunEntity> qw = new QueryWrapper<>();
        qw.eq("evaluation_type", evaluationType)
          .eq("deleted", 0)
          .orderByDesc("create_time")
          .orderByDesc("id")
          .last("LIMIT " + Math.max(1, limit));
        return mapper.selectList(qw).stream()
                .map(RuntimeEvaluationRunEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<RuntimeEvaluationRun> latestByType(String evaluationType) {
        return listByType(evaluationType, 1).stream().findFirst();
    }
}
