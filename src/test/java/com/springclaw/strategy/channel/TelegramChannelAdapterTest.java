package com.springclaw.strategy.channel;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.strategy.channel.impl.TelegramChannelAdapter;
import com.springclaw.strategy.channel.model.UnifiedInboundMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

class TelegramChannelAdapterTest {

    private final TelegramChannelAdapter adapter = new TelegramChannelAdapter();

    @Test
    void shouldAdaptTelegramPayload() {
        Map<String, Object> payload = Map.of(
                "message", Map.of(
                        "chat", Map.of("id", 1001),
                        "from", Map.of("id", 2002),
                        "text", "hello"
                )
        );

        UnifiedInboundMessage message = adapter.adapt(payload);

        Assertions.assertEquals("telegram", message.channel());
        Assertions.assertEquals("telegram:1001", message.sessionKey());
        Assertions.assertEquals("2002", message.userId());
        Assertions.assertEquals("hello", message.text());
    }

    @Test
    void shouldThrowWhenPayloadIncomplete() {
        Map<String, Object> badPayload = Map.of(
                "message", Map.of("chat", Map.of("id", 1))
        );

        BusinessException ex = Assertions.assertThrows(BusinessException.class, () -> adapter.adapt(badPayload));
        Assertions.assertEquals(40011, ex.getCode());
    }
}
