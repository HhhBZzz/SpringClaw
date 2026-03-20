package com.openclaw.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 会话实体。
 *
 * 设计说明：
 * 1. Stage 1 先落最小会话闭环：sessionKey + 渠道 + 最近一轮问答。
 * 2. 后续可演进为完整消息事件流表（与 OpenClaw transcript 对齐）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_session")
public class AgentSession extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 会话唯一键。
     * 推荐格式：channel:userId 或 channel:chatId，便于多渠道统一路由。
     */
    private String sessionKey;

    /**
     * 渠道标识，例如 telegram/wechat。
     */
    private String channel;

    /**
     * 用户标识。
     */
    private String userId;

    /**
     * 会话状态（ACTIVE/CLOSED）。
     */
    private String status;

    /**
     * 最近一条用户消息（用于快速排障和审计）。
     */
    private String lastUserMessage;

    /**
     * 最近一条模型回复。
     */
    private String lastAssistantMessage;

    /**
     * 本次会话使用的 SOUL 版本（用于提示词追踪）。
     */
    private String soulVersion;
}
