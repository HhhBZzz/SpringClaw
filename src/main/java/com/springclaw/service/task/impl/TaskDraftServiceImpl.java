package com.springclaw.service.task.impl;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.skill.impl.SkillServiceImpl;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
import com.springclaw.service.task.TaskCreationDraft;
import com.springclaw.service.task.TaskDraftService;
import com.springclaw.service.task.TaskScheduleSupport;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TaskDraftServiceImpl implements TaskDraftService {

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CRON_PATTERN = Pattern.compile("(?:cron[:：]?\\s*)([\\w*/?, -]{9,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern DAILY_PATTERN = Pattern.compile("每天\\s*(?:上午|中午|下午|晚上)?\\s*(\\d{1,2})(?:[:点时](\\d{1,2}))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEEKLY_PATTERN = Pattern.compile("每周([一二三四五六日天1-7])\\s*(?:上午|中午|下午|晚上)?\\s*(\\d{1,2})(?:[:点时](\\d{1,2}))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern MONTHLY_PATTERN = Pattern.compile("每月\\s*(\\d{1,2})[号日]?\\s*(?:上午|中午|下午|晚上)?\\s*(\\d{1,2})(?:[:点时](\\d{1,2}))?", Pattern.CASE_INSENSITIVE);

    private final TaskScheduleSupport taskScheduleSupport;
    private final ScriptSkillCatalogService scriptSkillCatalogService;
    private final SkillRegistryService skillRegistryService;

    public TaskDraftServiceImpl(TaskScheduleSupport taskScheduleSupport,
                                ScriptSkillCatalogService scriptSkillCatalogService,
                                SkillRegistryService skillRegistryService) {
        this.taskScheduleSupport = taskScheduleSupport;
        this.scriptSkillCatalogService = scriptSkillCatalogService;
        this.skillRegistryService = skillRegistryService;
    }

    @Override
    public TaskCreationDraft parseDraft(String ownerUserId, String channel, String message) {
        if (!StringUtils.hasText(message)) {
            throw new BusinessException(40074, "任务草稿消息不能为空");
        }
        String question = message.trim();
        ScheduleDraft schedule = parseSchedule(question);
        TargetDraft target = parseTarget(question);
        String deliveryMode = "NONE";
        String deliveryTarget = "";
        boolean persistToSession = false;
        if (question.contains("发到飞书") || question.contains("推送到飞书") || question.contains("回到当前会话") || question.contains("发回当前会话")) {
            deliveryMode = "FEISHU";
            deliveryTarget = StringUtils.hasText(channel) && channel.toLowerCase(Locale.ROOT).contains("feishu") ? "CURRENT_SESSION" : "";
            persistToSession = true;
        }
        String taskName = buildTaskName(target, schedule);
        String summary = "将创建任务：%s；调度=%s；执行=%s/%s".formatted(taskName, schedule.label(), target.targetType(), target.targetRef());
        return new TaskCreationDraft(
                taskName,
                schedule.scheduleType(),
                schedule.scheduleExpression(),
                schedule.label(),
                target.targetType(),
                target.targetRef(),
                target.payload(),
                StringUtils.hasText(channel) ? channel.trim().toLowerCase(Locale.ROOT) : "api",
                deliveryMode,
                deliveryTarget,
                persistToSession,
                persistToSession ? "task:{taskId}" : "",
                summary
        );
    }

    private ScheduleDraft parseSchedule(String question) {
        Matcher cronMatcher = CRON_PATTERN.matcher(question);
        if (cronMatcher.find()) {
            String expression = cronMatcher.group(1).trim();
            taskScheduleSupport.validate("cron", expression);
            return new ScheduleDraft("cron", expression, taskScheduleSupport.describe("cron", expression));
        }
        Matcher daily = DAILY_PATTERN.matcher(question);
        if (daily.find()) {
            String expression = "DAILY@%02d:%02d".formatted(parseHour(daily.group(1), question), parseMinute(daily.group(2)));
            return new ScheduleDraft("preset", expression, taskScheduleSupport.describe("preset", expression));
        }
        Matcher weekly = WEEKLY_PATTERN.matcher(question);
        if (weekly.find()) {
            String expression = "WEEKLY:%s@%02d:%02d".formatted(toWeekDay(weekly.group(1)), parseHour(weekly.group(2), question), parseMinute(weekly.group(3)));
            return new ScheduleDraft("preset", expression, taskScheduleSupport.describe("preset", expression));
        }
        Matcher monthly = MONTHLY_PATTERN.matcher(question);
        if (monthly.find()) {
            String expression = "MONTHLY:%d@%02d:%02d".formatted(Integer.parseInt(monthly.group(1)), parseHour(monthly.group(2), question), parseMinute(monthly.group(3)));
            return new ScheduleDraft("preset", expression, taskScheduleSupport.describe("preset", expression));
        }
        throw new BusinessException(40075, "暂时只支持‘每天/每周/每月/cron’这几种定时表达，请补充明确时间");
    }

    private TargetDraft parseTarget(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        String explicitSkill = extractExplicitSkill(question);
        if (StringUtils.hasText(explicitSkill)) {
            return new TargetDraft("skill", explicitSkill, question);
        }
        Matcher urlMatcher = URL_PATTERN.matcher(question);
        if (urlMatcher.find() && containsAny(lower, "抓", "爬", "读取", "网页", "链接", "crawl", "fetch")) {
            return new TargetDraft("skill", "web_crawler", question);
        }
        if (containsAny(lower, "boss", "直聘", "岗位", "职位")) {
            return new TargetDraft("skill", "boss_authorized_collector", question);
        }
        if (containsAny(lower, "代码分析", "分析代码", "分析项目")) {
            return new TargetDraft("skill", "code-analysis", question);
        }
        if (containsAny(lower, "日志", "报错", "堆栈", "异常")) {
            return new TargetDraft("skill", "log-diagnostics", question);
        }
        return new TargetDraft("agent", "prompt", stripScheduleWords(question));
    }

    private String extractExplicitSkill(String question) {
        Matcher matcher = Pattern.compile("(?:skill|技能)[:：\\s]+([a-zA-Z0-9_-]{2,64})").matcher(question);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return scriptSkillCatalogService.matchBestDefinition(question)
                .filter(definition -> question.toLowerCase(Locale.ROOT).contains(definition.skillName().toLowerCase(Locale.ROOT)))
                .map(definition -> definition.skillName())
                .orElseGet(() -> skillRegistryService.listAllDefinitions().stream()
                        .filter(definition -> StringUtils.hasText(definition.skillId()))
                        .filter(definition -> question.toLowerCase(Locale.ROOT).contains(definition.skillId().toLowerCase(Locale.ROOT)))
                        .map(definition -> definition.skillId())
                        .findFirst()
                        .orElse(""));
    }

    private String stripScheduleWords(String question) {
        String cleaned = CRON_PATTERN.matcher(question).replaceAll("");
        cleaned = DAILY_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = WEEKLY_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = MONTHLY_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replace("帮我", "").replace("请", "").trim();
        return StringUtils.hasText(cleaned) ? cleaned : question.trim();
    }

    private String buildTaskName(TargetDraft target, ScheduleDraft schedule) {
        String targetName = switch (target.targetRef()) {
            case "web_crawler" -> "网页抓取";
            case "boss_authorized_collector" -> "BOSS 采集";
            case "code-analysis" -> "代码分析";
            case "log-diagnostics" -> "日志诊断";
            default -> "agent".equals(target.targetType()) ? "Agent 任务" : target.targetRef();
        };
        return targetName + " - " + schedule.label();
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private int parseHour(String rawHour, String question) {
        int hour = Integer.parseInt(rawHour);
        if (question.contains("下午") || question.contains("晚上")) {
            if (hour < 12) {
                hour += 12;
            }
        }
        if (hour < 0 || hour > 23) {
            throw new BusinessException(40076, "小时非法: " + rawHour);
        }
        return hour;
    }

    private int parseMinute(String rawMinute) {
        if (!StringUtils.hasText(rawMinute)) {
            return 0;
        }
        int minute = Integer.parseInt(rawMinute);
        if (minute < 0 || minute > 59) {
            throw new BusinessException(40077, "分钟非法: " + rawMinute);
        }
        return minute;
    }

    private String toWeekDay(String raw) {
        return switch (raw) {
            case "1", "一" -> "MON";
            case "2", "二" -> "TUE";
            case "3", "三" -> "WED";
            case "4", "四" -> "THU";
            case "5", "五" -> "FRI";
            case "6", "六" -> "SAT";
            case "7", "日", "天" -> "SUN";
            default -> throw new BusinessException(40078, "星期非法: " + raw);
        };
    }

    private record ScheduleDraft(String scheduleType, String scheduleExpression, String label) {
    }

    private record TargetDraft(String targetType, String targetRef, String payload) {
    }
}
