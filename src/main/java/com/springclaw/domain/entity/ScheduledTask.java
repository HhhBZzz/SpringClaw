package com.springclaw.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 定时任务定义。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("scheduled_task")
public class ScheduledTask extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String taskId;

    private String ownerUserId;

    private String name;

    private Integer enabled;

    private String scheduleType;

    private String scheduleExpression;

    private String targetType;

    private String targetRef;

    private String inputPayload;

    private String channel;

    private String deliveryMode;

    private String deliveryTarget;

    private Integer persistToSession;

    private String sessionKeyTemplate;

    private LocalDateTime lastRunAt;

    private LocalDateTime nextRunAt;

    private String lastStatus;
}
