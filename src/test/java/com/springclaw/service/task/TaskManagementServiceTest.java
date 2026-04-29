package com.springclaw.service.task;

import com.springclaw.domain.entity.ScheduledTask;
import com.springclaw.service.task.impl.TaskManagementServiceImpl;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskManagementServiceTest {

    @Test
    void shouldDeleteTaskAndExecutionsTogether() {
        ScheduledTaskService scheduledTaskService = mock(ScheduledTaskService.class);
        ScheduledTaskExecutionService scheduledTaskExecutionService = mock(ScheduledTaskExecutionService.class);
        TaskManagementService service = new TaskManagementServiceImpl(scheduledTaskService, scheduledTaskExecutionService);

        ScheduledTask task = new ScheduledTask();
        task.setTaskId("task_1");

        when(scheduledTaskService.getTaskForAccess("tester", "USER", "task_1")).thenReturn(task);

        service.deleteTask("tester", "USER", "task_1");

        verify(scheduledTaskService).getTaskForAccess("tester", "USER", "task_1");
        verify(scheduledTaskExecutionService).deleteByTaskId("task_1");
        verify(scheduledTaskService).deleteTask("tester", "USER", "task_1");
    }
}
