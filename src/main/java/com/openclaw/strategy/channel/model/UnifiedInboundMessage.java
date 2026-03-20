package com.openclaw.strategy.channel.model;

/**
 * 统一入站消息模型。
 *
 * 设计说明：
 * 1. 各渠道 payload 差异很大，先归一化为统一对象，再进入业务层。
 * 2. 这一步是“防腐层”思想，避免渠道字段污染核心服务。
 */
public record UnifiedInboundMessage(
        String channel,
        String sessionKey,
        String userId,
        String text
) {
}
