package com.springclaw.service.chat.async;

import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.service.chat.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class ChatMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageConsumer.class);

    private final ChatService chatService;
    private final AsyncChatResultStore asyncChatResultStore;
    private final ChatMessageProducer chatMessageProducer;
    private final SimpMessagingTemplate messagingTemplate;

    public ChatMessageConsumer(ChatService chatService,
                               AsyncChatResultStore asyncChatResultStore,
                               ChatMessageProducer chatMessageProducer,
                               SimpMessagingTemplate messagingTemplate) {
        this.chatService = chatService;
        this.asyncChatResultStore = asyncChatResultStore;
        this.chatMessageProducer = chatMessageProducer;
        this.messagingTemplate = messagingTemplate;
    }

    @RabbitListener(queues = "${springclaw.rabbitmq.chat-request-queue:chat.request.queue}")
    public void consume(AsyncChatRequestMessage message) {
        try {
            ChatResponse response = chatService.chat(new ChatRequest(
                    message.sessionKey(),
                    message.userId(),
                    message.message(),
                    message.channel(),
                    message.responseMode()
            ));
            AsyncChatResultPayload payload = asyncChatResultStore.markCompleted(
                    message,
                    response.answer(),
                    response.model()
            );
            chatMessageProducer.sendResponse(payload);
            messagingTemplate.convertAndSend("/topic/chat/" + payload.requestId(), payload);
        } catch (Exception ex) {
            log.warn("异步聊天处理失败，requestId={}, reason={}", message.requestId(), ex.getMessage());
            AsyncChatResultPayload payload = asyncChatResultStore.markFailed(message, ex.getMessage());
            chatMessageProducer.sendResponse(payload);
            messagingTemplate.convertAndSend("/topic/chat/" + payload.requestId(), payload);
        }
    }
}
