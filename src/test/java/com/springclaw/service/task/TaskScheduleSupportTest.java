package com.springclaw.service.task;

import com.springclaw.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskScheduleSupportTest {

    private final TaskScheduleSupport support = new TaskScheduleSupport();

    @Test
    void shouldParseDailyPreset() {
        LocalDateTime next = support.nextRunAt("preset", "DAILY@09:00", LocalDateTime.of(2026, 4, 22, 8, 0));
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 4, 22, 9, 0));
        assertThat(support.describe("preset", "DAILY@09:00")).isEqualTo("每天 09:00");
    }

    @Test
    void shouldParseWeeklyPreset() {
        LocalDateTime next = support.nextRunAt("preset", "WEEKLY:MON@10:30", LocalDateTime.of(2026, 4, 22, 8, 0));
        assertThat(next.getDayOfWeek().name()).isEqualTo("MONDAY");
        assertThat(next.getHour()).isEqualTo(10);
        assertThat(next.getMinute()).isEqualTo(30);
    }

    @Test
    void shouldRejectInvalidCron() {
        assertThatThrownBy(() -> support.validate("cron", "bad cron"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Cron 表达式非法");
    }
}
