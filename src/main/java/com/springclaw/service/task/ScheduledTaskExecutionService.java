package com.springclaw.service.task;

import com.baomidou.mybatisplus.extension.service.IService;
import com.springclaw.domain.entity.ScheduledTaskExecution;

import java.time.LocalDateTime;
import java.util.List;

public interface ScheduledTaskExecutionService extends IService<ScheduledTaskExecution> {

    ScheduledTaskExecution start(String taskId, String triggerSource, String requestId);

    void complete(String executionId,
                  String status,
                  String summary,
                  String resultPayload,
                  String errorMessage,
                  String requestId,
                  String sessionKey,
                  LocalDateTime finishedAt);

    int deleteByTaskId(String taskId);

    List<ScheduledTaskExecution> listByTask(String requesterUserId, String requesterRole, String taskId, int limit);
}
