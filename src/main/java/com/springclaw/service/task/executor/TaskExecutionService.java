package com.springclaw.service.task.executor;

import com.springclaw.common.util.TextUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.domain.entity.AgentSession;
import com.springclaw.domain.entity.ScheduledTask;
import com.springclaw.domain.entity.ScheduledTaskExecution;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.runtime.bridge.LegacyRuntimeBridge;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.chat.impl.ChatServiceImpl;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.MemoryService;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.session.AgentSessionService;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.runtime.SkillRuntimeService;
import com.springclaw.service.task.ScheduledTaskExecutionService;
import com.springclaw.service.task.ScheduledTaskService;
import com.springclaw.service.task.TaskExecutionOutcome;
import com.springclaw.service.task.TaskScheduleSupport;
import com.springclaw.strategy.channel.model.UnifiedInboundMessage;
import com.springclaw.strategy.channel.outbound.ChannelOutboundDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);

    private static final Duration RUN_DEADLINE = Duration.ofMinutes(30);

    private final ScheduledTaskService scheduledTaskService;
    private final ScheduledTaskExecutionService scheduledTaskExecutionService;
    private final TaskScheduleSupport taskScheduleSupport;
    private final SkillRuntimeService skillRuntimeService;
    private final SkillService skillService;
    private final ChatServiceImpl chatService;
    private final AgentSessionService agentSessionService;
    private final MemoryService memoryService;
    private final MessageEventService messageEventService;
    private final SoulPromptService soulPromptService;
    private final ChannelOutboundDispatcher channelOutboundDispatcher;
    private final AuthService authService;
    private final LegacyRuntimeBridge runtimeBridge;
    private final ObjectMapper objectMapper;
    private final boolean feishuDeliveryEnabled;

    public TaskExecutionService(ScheduledTaskService scheduledTaskService,
                                ScheduledTaskExecutionService scheduledTaskExecutionService,
                                TaskScheduleSupport taskScheduleSupport,
                                SkillRuntimeService skillRuntimeService,
                                SkillService skillService,
                                ChatServiceImpl chatService,
                                AgentSessionService agentSessionService,
                                MemoryService memoryService,
                                MessageEventService messageEventService,
                                SoulPromptService soulPromptService,
                                ChannelOutboundDispatcher channelOutboundDispatcher,
                                AuthService authService,
                                LegacyRuntimeBridge runtimeBridge,
                                ObjectMapper objectMapper,
                                @Value("${springclaw.task.delivery.feishu-enabled:true}") boolean feishuDeliveryEnabled) {
        this.scheduledTaskService = scheduledTaskService;
        this.scheduledTaskExecutionService = scheduledTaskExecutionService;
        this.taskScheduleSupport = taskScheduleSupport;
        this.skillRuntimeService = skillRuntimeService;
        this.skillService = skillService;
        this.chatService = chatService;
        this.agentSessionService = agentSessionService;
        this.memoryService = memoryService;
        this.messageEventService = messageEventService;
        this.soulPromptService = soulPromptService;
        this.channelOutboundDispatcher = channelOutboundDispatcher;
        this.authService = authService;
        this.runtimeBridge = runtimeBridge;
        this.objectMapper = objectMapper;
        this.feishuDeliveryEnabled = feishuDeliveryEnabled;
    }

    public TaskExecutionOutcome runTask(ScheduledTask task, String triggerSource) {
        LocalDateTime startedAt = LocalDateTime.now();
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String sessionKey = resolveTaskSessionKey(task);
        String channel = TextUtils.safe(task.getChannel(), "api");
        ScheduledTaskExecution execution = null;
        try {
            if (!"CRON".equalsIgnoreCase(TextUtils.safe(triggerSource))) {
                scheduledTaskService.markRunning(task, startedAt, task.getNextRunAt());
            }
            String originalMessage = renderTaskPrompt(task);
            String responseMode = "agent".equalsIgnoreCase(task.getTargetType())
                    ? "agent"
                    : "skill";
            Instant acceptedAt = Instant.now();
            runtimeBridge.accepted(new RunAcceptance(
                    requestId,
                    sessionKey,
                    channel,
                    task.getOwnerUserId(),
                    SessionAccessClaim.personal(
                            SessionAccessClaim.AcceptanceOrigin.SCHEDULED_TASK,
                            channel,
                            sessionKey,
                            task.getOwnerUserId()
                    ),
                    authService.resolveRoleByUserId(task.getOwnerUserId()),
                    originalMessage,
                    responseMode,
                    acceptedAt,
                    acceptedAt.plus(RUN_DEADLINE)
            ));
            execution = scheduledTaskExecutionService.start(
                    task.getTaskId(),
                    triggerSource,
                    requestId
            );
            TaskExecutionOutcome outcome = switch (TextUtils.normalize(task.getTargetType())) {
                case "skill" -> executeSkillTask(task, requestId);
                case "agent" -> executeAgentTask(task, requestId, originalMessage);
                default -> throw new BusinessException(40079, "不支持的任务目标类型: " + task.getTargetType());
            };
            if (shouldPersistToSession(task) && !"agent".equalsIgnoreCase(task.getTargetType())) {
                persistTaskTurn(
                        task,
                        outcome.requestId(),
                        outcome.sessionKey(),
                        originalMessage,
                        outcome.resultPayload()
                );
            }
            deliverIfNeeded(task, outcome.resultPayload());
            scheduledTaskExecutionService.complete(
                    execution.getExecutionId(),
                    "SUCCESS",
                    TextUtils.truncate(outcome.summary(), 300),
                    TextUtils.truncate(outcome.resultPayload(), 5000),
                    "",
                    outcome.requestId(),
                    outcome.sessionKey(),
                    LocalDateTime.now()
            );
            scheduledTaskService.markFinished(task, "SUCCESS", LocalDateTime.now());
            return outcome;
        } catch (Exception ex) {
            String error = ex.getMessage() == null
                    ? ex.getClass().getSimpleName()
                    : ex.getMessage();
            if (execution != null) {
                scheduledTaskExecutionService.complete(
                        execution.getExecutionId(),
                        "FAILED",
                        TextUtils.truncate(error, 300),
                        "",
                        TextUtils.truncate(error, 1000),
                        requestId,
                        sessionKey,
                        LocalDateTime.now()
                );
            }
            scheduledTaskService.markFinished(task, "FAILED", LocalDateTime.now());
            throw ex;
        }
    }

    public int dispatchDueTasks(int limit) {
        LocalDateTime now = LocalDateTime.now();
        List<ScheduledTask> dueTasks = scheduledTaskService.listDueTasks(now, limit);
        int executed = 0;
        for (ScheduledTask task : dueTasks) {
            LocalDateTime nextRunAt = taskScheduleSupport.nextRunAt(task.getScheduleType(), task.getScheduleExpression(), now);
            scheduledTaskService.markRunning(task, now, nextRunAt);
            try {
                runTask(task, "CRON");
            } catch (Exception ex) {
                log.warn("定时任务执行失败，taskId={}, reason={}", task.getTaskId(), ex.getMessage());
            }
            executed++;
        }
        return executed;
    }

    private TaskExecutionOutcome executeSkillTask(ScheduledTask task, String requestId) {
        String skillId = TextUtils.safe(task.getTargetRef());
        Set<String> allowedToolPacks = skillService.resolveAllowedToolPacks(task.getChannel(), task.getOwnerUserId());
        String answer = skillRuntimeService.executeBySkillId(skillId, task.getInputPayload(), allowedToolPacks);
        return new TaskExecutionOutcome(buildSummary(task, answer), answer, requestId, resolveTaskSessionKey(task));
    }

    private TaskExecutionOutcome executeAgentTask(
            ScheduledTask task,
            String runId,
            String prompt
    ) {
        ChatServiceImpl.TaskChatExecutionResult result =
                chatService.executeTaskMessage(
                        new ChatRequest(
                                resolveTaskSessionKey(task),
                                task.getOwnerUserId(),
                                prompt,
                                TextUtils.safe(task.getChannel(), "api"),
                                "agent"
                        ),
                        shouldPersistToSession(task),
                        runId
                );
        return new TaskExecutionOutcome(
                buildSummary(task, result.answer()),
                result.answer(),
                result.requestId(),
                result.sessionKey()
        );
    }

    private void persistTaskTurn(ScheduledTask task, String requestId, String sessionKey, String userMessage, String assistantMessage) {
        AgentSession session = agentSessionService.getOrCreate(sessionKey, task.getChannel(), task.getOwnerUserId());
        agentSessionService.persistConversation(session, userMessage, assistantMessage, soulPromptService.soulVersion());
        memoryService.storeConversationTurn(sessionKey, task.getChannel(), task.getOwnerUserId(), userMessage, assistantMessage);
        messageEventService.recordTurn(sessionKey, task.getChannel(), task.getOwnerUserId(), userMessage, assistantMessage, "TASK", requestId);
        messageEventService.recordSingle(
                sessionKey,
                task.getChannel(),
                task.getOwnerUserId(),
                "SYSTEM",
                "TASK",
                "taskId=%s, trigger=%s, target=%s/%s".formatted(task.getTaskId(), "SYSTEM", task.getTargetType(), task.getTargetRef()),
                requestId
        );
    }

    private void deliverIfNeeded(ScheduledTask task, String answer) {
        if (!feishuDeliveryEnabled) {
            return;
        }
        if (!"FEISHU".equalsIgnoreCase(TextUtils.safe(task.getDeliveryMode()))) {
            return;
        }
        String targetSessionKey = resolveDeliveryTarget(task);
        if (!StringUtils.hasText(targetSessionKey)) {
            return;
        }
        UnifiedInboundMessage inboundMessage = new UnifiedInboundMessage(
                TextUtils.safe(task.getChannel(), "feishu"),
                targetSessionKey,
                task.getOwnerUserId(),
                ""
        );
        channelOutboundDispatcher.dispatch(TextUtils.safe(task.getChannel(), "feishu"), inboundMessage, Map.of(), TextUtils.truncate(answer, 1800));
    }

    private String resolveDeliveryTarget(ScheduledTask task) {
        if (!StringUtils.hasText(task.getDeliveryTarget())) {
            return "feishu".equalsIgnoreCase(task.getChannel()) && shouldPersistToSession(task)
                    ? resolveTaskSessionKey(task)
                    : "";
        }
        if ("CURRENT_SESSION".equalsIgnoreCase(task.getDeliveryTarget())) {
            return resolveTaskSessionKey(task);
        }
        return task.getDeliveryTarget().trim();
    }

    private String resolveAgentPrompt(String rawInputPayload) {
        String payload = TextUtils.safe(rawInputPayload);
        if (!looksLikeJson(payload)) {
            return payload;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(payload, new TypeReference<>() {
            });
            Object message = parsed.get("message");
            if (message == null) {
                message = parsed.get("prompt");
            }
            if (message == null) {
                message = parsed.get("goal");
            }
            if (message != null && StringUtils.hasText(String.valueOf(message))) {
                return String.valueOf(message).trim();
            }
        } catch (Exception ex) {
            throw new BusinessException(40081, "Agent 任务 inputPayload JSON 非法: " + ex.getMessage());
        }
        throw new BusinessException(40082, "Agent 任务需要 message/prompt/goal 字段");
    }

    private boolean shouldPersistToSession(ScheduledTask task) {
        return task.getPersistToSession() != null && task.getPersistToSession() == 1;
    }

    private String renderTaskPrompt(ScheduledTask task) {
        return switch (TextUtils.normalize(task.getTargetType())) {
            case "agent" -> resolveAgentPrompt(task.getInputPayload());
            case "skill" -> TextUtils.safe(task.getInputPayload(), "执行 skill: " + task.getTargetRef());
            default -> TextUtils.safe(task.getInputPayload());
        };
    }

    private String resolveTaskSessionKey(ScheduledTask task) {
        String template = TextUtils.safe(task.getSessionKeyTemplate());
        if (!StringUtils.hasText(template)) {
            return shouldPersistToSession(task)
                    ? "task:" + task.getTaskId()
                    : "task:shadow:" + task.getTaskId();
        }
        return template
                .replace("{taskId}", task.getTaskId())
                .replace("{ownerUserId}", task.getOwnerUserId())
                .replace("{date}", LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE))
                .replace("{channel}", TextUtils.safe(task.getChannel(), "api"));
    }

    private String buildSummary(ScheduledTask task, String answer) {
        return "%s 执行完成：%s".formatted(task.getName(), TextUtils.truncate(answer, 120));
    }

    private boolean looksLikeJson(String text) {
        return StringUtils.hasText(text) && text.trim().startsWith("{") && text.trim().endsWith("}");
    }

}
