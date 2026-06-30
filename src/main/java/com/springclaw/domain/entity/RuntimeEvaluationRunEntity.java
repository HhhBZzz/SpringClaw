package com.springclaw.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.springclaw.service.memory.evaluation.RuntimeEvaluationRun;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Data
@TableName("runtime_evaluation_run")
public class RuntimeEvaluationRunEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String evaluationType;
    private String schemaVersion;
    private Integer enabled;
    private Integer total;
    private Integer passed;
    private Integer failed;
    private Integer skipped;
    private String resultJson;
    private LocalDateTime createTime;

    @TableLogic(value = "0", delval = "1")
    private Integer deleted;

    public static RuntimeEvaluationRunEntity fromDomain(RuntimeEvaluationRun run) {
        RuntimeEvaluationRunEntity entity = new RuntimeEvaluationRunEntity();
        entity.id = run.id();
        entity.evaluationType = run.evaluationType();
        entity.schemaVersion = run.schemaVersion();
        entity.enabled = run.enabled() ? 1 : 0;
        entity.total = run.total();
        entity.passed = run.passed();
        entity.failed = run.failed();
        entity.skipped = run.skipped();
        entity.resultJson = run.resultJson();
        entity.createTime = toLocalDateTime(run.createdAt());
        entity.deleted = 0;
        return entity;
    }

    public RuntimeEvaluationRun toDomain() {
        return new RuntimeEvaluationRun(
                id,
                evaluationType,
                schemaVersion,
                enabled != null && enabled == 1,
                total == null ? 0 : total,
                passed == null ? 0 : passed,
                failed == null ? 0 : failed,
                skipped == null ? 0 : skipped,
                resultJson,
                fromLocalDateTime(createTime)
        );
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null
                : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant fromLocalDateTime(LocalDateTime ldt) {
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }
}
