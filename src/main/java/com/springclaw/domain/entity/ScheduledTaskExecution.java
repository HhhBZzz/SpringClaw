package com.springclaw.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 定时任务执行记录。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("scheduled_task_execution")
public class ScheduledTaskExecution extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String executionId;

    private String taskId;

    private String triggerSource;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private String status;

    private String summary;

    private String resultPayload;

    private String errorMessage;

    private String requestId;

    private String sessionKey;
}
