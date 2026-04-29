package com.springclaw.strategy.channel.factory;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.strategy.channel.ChannelAdapter;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 渠道适配器工厂（Factory Pattern）。
 *
 * 设计说明：
 * 1. 工厂负责“按渠道名选择策略实现”，Controller 无需关心实现细节。
 * 2. 策略 + 工厂组合是大厂常见的多渠道扩展写法，面试可重点讲“低耦合扩展能力”。
 */
@Component
public class ChannelAdapterFactory {

    private final Map<String, ChannelAdapter> adapterMap;

    public ChannelAdapterFactory(List<ChannelAdapter> adapters) {
        this.adapterMap = adapters.stream()
                .collect(Collectors.toMap(
                        adapter -> adapter.channel().toLowerCase(),
                        Function.identity(),
                        (a, b) -> a
                ));
    }

    public ChannelAdapter getRequired(String channel) {
        if (channel == null || channel.isBlank()) {
            throw new BusinessException(40031, "channel 不能为空");
        }
        ChannelAdapter adapter = adapterMap.get(channel.toLowerCase());
        if (adapter == null) {
            throw new BusinessException(40030, "不支持的渠道: " + channel);
        }
        return adapter;
    }
}
