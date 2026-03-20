package com.openclaw.strategy.channel.impl;

import com.openclaw.common.exception.BusinessException;
import com.openclaw.strategy.channel.ChannelAdapter;
import com.openclaw.strategy.channel.model.UnifiedInboundMessage;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * WeChat（示例）Webhook 适配器。
 *
 * 说明：
 * 1. 这里按常见字段 fromUser/toUser/content 做演示。
 * 2. 接入真实企业微信或公众号时，仅需替换本类解析逻辑，不影响上层业务。
 */
@Component
public class WechatChannelAdapter implements ChannelAdapter {

    @Override
    public String channel() {
        return "wechat";
    }

    @Override
    public UnifiedInboundMessage adapt(Map<String, Object> payload) {
        String fromUser = String.valueOf(payload.getOrDefault("fromUser", "")).trim();
        String toUser = String.valueOf(payload.getOrDefault("toUser", "")).trim();
        String content = String.valueOf(payload.getOrDefault("content", "")).trim();

        if (fromUser.isEmpty() || toUser.isEmpty() || content.isEmpty()) {
            throw new BusinessException(40020, "WeChat payload 字段不完整");
        }

        String sessionKey = "wechat:" + fromUser;
        return new UnifiedInboundMessage(channel(), sessionKey, fromUser, content);
    }
}
