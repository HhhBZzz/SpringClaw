package com.springclaw.controller.task;

import com.springclaw.common.response.ApiResponse;
import com.springclaw.domain.entity.ScheduledTask;
import com.springclaw.domain.entity.ScheduledTaskExecution;
import com.springclaw.service.task.ScheduledTaskExecutionService;
import com.springclaw.service.task.ScheduledTaskService;
import com.springclaw.service.task.TaskCreationDraft;
import com.springclaw.service.task.TaskDraftService;
import com.springclaw.service.task.TaskExecutionOutcome;
import com.springclaw.service.task.TaskManagementService;
import com.springclaw.service.task.TaskUpsertCommand;
import com.springclaw.service.task.executor.TaskExecutionService;
import com.springclaw.web.auth.RequestUserContext;
import com.springclaw.web.auth.RequestUserContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final ScheduledTaskService scheduledTaskService;
    private final ScheduledTaskExecutionService scheduledTaskExecutionService;
    private final TaskExecutionService taskExecutionService;
    private final TaskDraftService taskDraftService;
    private final TaskManagementService taskManagementService;

    public TaskController(ScheduledTaskService scheduledTaskService,
                          ScheduledTaskExecutionService scheduledTaskExecutionService,
                          TaskExecutionService taskExecutionService,
                          TaskDraftService taskDraftService,
                          TaskManagementService taskManagementService) {
        this.scheduledTaskService = scheduledTaskService;
        this.scheduledTaskExecutionService = scheduledTaskExecutionService;
        this.taskExecutionService = taskExecutionService;
        this.taskDraftService = taskDraftService;
        this.taskManagementService = taskManagementService;
    }

    @PostMapping
    public ApiResponse<ScheduledTask> create(@RequestBody UpsertTaskRequest request) {
        RequestUserContext context = requireContext();
        ScheduledTask task = scheduledTaskService.createTask(context.username(), toCommand(request));
        return ApiResponse.success(task);
    }

    @PutMapping("/{taskId}")
    public ApiResponse<ScheduledTask> update(@PathVariable String taskId,
                                             @RequestBody UpsertTaskRequest request) {
        RequestUserContext context = requireContext();
        ScheduledTask task = scheduledTaskService.updateTask(context.username(), context.roleCode(), taskId, toCommand(request));
        return ApiResponse.success(task);
    }

    @GetMapping
    public ApiResponse<List<ScheduledTask>> list(@RequestParam(required = false) String ownerUserId,
                                                 @RequestParam(required = false) Boolean enabled,
                                                 @RequestParam(defaultValue = "50") int limit) {
        RequestUserContext context = requireContext();
        return ApiResponse.success(scheduledTaskService.listTasks(context.username(), context.roleCode(), ownerUserId, enabled, limit));
    }

    @PostMapping("/{taskId}/enable")
    public ApiResponse<ScheduledTask> enable(@PathVariable String taskId) {
        RequestUserContext context = requireContext();
        return ApiResponse.success(scheduledTaskService.setEnabled(context.username(), context.roleCode(), taskId, true));
    }

    @PostMapping("/{taskId}/disable")
    public ApiResponse<ScheduledTask> disable(@PathVariable String taskId) {
        RequestUserContext context = requireContext();
        return ApiResponse.success(scheduledTaskService.setEnabled(context.username(), context.roleCode(), taskId, false));
    }

    @DeleteMapping("/{taskId}")
    public ApiResponse<Void> delete(@PathVariable String taskId) {
        RequestUserContext context = requireContext();
        taskManagementService.deleteTask(context.username(), context.roleCode(), taskId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{taskId}/run")
    public ApiResponse<TaskExecutionOutcome> run(@PathVariable String taskId) {
        RequestUserContext context = requireContext();
        ScheduledTask task = scheduledTaskService.getTaskForAccess(context.username(), context.roleCode(), taskId);
        return ApiResponse.success(taskExecutionService.runTask(task, "MANUAL"));
    }

    @GetMapping("/{taskId}/executions")
    public ApiResponse<List<ScheduledTaskExecution>> executions(@PathVariable String taskId,
                                                                @RequestParam(defaultValue = "20") int limit) {
        RequestUserContext context = requireContext();
        return ApiResponse.success(scheduledTaskExecutionService.listByTask(context.username(), context.roleCode(), taskId, limit));
    }

    @PostMapping("/drafts/parse")
    public ApiResponse<TaskCreationDraft> parseDraft(@RequestBody DraftParseRequest request) {
        RequestUserContext context = requireContext();
        TaskCreationDraft draft = taskDraftService.parseDraft(
                context.username(),
                request == null ? null : request.channel(),
                request == null ? null : request.message()
        );
        if (draft != null && "CURRENT_SESSION".equalsIgnoreCase(draft.deliveryTarget()) && request != null && request.currentSessionKey() != null) {
            draft = new TaskCreationDraft(
                    draft.name(),
                    draft.scheduleType(),
                    draft.scheduleExpression(),
                    draft.scheduleLabel(),
                    draft.targetType(),
                    draft.targetRef(),
                    draft.inputPayload(),
                    draft.channel(),
                    draft.deliveryMode(),
                    request.currentSessionKey(),
                    draft.persistToSession(),
                    draft.sessionKeyTemplate(),
                    draft.summary()
            );
        }
        return ApiResponse.success(draft);
    }

    @PostMapping("/drafts/confirm")
    public ApiResponse<ScheduledTask> confirmDraft(@RequestBody ConfirmDraftRequest request) {
        RequestUserContext context = requireContext();
        TaskCreationDraft draft = request.draft();
        String deliveryTarget = draft.deliveryTarget();
        if ("CURRENT_SESSION".equalsIgnoreCase(deliveryTarget)) {
            deliveryTarget = request.currentSessionKey();
        }
        TaskUpsertCommand command = new TaskUpsertCommand(
                draft.name(),
                true,
                draft.scheduleType(),
                draft.scheduleExpression(),
                draft.targetType(),
                draft.targetRef(),
                draft.inputPayload(),
                draft.channel(),
                draft.deliveryMode(),
                deliveryTarget,
                draft.persistToSession(),
                draft.sessionKeyTemplate()
        );
        return ApiResponse.success(scheduledTaskService.createTask(context.username(), command));
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(@RequestParam(required = false) String ownerUserId,
                                                    @RequestParam(defaultValue = "20") int limit) {
        RequestUserContext context = requireContext();
        List<ScheduledTask> tasks = scheduledTaskService.listTasks(context.username(), context.roleCode(), ownerUserId, null, limit);
        long enabledCount = tasks.stream().filter(task -> task.getEnabled() == null || task.getEnabled() == 1).count();
        return ApiResponse.success(Map.of(
                "total", tasks.size(),
                "enabled", enabledCount,
                "disabled", tasks.size() - enabledCount,
                "tasks", tasks
        ));
    }

    private RequestUserContext requireContext() {
        RequestUserContext context = RequestUserContextHolder.get();
        if (context == null) {
            throw new com.springclaw.common.exception.BusinessException(40112, "当前接口需要登录后访问");
        }
        return context;
    }

    private TaskUpsertCommand toCommand(UpsertTaskRequest request) {
        if (request == null) {
            throw new com.springclaw.common.exception.BusinessException(40060, "任务配置不能为空");
        }
        return new TaskUpsertCommand(
                request.name(),
                request.enabled(),
                request.scheduleType(),
                request.scheduleExpression(),
                request.targetType(),
                request.targetRef(),
                request.inputPayload(),
                request.channel(),
                request.deliveryMode(),
                request.deliveryTarget(),
                request.persistToSession(),
                request.sessionKeyTemplate()
        );
    }

    public record UpsertTaskRequest(String name,
                                    Boolean enabled,
                                    String scheduleType,
                                    String scheduleExpression,
                                    String targetType,
                                    String targetRef,
                                    String inputPayload,
                                    String channel,
                                    String deliveryMode,
                                    String deliveryTarget,
                                    Boolean persistToSession,
                                    String sessionKeyTemplate) {
    }

    public record DraftParseRequest(String message,
                                    String channel,
                                    String currentSessionKey) {
    }

    public record ConfirmDraftRequest(TaskCreationDraft draft,
                                      String currentSessionKey) {
    }
}
