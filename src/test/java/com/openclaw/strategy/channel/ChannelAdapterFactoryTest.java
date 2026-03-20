package com.openclaw.strategy.channel;

import com.openclaw.common.exception.BusinessException;
import com.openclaw.strategy.channel.factory.ChannelAdapterFactory;
import com.openclaw.strategy.channel.impl.FeishuChannelAdapter;
import com.openclaw.strategy.channel.impl.TelegramChannelAdapter;
import com.openclaw.strategy.channel.impl.WechatChannelAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

class ChannelAdapterFactoryTest {

    @Test
    void shouldRouteToRegisteredAdapter() {
        ChannelAdapterFactory factory = new ChannelAdapterFactory(List.of(
                new TelegramChannelAdapter(),
                new WechatChannelAdapter(),
                new FeishuChannelAdapter(new ObjectMapper())
        ));

        ChannelAdapter adapter = factory.getRequired("TeLeGrAm");
        Assertions.assertEquals("telegram", adapter.channel());
    }

    @Test
    void shouldRouteToFeishuAdapter() {
        ChannelAdapterFactory factory = new ChannelAdapterFactory(List.of(
                new FeishuChannelAdapter(new ObjectMapper()),
                new TelegramChannelAdapter()
        ));

        ChannelAdapter adapter = factory.getRequired("feishu");
        Assertions.assertEquals("feishu", adapter.channel());
    }

    @Test
    void shouldRejectBlankChannel() {
        ChannelAdapterFactory factory = new ChannelAdapterFactory(List.of(new TelegramChannelAdapter()));

        BusinessException ex = Assertions.assertThrows(BusinessException.class, () -> factory.getRequired(" "));
        Assertions.assertEquals(40031, ex.getCode());
    }
}
