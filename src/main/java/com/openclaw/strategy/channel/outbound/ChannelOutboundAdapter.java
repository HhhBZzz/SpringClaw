package com.openclaw.strategy.channel.outbound;

import com.openclaw.strategy.channel.model.UnifiedInboundMessage;

import java.util.Map;

/**
 * 渠道出站适配器。
 *
 * 设计说明：
 * 1. 入站和出站都采用适配器，保持渠道协议隔离。
 * 2. Core 层只关心“要回复什么”，不关心“各渠道怎么发”。
 */
public interface ChannelOutboundAdapter {

    String channel();

    void send(UnifiedInboundMessage inboundMessage, Map<String, Object> rawPayload, String replyText);
}

