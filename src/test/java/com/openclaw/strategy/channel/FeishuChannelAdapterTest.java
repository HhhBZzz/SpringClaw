package com.openclaw.strategy.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.common.exception.BusinessException;
import com.openclaw.strategy.channel.impl.FeishuChannelAdapter;
import com.openclaw.strategy.channel.model.UnifiedInboundMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Map;

class FeishuChannelAdapterTest {

    private final FeishuChannelAdapter adapter = new FeishuChannelAdapter(new ObjectMapper());

    @Test
    void shouldAdaptFeishuEventPayload() {
        Map<String, Object> payload = Map.of(
                "event", Map.of(
                        "sender", Map.of(
                                "sender_id", Map.of("open_id", "ou_test_001")
                        ),
                        "message", Map.of(
                                "chat_id", "oc_test_chat_01",
                                "chat_type", "p2p",
                                "message_type", "text",
                                "content", "{\"text\":\"你好，飞书\"}"
                        )
                )
        );

        UnifiedInboundMessage message = adapter.adapt(payload);

        Assertions.assertEquals("feishu", message.channel());
        Assertions.assertEquals("feishu:p2p:oc_test_chat_01", message.sessionKey());
        Assertions.assertEquals("ou_test_001", message.userId());
        Assertions.assertEquals("你好，飞书", message.text());
    }

    @Test
    void shouldAdaptSimplePayload() {
        Map<String, Object> payload = Map.of(
                "open_id", "ou_simple_001",
                "chat_id", "oc_simple_chat",
                "text", "hello from simple payload"
        );

        UnifiedInboundMessage message = adapter.adapt(payload);

        Assertions.assertEquals("feishu", message.channel());
        Assertions.assertEquals("feishu:oc_simple_chat", message.sessionKey());
        Assertions.assertEquals("ou_simple_001", message.userId());
        Assertions.assertEquals("hello from simple payload", message.text());
    }

    @Test
    void shouldAdaptGroupPayloadIntoGroupScopedSession() {
        Map<String, Object> payload = Map.of(
                "event", Map.of(
                        "sender", Map.of(
                                "sender_id", Map.of("open_id", "ou_group_001")
                        ),
                        "message", Map.of(
                                "chat_id", "oc_group_chat",
                                "chat_type", "group",
                                "message_type", "text",
                                "content", "{\"text\":\"今天总结一下\"}"
                        )
                )
        );

        UnifiedInboundMessage message = adapter.adapt(payload);

        Assertions.assertEquals("feishu:group:oc_group_chat", message.sessionKey());
        Assertions.assertEquals("ou_group_001", message.userId());
        Assertions.assertEquals("今天总结一下", message.text());
    }

    @Test
    void shouldThrowWhenPayloadIncomplete() {
        Map<String, Object> badPayload = Map.of(
                "event", Map.of(
                        "message", Map.of("chat_id", "oc_missing_sender")
                )
        );

        BusinessException ex = Assertions.assertThrows(BusinessException.class, () -> adapter.adapt(badPayload));
        Assertions.assertEquals(40031, ex.getCode());
    }
}
