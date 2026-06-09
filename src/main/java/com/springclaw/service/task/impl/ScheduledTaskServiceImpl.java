package com.springclaw.service.task.impl;

import com.springclaw.common.util.TextUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.domain.entity.ScheduledTask;
import com.springclaw.mapper.ScheduledTaskMapper;
import com.springclaw.service.task.ScheduledTaskService;
import com.springclaw.service.task.TaskScheduleSupport;
import com.springclaw.service.task.TaskUpsertCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ScheduledTaskServiceImpl extends ServiceImpl<ScheduledTaskMapper, ScheduledTask> implements ScheduledTaskService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskServiceImpl.class);
    private static final long DB_RETRY_INTERVAL_MS = 30_000L;

    private final boolean dbEnabled;
    private final TaskScheduleSupport taskScheduleSupport;
    private final Map<String, ScheduledTask> localStore = new ConcurrentHashMap<>();
    private final AtomicLong localIdGenerator = new AtomicLong(1);
    private volatile long dbRetryAt = 0L;

    public ScheduledTaskServiceImpl(@Value("${springclaw.persistence.db-enabled:false}") boolean dbEnabled,
                                    TaskScheduleSupport taskScheduleSupport) {
        this.dbEnabled = dbEnabled;
        this.taskScheduleSupport = taskScheduleSupport;
    }

    @Override
    public ScheduledTask createTask(String ownerUserId, TaskUpsertCommand command) {
        String owner = normalizeOwner(ownerUserId);
        TaskState normalized = normalizeCommand(command, null, LocalDateTime.now());
        ScheduledTask task = new ScheduledTask();
        task.setTaskId(generateTaskId());
        task.setOwnerUserId(owner);
        applyState(task, normalized);
        persistTask(task, true);
        return copyOf(task);
    }

    @Override
    public ScheduledTask updateTask(String requesterUserId, String requesterRole, String taskId, TaskUpsertCommand command) {
        ScheduledTask current = getTaskForAccess(requesterUserId, requesterRole, taskId);
        TaskState normalized = normalizeCommand(command, current, LocalDateTime.now());
        applyState(current, normalized);
        persistTask(current, false);
        return copyOf(current);
    }

    @Override
    public List<ScheduledTask> listTasks(String requesterUserId, String requesterRole, String ownerFilter, Boolean enabledOnly, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        if (canUseDb()) {
            try {
                LambdaQueryWrapper<ScheduledTask> query = new LambdaQueryWrapper<>();
                if (isAdmin(requesterRole)) {
                    if (StringUtils.hasText(ownerFilter)) {
                        query.eq(ScheduledTask::getOwnerUserId, normalizeOwner(ownerFilter));
                    }
                } else {
                    query.eq(ScheduledTask::getOwnerUserId, normalizeOwner(requesterUserId));
                }
                if (enabledOnly != null) {
                    query.eq(ScheduledTask::getEnabled, enabledOnly ? 1 : 0);
                }
                return list(query.orderByDesc(ScheduledTask::getUpdateTime)
                                .last("limit " + safeLimit))
                        .stream()
                        .map(this::copyOf)
                        .toList();
            } catch (Exception ex) {
                markDbTemporarilyUnavailable(ex);
            }
        }
        return localStore.values().stream()
                .filter(task -> canAccess(task, requesterUserId, requesterRole))
                .filter(task -> !StringUtils.hasText(ownerFilter) || normalizeOwner(ownerFilter).equals(task.getOwnerUserId()))
                .filter(task -> enabledOnly == null || (enabledOnly ? isEnabled(task) : !isEnabled(task)))
                .sorted(Comparator.comparing(ScheduledTask::getUpdateTime, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(safeLimit)
                .map(this::copyOf)
                .toList();
    }

    @Override
    public ScheduledTask getTaskForAccess(String requesterUserId, String requesterRole, String taskId) {
        ScheduledTask task = findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(40461, "定时任务不存在: " + taskId));
        if (!canAccess(task, requesterUserId, requesterRole)) {
            throw new BusinessException(40361, "无权访问该定时任务");
        }
        return copyOf(task);
    }

    @Override
    public Optional<ScheduledTask> findByTaskId(String taskId) {
        String safeTaskId = TextUtils.safe(taskId);
        if (!StringUtils.hasText(safeTaskId)) {
            return Optional.empty();
        }
        if (canUseDb()) {
            try {
                ScheduledTask task = lambdaQuery().eq(ScheduledTask::getTaskId, safeTaskId).one();
                if (task != null) {
                    return Optional.of(copyOf(task));
                }
            } catch (Exception ex) {
                markDbTemporarilyUnavailable(ex);
            }
        }
        ScheduledTask local = localStore.get(safeTaskId);
        return local == null ? Optional.empty() : Optional.of(copyOf(local));
    }

    @Override
    public ScheduledTask setEnabled(String requesterUserId, String requesterRole, String taskId, boolean enabled) {
        ScheduledTask task = getTaskForAccess(requesterUserId, requesterRole, taskId);
        task.setEnabled(enabled ? 1 : 0);
        if (enabled) {
            task.setNextRunAt(taskScheduleSupport.nextRunAt(task.getScheduleType(), task.getScheduleExpression(), LocalDateTime.now()));
            task.setLastStatus("READY");
        } else {
            task.setLastStatus("DISABLED");
        }
        persistTask(task, false);
        return copyOf(task);
    }

    @Override
    public void deleteTask(String requesterUserId, String requesterRole, String taskId) {
        ScheduledTask task = getTaskForAccess(requesterUserId, requesterRole, taskId);
        if (canUseDb()) {
            try {
                lambdaUpdate()
                        .eq(ScheduledTask::getTaskId, task.getTaskId())
                        .remove();
                return;
            } catch (Exception ex) {
                markDbTemporarilyUnavailable(ex);
            }
        }
        localStore.remove(task.getTaskId());
    }

    @Override
    public List<ScheduledTask> listDueTasks(LocalDateTime now, int limit) {
        LocalDateTime safeNow = now == null ? LocalDateTime.now() : now;
        int safeLimit = Math.max(1, Math.min(limit, 200));
        if (canUseDb()) {
            try {
                return list(lambdaQuery()
                        .eq(ScheduledTask::getEnabled, 1)
                        .isNotNull(ScheduledTask::getNextRunAt)
                        .le(ScheduledTask::getNextRunAt, safeNow)
                        .orderByAsc(ScheduledTask::getNextRunAt)
                        .last("limit " + safeLimit)).stream()
                        .map(this::copyOf)
                        .toList();
            } catch (Exception ex) {
                markDbTemporarilyUnavailable(ex);
            }
        }
        return localStore.values().stream()
                .filter(this::isEnabled)
                .filter(task -> task.getNextRunAt() != null && !task.getNextRunAt().isAfter(safeNow))
                .sorted(Comparator.comparing(ScheduledTask::getNextRunAt))
                .limit(safeLimit)
                .map(this::copyOf)
                .toList();
    }

    @Override
    public void markRunning(ScheduledTask task, LocalDateTime startedAt, LocalDateTime nextRunAt) {
        ScheduledTask current = loadMutable(task.getTaskId());
        current.setLastRunAt(startedAt);
        current.setNextRunAt(nextRunAt);
        current.setLastStatus("RUNNING");
        persistTask(current, false);
    }

    @Override
    public void markFinished(ScheduledTask task, String finalStatus, LocalDateTime finishedAt) {
        ScheduledTask current = loadMutable(task.getTaskId());
        current.setLastStatus(StringUtils.hasText(finalStatus) ? finalStatus.trim().toUpperCase(Locale.ROOT) : "SUCCESS");
        current.setUpdateTime(finishedAt);
        persistTask(current, false);
    }

    private ScheduledTask loadMutable(String taskId) {
        return findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(40461, "定时任务不存在: " + taskId));
    }

    private void persistTask(ScheduledTask task, boolean insert) {
        if (task.getEnabled() == null) {
            task.setEnabled(1);
        }
        if (canUseDb()) {
            try {
                if (insert) {
                    save(task);
                } else {
                    updateById(task);
                }
                return;
            } catch (Exception ex) {
                markDbTemporarilyUnavailable(ex);
            }
        }
        if (task.getId() == null) {
            task.setId(-localIdGenerator.getAndIncrement());
        }
        localStore.put(task.getTaskId(), copyOf(task));
    }

    private TaskState normalizeCommand(TaskUpsertCommand command, ScheduledTask existing, LocalDateTime now) {
        if (command == null && existing == null) {
            throw new BusinessException(40060, "任务配置不能为空");
        }
        String name = pick(command == null ? null : command.name(), existing == null ? null : existing.getName());
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(40062, "任务名称不能为空");
        }
        String scheduleType = normalizeScheduleType(pick(command == null ? null : command.scheduleType(), existing == null ? null : existing.getScheduleType()));
        String scheduleExpression = pick(command == null ? null : command.scheduleExpression(), existing == null ? null : existing.getScheduleExpression());
        taskScheduleSupport.validate(scheduleType, scheduleExpression);
        String targetType = normalizeTargetType(pick(command == null ? null : command.targetType(), existing == null ? null : existing.getTargetType()));
        String targetRef = pick(command == null ? null : command.targetRef(), existing == null ? null : existing.getTargetRef());
        if (!StringUtils.hasText(targetRef)) {
            targetRef = "agent".equals(targetType) ? "prompt" : "";
        }
        if (!StringUtils.hasText(targetRef)) {
            throw new BusinessException(40063, "targetRef 不能为空");
        }
        String inputPayload = pick(command == null ? null : command.inputPayload(), existing == null ? null : existing.getInputPayload());
        if ("agent".equals(targetType) && !StringUtils.hasText(inputPayload)) {
            throw new BusinessException(40064, "Agent 任务需要 inputPayload/message");
        }
        String channel = normalizeChannel(pick(command == null ? null : command.channel(), existing == null ? null : existing.getChannel()));
        String deliveryMode = normalizeDeliveryMode(pick(command == null ? null : command.deliveryMode(), existing == null ? null : existing.getDeliveryMode()));
        String deliveryTarget = pick(command == null ? null : command.deliveryTarget(), existing == null ? null : existing.getDeliveryTarget());
        boolean persistToSession = boolValue(command == null ? null : command.persistToSession(), existing != null && existing.getPersistToSession() != null && existing.getPersistToSession() == 1);
        boolean enabled = boolValue(command == null ? null : command.enabled(), existing == null || isEnabled(existing));
        String sessionKeyTemplate = pick(command == null ? null : command.sessionKeyTemplate(), existing == null ? null : existing.getSessionKeyTemplate());
        LocalDateTime nextRunAt = enabled ? taskScheduleSupport.nextRunAt(scheduleType, scheduleExpression, now) : existing == null ? null : existing.getNextRunAt();
        String lastStatus = enabled ? (existing == null ? "READY" : TextUtils.safe(existing.getLastStatus(), "READY")) : "DISABLED";
        return new TaskState(name.trim(), enabled, scheduleType, scheduleExpression.trim(), targetType, targetRef.trim(),
                TextUtils.safe(inputPayload), channel, deliveryMode, TextUtils.safe(deliveryTarget), persistToSession, TextUtils.safe(sessionKeyTemplate), nextRunAt, lastStatus);
    }

    private void applyState(ScheduledTask task, TaskState state) {
        task.setName(state.name());
        task.setEnabled(state.enabled() ? 1 : 0);
        task.setScheduleType(state.scheduleType());
        task.setScheduleExpression(state.scheduleExpression());
        task.setTargetType(state.targetType());
        task.setTargetRef(state.targetRef());
        task.setInputPayload(state.inputPayload());
        task.setChannel(state.channel());
        task.setDeliveryMode(state.deliveryMode());
        task.setDeliveryTarget(state.deliveryTarget());
        task.setPersistToSession(state.persistToSession() ? 1 : 0);
        task.setSessionKeyTemplate(state.sessionKeyTemplate());
        task.setNextRunAt(state.nextRunAt());
        task.setLastStatus(state.lastStatus());
    }

    private boolean canAccess(ScheduledTask task, String requesterUserId, String requesterRole) {
        return isAdmin(requesterRole) || normalizeOwner(requesterUserId).equals(task.getOwnerUserId());
    }

    private boolean isEnabled(ScheduledTask task) {
        return task.getEnabled() == null || task.getEnabled() == 1;
    }

    private boolean canUseDb() {
        return dbEnabled && System.currentTimeMillis() >= dbRetryAt;
    }

    private void markDbTemporarilyUnavailable(Exception ex) {
        dbRetryAt = System.currentTimeMillis() + DB_RETRY_INTERVAL_MS;
        String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        log.warn("定时任务 DB 不可用，{}ms 内走本地缓存。reason={}", DB_RETRY_INTERVAL_MS, reason);
    }

    private String normalizeOwner(String ownerUserId) {
        if (!StringUtils.hasText(ownerUserId)) {
            throw new BusinessException(40061, "ownerUserId 不能为空");
        }
        return ownerUserId.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeScheduleType(String scheduleType) {
        String value = TextUtils.safe(scheduleType).toLowerCase(Locale.ROOT);
        if (!"preset".equals(value) && !"cron".equals(value)) {
            throw new BusinessException(40070, "scheduleType 仅支持 preset 或 cron");
        }
        return value;
    }

    private String normalizeTargetType(String targetType) {
        String value = TextUtils.safe(targetType).toLowerCase(Locale.ROOT);
        if (!"skill".equals(value) && !"agent".equals(value)) {
            throw new BusinessException(40065, "targetType 仅支持 skill 或 agent");
        }
        return value;
    }

    private String normalizeChannel(String channel) {
        return StringUtils.hasText(channel) ? channel.trim().toLowerCase(Locale.ROOT) : "api";
    }

    private String normalizeDeliveryMode(String deliveryMode) {
        String value = StringUtils.hasText(deliveryMode) ? deliveryMode.trim().toUpperCase(Locale.ROOT) : "NONE";
        if (!List.of("NONE", "FEISHU").contains(value)) {
            throw new BusinessException(40066, "deliveryMode 仅支持 NONE 或 FEISHU");
        }
        return value;
    }

    private boolean isAdmin(String roleCode) {
        return "ADMIN".equalsIgnoreCase(TextUtils.safe(roleCode));
    }

    private boolean boolValue(Boolean override, boolean defaultValue) {
        return override == null ? defaultValue : override;
    }

    private String generateTaskId() {
        return "task_" + UUID.randomUUID().toString().replace("-", "");
    }

    private ScheduledTask copyOf(ScheduledTask source) {
        ScheduledTask copy = new ScheduledTask();
        copy.setId(source.getId());
        copy.setTaskId(source.getTaskId());
        copy.setOwnerUserId(source.getOwnerUserId());
        copy.setName(source.getName());
        copy.setEnabled(source.getEnabled());
        copy.setScheduleType(source.getScheduleType());
        copy.setScheduleExpression(source.getScheduleExpression());
        copy.setTargetType(source.getTargetType());
        copy.setTargetRef(source.getTargetRef());
        copy.setInputPayload(source.getInputPayload());
        copy.setChannel(source.getChannel());
        copy.setDeliveryMode(source.getDeliveryMode());
        copy.setDeliveryTarget(source.getDeliveryTarget());
        copy.setPersistToSession(source.getPersistToSession());
        copy.setSessionKeyTemplate(source.getSessionKeyTemplate());
        copy.setLastRunAt(source.getLastRunAt());
        copy.setNextRunAt(source.getNextRunAt());
        copy.setLastStatus(source.getLastStatus());
        copy.setCreateTime(source.getCreateTime());
        copy.setUpdateTime(source.getUpdateTime());
        copy.setCreateBy(source.getCreateBy());
        copy.setUpdateBy(source.getUpdateBy());
        copy.setDeleted(source.getDeleted());
        return copy;
    }

    private String pick(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private record TaskState(String name,
                             boolean enabled,
                             String scheduleType,
                             String scheduleExpression,
                             String targetType,
                             String targetRef,
                             String inputPayload,
                             String channel,
                             String deliveryMode,
                             String deliveryTarget,
                             boolean persistToSession,
                             String sessionKeyTemplate,
                             LocalDateTime nextRunAt,
                             String lastStatus) {
    }
}
