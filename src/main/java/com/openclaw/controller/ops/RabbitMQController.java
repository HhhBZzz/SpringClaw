package com.openclaw.controller.ops;

import com.openclaw.common.response.ApiResponse;
import com.openclaw.web.auth.RequireRole;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/rabbitmq")
@ConditionalOnBean(RabbitAdmin.class)
@RequireRole({"ADMIN"})
public class RabbitMQController {

    private final RabbitAdmin rabbitAdmin;
    private final String requestQueue;
    private final String responseQueue;
    private final String deadLetterQueue;

    public RabbitMQController(RabbitAdmin rabbitAdmin,
                              @Value("${openclaw.rabbitmq.chat-request-queue:chat.request.queue}") String requestQueue,
                              @Value("${openclaw.rabbitmq.chat-response-queue:chat.response.queue}") String responseQueue,
                              @Value("${openclaw.rabbitmq.chat-dead-letter-queue:chat.dead-letter.queue}") String deadLetterQueue) {
        this.rabbitAdmin = rabbitAdmin;
        this.requestQueue = requestQueue;
        this.responseQueue = responseQueue;
        this.deadLetterQueue = deadLetterQueue;
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestQueue", describeQueue(requestQueue));
        payload.put("responseQueue", describeQueue(responseQueue));
        payload.put("deadLetterQueue", describeQueue(deadLetterQueue));
        return ApiResponse.success(payload);
    }

    private Map<String, Object> describeQueue(String queueName) {
        QueueInformation info = rabbitAdmin.getQueueInfo(queueName);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", queueName);
        payload.put("exists", info != null);
        if (info != null) {
            payload.put("messageCount", info.getMessageCount());
            payload.put("consumerCount", info.getConsumerCount());
        }
        return payload;
    }
}
