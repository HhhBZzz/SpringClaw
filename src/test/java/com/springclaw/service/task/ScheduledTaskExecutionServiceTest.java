package com.springclaw.service.task;

import com.springclaw.service.task.impl.ScheduledTaskExecutionServiceImpl;
import com.springclaw.service.task.impl.ScheduledTaskServiceImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledTaskExecutionServiceTest {

    @Test
    void shouldDeleteExecutionsByTaskIdInLocalMode() {
        ScheduledTaskServiceImpl taskService = new ScheduledTaskServiceImpl(false, new TaskScheduleSupport());
        ScheduledTaskExecutionServiceImpl executionService = new ScheduledTaskExecutionServiceImpl(false, taskService);

        var task = taskService.createTask("tester", new TaskUpsertCommand(
                "执行记录清理任务",
                true,
                "preset",
                "DAILY@09:00",
                "skill",
                "web_crawler",
                "读取这个网页 https://example.com",
                "api",
                "NONE",
                "",
                false,
                ""
        ));

        executionService.start(task.getTaskId(), "MANUAL", "req_1");
        executionService.start(task.getTaskId(), "MANUAL", "req_2");

        assertThat(executionService.listByTask("tester", "USER", task.getTaskId(), 20)).hasSize(2);

        int deleted = executionService.deleteByTaskId(task.getTaskId());

        assertThat(deleted).isEqualTo(2);
    }
}
