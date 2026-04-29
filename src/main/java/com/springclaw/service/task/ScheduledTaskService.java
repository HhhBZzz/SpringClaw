package com.springclaw.service.task;

import com.baomidou.mybatisplus.extension.service.IService;
import com.springclaw.domain.entity.ScheduledTask;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ScheduledTaskService extends IService<ScheduledTask> {

    ScheduledTask createTask(String ownerUserId, TaskUpsertCommand command);

    ScheduledTask updateTask(String requesterUserId, String requesterRole, String taskId, TaskUpsertCommand command);

    List<ScheduledTask> listTasks(String requesterUserId, String requesterRole, String ownerFilter, Boolean enabledOnly, int limit);

    ScheduledTask getTaskForAccess(String requesterUserId, String requesterRole, String taskId);

    Optional<ScheduledTask> findByTaskId(String taskId);

    ScheduledTask setEnabled(String requesterUserId, String requesterRole, String taskId, boolean enabled);

    void deleteTask(String requesterUserId, String requesterRole, String taskId);

    List<ScheduledTask> listDueTasks(LocalDateTime now, int limit);

    void markRunning(ScheduledTask task, LocalDateTime startedAt, LocalDateTime nextRunAt);

    void markFinished(ScheduledTask task, String finalStatus, LocalDateTime finishedAt);
}
