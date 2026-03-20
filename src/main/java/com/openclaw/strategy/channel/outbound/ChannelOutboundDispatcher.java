package com.openclaw.strategy.channel.outbound;

import com.openclaw.strategy.channel.model.UnifiedInboundMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 渠道出站分发器。
 */
@Component
public class ChannelOutboundDispatcher {

    private final Map<String, ChannelOutboundAdapter> adapterMap;

    public ChannelOutboundDispatcher(List<ChannelOutboundAdapter> adapters) {
        this.adapterMap = adapters.stream()
                .collect(Collectors.toMap(
                        adapter -> adapter.channel().toLowerCase(),
                        Function.identity(),
                        (a, b) -> a
                ));
    }

    public boolean dispatch(String channel,
                            UnifiedInboundMessage inboundMessage,
                            Map<String, Object> rawPayload,
                            String replyText) {
        if (channel == null) {
            return false;
        }
        ChannelOutboundAdapter adapter = adapterMap.get(channel.trim().toLowerCase());
        if (adapter == null) {
            return false;
        }
        adapter.send(inboundMessage, rawPayload, replyText);
        return true;
    }
}

