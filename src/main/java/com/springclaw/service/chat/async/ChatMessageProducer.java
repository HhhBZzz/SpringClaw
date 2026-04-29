package com.springclaw.service.chat.async;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatMessageProducer {

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;
    private final String requestRoutingKey;
    private final String responseRoutingKey;

    public ChatMessageProducer(RabbitTemplate rabbitTemplate,
                               @Value("${springclaw.rabbitmq.chat-exchange:chat.exchange}") String exchange,
                               @Value("${springclaw.rabbitmq.request-routing-key:chat.request}") String requestRoutingKey,
                               @Value("${springclaw.rabbitmq.response-routing-key:chat.response}") String responseRoutingKey) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.requestRoutingKey = requestRoutingKey;
        this.responseRoutingKey = responseRoutingKey;
    }

    public void sendRequest(AsyncChatRequestMessage message) {
        rabbitTemplate.convertAndSend(exchange, requestRoutingKey, message);
    }

    public void sendResponse(AsyncChatResultPayload payload) {
        rabbitTemplate.convertAndSend(exchange, responseRoutingKey, payload);
    }
}
