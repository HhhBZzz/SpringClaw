package com.springclaw.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 消息事件实体。
 *
 * 设计说明：
 * 1. 记录所有消息交互事件，用于审计和分析。
 * 2. 支持多渠道消息统一建模。
 * 3. 存储原始消息和处理后的结构化数据。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("message_event")
public class MessageEvent extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 会话唯一键。
     */
    private String sessionKey;

    /**
     * 消息来源渠道。
     */
    private String channel;

    /**
     * 用户标识。
     */
    private String userId;

    /**
     * 消息角色：USER/ASSISTANT/SYSTEM。
     */
    private String role;

    /**
     * 事件类型：MESSAGE/TURN/SYSTEM 等。
     */
    private String eventType;

    /**
     * 关联的请求 ID。
     */
    private String requestId;

    /**
     * 确定性事件键（Phase 3A1 memory core）。旧数据回填为 legacy:&lt;id&gt;。
     */
    private String eventKey;

    /**
     * 消息内容。
     */
    private String content;

    /**
     * 原始消息 JSON（用于审计）。
     */
    private String rawPayload;

    /**
     * 处理状态：pending/processed/failed。
     */
    private String processingStatus;

    /**
     * 处理错误信息（如果有）。
     */
    private String errorMessage;
}
