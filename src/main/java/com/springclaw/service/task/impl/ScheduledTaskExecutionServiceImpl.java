package com.springclaw.service.task.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.springclaw.domain.entity.ScheduledTaskExecution;
import com.springclaw.mapper.ScheduledTaskExecutionMapper;
import com.springclaw.service.task.ScheduledTaskExecutionService;
import com.springclaw.service.task.ScheduledTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ScheduledTaskExecutionServiceImpl extends ServiceImpl<ScheduledTaskExecutionMapper, ScheduledTaskExecution>
        implements ScheduledTaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskExecutionServiceImpl.class);
    private static final long DB_RETRY_INTERVAL_MS = 30_000L;

    private final boolean dbEnabled;
    private final ScheduledTaskService scheduledTaskService;
    private final Map<String, ScheduledTaskExecution> localStore = new ConcurrentHashMap<>();
    private final AtomicLong localIdGenerator = new AtomicLong(1);
    private volatile long dbRetryAt = 0L;

    public ScheduledTaskExecutionServiceImpl(@Value("${springclaw.persistence.db-enabled:false}") boolean dbEnabled,
                                             ScheduledTaskService scheduledTaskService) {
        this.dbEnabled = dbEnabled;
        this.scheduledTaskService = scheduledTaskService;
    }

    @Override
    public ScheduledTaskExecution start(String taskId, String triggerSource, String requestId) {
        ScheduledTaskExecution execution = new ScheduledTaskExecution();
        execution.setExecutionId("exec_" + UUID.randomUUID().toString().replace("-", ""));
        execution.setTaskId(taskId);
        execution.setTriggerSource(StringUtils.hasText(triggerSource) ? triggerSource.trim().toUpperCase() : "MANUAL");
        execution.setStartedAt(LocalDateTime.now());
        execution.setStatus("RUNNING");
        execution.setRequestId(StringUtils.hasText(requestId) ? requestId.trim() : "");
        persist(execution, true);
        return copyOf(execution);
    }

    @Override
    public void complete(String executionId,
                         String status,
                         String summary,
                         String resultPayload,
                         String errorMessage,
                         String requestId,
                         String sessionKey,
                         LocalDateTime finishedAt) {
        ScheduledTaskExecution execution = loadMutable(executionId);
        execution.setStatus(StringUtils.hasText(status) ? status.trim().toUpperCase() : "SUCCESS");
        execution.setSummary(summary);
        execution.setResultPayload(resultPayload);
        execution.setErrorMessage(errorMessage);
        execution.setRequestId(StringUtils.hasText(requestId) ? requestId.trim() : execution.getRequestId());
        execution.setSessionKey(sessionKey);
        execution.setFinishedAt(finishedAt == null ? LocalDateTime.now() : finishedAt);
        persist(execution, false);
    }

    @Override
    public List<ScheduledTaskExecution> listByTask(String requesterUserId, String requesterRole, String taskId, int limit) {
        scheduledTaskService.getTaskForAccess(requesterUserId, requesterRole, taskId);
        int safeLimit = Math.max(1, Math.min(limit, 200));
        if (canUseDb()) {
            try {
                return lambdaQuery()
                        .eq(ScheduledTaskExecution::getTaskId, taskId)
                        .orderByDesc(ScheduledTaskExecution::getStartedAt)
                        .last("limit " + safeLimit)
                        .list().stream()
                        .map(this::copyOf)
                        .toList();
            } catch (Exception ex) {
                markDbTemporarilyUnavailable(ex);
            }
        }
        return localStore.values().stream()
                .filter(execution -> taskId.equals(execution.getTaskId()))
                .sorted(Comparator.comparing(ScheduledTaskExecution::getStartedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(safeLimit)
                .map(this::copyOf)
                .toList();
    }

    @Override
    public int deleteByTaskId(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return 0;
        }
        String safeTaskId = taskId.trim();
        if (canUseDb()) {
            try {
                return getBaseMapper().delete(new LambdaQueryWrapper<ScheduledTaskExecution>()
                        .eq(ScheduledTaskExecution::getTaskId, safeTaskId));
            } catch (Exception ex) {
                markDbTemporarilyUnavailable(ex);
            }
        }
        int before = localStore.size();
        localStore.entrySet().removeIf(entry -> safeTaskId.equals(entry.getValue().getTaskId()));
        return before - localStore.size();
    }

    private ScheduledTaskExecution loadMutable(String executionId) {
        if (canUseDb()) {
            try {
                ScheduledTaskExecution execution = lambdaQuery()
                        .eq(ScheduledTaskExecution::getExecutionId, executionId)
                        .one();
                if (execution != null) {
                    return execution;
                }
            } catch (Exception ex) {
                markDbTemporarilyUnavailable(ex);
            }
        }
        ScheduledTaskExecution local = localStore.get(executionId);
        if (local == null) {
            throw new com.springclaw.common.exception.BusinessException(40462, "任务执行记录不存在: " + executionId);
        }
        return copyOf(local);
    }

    private void persist(ScheduledTaskExecution execution, boolean insert) {
        if (canUseDb()) {
            try {
                if (insert) {
                    save(execution);
                } else {
                    updateById(execution);
                }
                return;
            } catch (Exception ex) {
                markDbTemporarilyUnavailable(ex);
            }
        }
        if (execution.getId() == null) {
            execution.setId(-localIdGenerator.getAndIncrement());
        }
        localStore.put(execution.getExecutionId(), copyOf(execution));
    }

    private boolean canUseDb() {
        return dbEnabled && System.currentTimeMillis() >= dbRetryAt;
    }

    private void markDbTemporarilyUnavailable(Exception ex) {
        dbRetryAt = System.currentTimeMillis() + DB_RETRY_INTERVAL_MS;
        String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        log.warn("任务执行记录 DB 不可用，{}ms 内走本地缓存。reason={}", DB_RETRY_INTERVAL_MS, reason);
    }

    private ScheduledTaskExecution copyOf(ScheduledTaskExecution source) {
        ScheduledTaskExecution copy = new ScheduledTaskExecution();
        copy.setId(source.getId());
        copy.setExecutionId(source.getExecutionId());
        copy.setTaskId(source.getTaskId());
        copy.setTriggerSource(source.getTriggerSource());
        copy.setStartedAt(source.getStartedAt());
        copy.setFinishedAt(source.getFinishedAt());
        copy.setStatus(source.getStatus());
        copy.setSummary(source.getSummary());
        copy.setResultPayload(source.getResultPayload());
        copy.setErrorMessage(source.getErrorMessage());
        copy.setRequestId(source.getRequestId());
        copy.setSessionKey(source.getSessionKey());
        copy.setCreateTime(source.getCreateTime());
        copy.setUpdateTime(source.getUpdateTime());
        copy.setCreateBy(source.getCreateBy());
        copy.setUpdateBy(source.getUpdateBy());
        copy.setDeleted(source.getDeleted());
        return copy;
    }
}
