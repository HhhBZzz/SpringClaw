package com.springclaw.service.task;

import com.springclaw.service.task.impl.ScheduledTaskServiceImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledTaskServiceTest {

    @Test
    void shouldCreateAndDisableTaskInLocalMode() {
        ScheduledTaskServiceImpl service = new ScheduledTaskServiceImpl(false, new TaskScheduleSupport());

        var created = service.createTask("tester", new TaskUpsertCommand(
                "网页抓取任务",
                true,
                "preset",
                "DAILY@09:00",
                "skill",
                "web_crawler",
                "读取这个网页 https://example.com",
                "feishu",
                "NONE",
                "",
                false,
                ""
        ));

        assertThat(created.getTaskId()).startsWith("task_");
        assertThat(service.listTasks("tester", "USER", null, null, 20)).hasSize(1);

        var disabled = service.setEnabled("tester", "USER", created.getTaskId(), false);
        assertThat(disabled.getLastStatus()).isEqualTo("DISABLED");
        assertThat(disabled.getEnabled()).isEqualTo(0);
    }

    @Test
    void shouldDeleteTaskInLocalMode() {
        ScheduledTaskServiceImpl service = new ScheduledTaskServiceImpl(false, new TaskScheduleSupport());

        var created = service.createTask("tester", new TaskUpsertCommand(
                "待删除任务",
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

        assertThat(service.listTasks("tester", "USER", null, null, 20)).hasSize(1);

        service.deleteTask("tester", "USER", created.getTaskId());

        assertThat(service.listTasks("tester", "USER", null, null, 20)).isEmpty();
        assertThat(service.findByTaskId(created.getTaskId())).isEmpty();
    }
}
