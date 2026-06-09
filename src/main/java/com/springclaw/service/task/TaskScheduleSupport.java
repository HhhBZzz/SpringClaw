package com.springclaw.service.task;

import com.springclaw.common.util.TextUtils;
import com.springclaw.common.exception.BusinessException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调度表达式解析与下次触发计算。
 */
@Component
public class TaskScheduleSupport {

    private static final Pattern DAILY_PRESET = Pattern.compile("^DAILY@(\\d{1,2}):(\\d{2})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKLY_PRESET = Pattern.compile("^WEEKLY:([A-Z]{3})@(\\d{1,2}):(\\d{2})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTHLY_PRESET = Pattern.compile("^MONTHLY:(\\d{1,2})@(\\d{1,2}):(\\d{2})$", Pattern.CASE_INSENSITIVE);

    public LocalDateTime nextRunAt(String scheduleType, String scheduleExpression, LocalDateTime baseTime) {
        LocalDateTime base = baseTime == null ? LocalDateTime.now() : baseTime;
        String cron = toCronExpression(scheduleType, scheduleExpression);
        CronExpression cronExpression = parseCron(cron);
        LocalDateTime next = cronExpression.next(base);
        if (next == null) {
            throw new BusinessException(40072, "无法计算下次执行时间");
        }
        return next;
    }

    public void validate(String scheduleType, String scheduleExpression) {
        toCronExpression(scheduleType, scheduleExpression);
    }

    public String describe(String scheduleType, String scheduleExpression) {
        String normalizedType = normalizeScheduleType(scheduleType);
        String safeExpression = TextUtils.safe(scheduleExpression);
        return switch (normalizedType) {
            case "preset" -> describePreset(safeExpression);
            case "cron" -> "Cron(" + safeExpression + ")";
            default -> throw new BusinessException(40070, "不支持的调度类型: " + normalizedType);
        };
    }

    public String toCronExpression(String scheduleType, String scheduleExpression) {
        String normalizedType = normalizeScheduleType(scheduleType);
        String safeExpression = TextUtils.safe(scheduleExpression);
        return switch (normalizedType) {
            case "preset" -> presetToCron(safeExpression);
            case "cron" -> {
                parseCron(safeExpression);
                yield safeExpression;
            }
            default -> throw new BusinessException(40070, "不支持的调度类型: " + normalizedType);
        };
    }

    private String presetToCron(String presetExpression) {
        Matcher daily = DAILY_PRESET.matcher(presetExpression);
        if (daily.matches()) {
            int hour = parseHour(daily.group(1));
            int minute = parseMinute(daily.group(2));
            return "0 %d %d * * *".formatted(minute, hour);
        }
        Matcher weekly = WEEKLY_PRESET.matcher(presetExpression);
        if (weekly.matches()) {
            int hour = parseHour(weekly.group(2));
            int minute = parseMinute(weekly.group(3));
            String day = normalizeWeekDay(weekly.group(1));
            return "0 %d %d * * %s".formatted(minute, hour, day);
        }
        Matcher monthly = MONTHLY_PRESET.matcher(presetExpression);
        if (monthly.matches()) {
            int day = Integer.parseInt(monthly.group(1));
            if (day < 1 || day > 31) {
                throw new BusinessException(40071, "每月预设的日期必须在 1-31 之间");
            }
            int hour = parseHour(monthly.group(2));
            int minute = parseMinute(monthly.group(3));
            return "0 %d %d %d * *".formatted(minute, hour, day);
        }
        throw new BusinessException(40071, "不支持的预设调度表达式: " + presetExpression);
    }

    private String describePreset(String presetExpression) {
        Matcher daily = DAILY_PRESET.matcher(presetExpression);
        if (daily.matches()) {
            return "每天 %s:%s".formatted(twoDigits(daily.group(1)), daily.group(2));
        }
        Matcher weekly = WEEKLY_PRESET.matcher(presetExpression);
        if (weekly.matches()) {
            return "每周%s %s:%s".formatted(renderWeekDay(weekly.group(1)), twoDigits(weekly.group(2)), weekly.group(3));
        }
        Matcher monthly = MONTHLY_PRESET.matcher(presetExpression);
        if (monthly.matches()) {
            return "每月 %s 号 %s:%s".formatted(monthly.group(1), twoDigits(monthly.group(2)), monthly.group(3));
        }
        return presetExpression;
    }

    private CronExpression parseCron(String cronExpression) {
        if (!StringUtils.hasText(cronExpression)) {
            throw new BusinessException(40070, "调度表达式不能为空");
        }
        try {
            return CronExpression.parse(cronExpression.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(40073, "Cron 表达式非法: " + ex.getMessage());
        }
    }

    private String normalizeScheduleType(String scheduleType) {
        String normalized = TextUtils.safe(scheduleType).toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(40070, "scheduleType 不能为空");
        }
        return normalized;
    }

    private int parseHour(String rawHour) {
        int hour = Integer.parseInt(rawHour);
        if (hour < 0 || hour > 23) {
            throw new BusinessException(40071, "小时必须在 0-23 之间");
        }
        return hour;
    }

    private int parseMinute(String rawMinute) {
        int minute = Integer.parseInt(rawMinute);
        if (minute < 0 || minute > 59) {
            throw new BusinessException(40071, "分钟必须在 0-59 之间");
        }
        return minute;
    }

    private String normalizeWeekDay(String rawDay) {
        String day = TextUtils.safe(rawDay).toUpperCase(Locale.ROOT);
        return switch (day) {
            case "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN" -> day;
            default -> throw new BusinessException(40071, "每周预设的星期非法: " + rawDay);
        };
    }

    private String renderWeekDay(String rawDay) {
        return switch (normalizeWeekDay(rawDay)) {
            case "MON" -> "一";
            case "TUE" -> "二";
            case "WED" -> "三";
            case "THU" -> "四";
            case "FRI" -> "五";
            case "SAT" -> "六";
            case "SUN" -> "日";
            default -> rawDay;
        };
    }

    private String twoDigits(String raw) {
        return DateTimeFormatter.ofPattern("HH").format(LocalDateTime.of(2000, 1, 1, Integer.parseInt(raw), 0));
    }

}
