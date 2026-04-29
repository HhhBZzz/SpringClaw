package com.springclaw.strategy.channel;

import com.springclaw.strategy.channel.model.UnifiedInboundMessage;

import java.util.Map;

/**
 * 渠道适配策略接口（Strategy Pattern）。
 *
 * 设计说明：
 * 1. 不同渠道的 Webhook 数据结构不同，通过策略模式实现“一个渠道一个实现类”。
 * 2. 这样新增渠道时只需新增实现并注册 Bean，不需要改 Controller 的 if-else，符合开闭原则。
 */
public interface ChannelAdapter {

    String channel();

    UnifiedInboundMessage adapt(Map<String, Object> payload);
}
