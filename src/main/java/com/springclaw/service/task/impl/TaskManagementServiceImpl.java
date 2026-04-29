package com.springclaw.service.task.impl;

import com.springclaw.domain.entity.ScheduledTask;
import com.springclaw.service.task.ScheduledTaskExecutionService;
import com.springclaw.service.task.ScheduledTaskService;
import com.springclaw.service.task.TaskManagementService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskManagementServiceImpl implements TaskManagementService {

    private final ScheduledTaskService scheduledTaskService;
    private final ScheduledTaskExecutionService scheduledTaskExecutionService;

    public TaskManagementServiceImpl(ScheduledTaskService scheduledTaskService,
                                     ScheduledTaskExecutionService scheduledTaskExecutionService) {
        this.scheduledTaskService = scheduledTaskService;
        this.scheduledTaskExecutionService = scheduledTaskExecutionService;
    }

    @Override
    @Transactional
    public void deleteTask(String requesterUserId, String requesterRole, String taskId) {
        ScheduledTask task = scheduledTaskService.getTaskForAccess(requesterUserId, requesterRole, taskId);
        scheduledTaskExecutionService.deleteByTaskId(task.getTaskId());
        scheduledTaskService.deleteTask(requesterUserId, requesterRole, task.getTaskId());
    }
}
