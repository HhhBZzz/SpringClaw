package com.springclaw.service.chat.async;

import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.RunStateRepository;
import com.springclaw.service.chat.AcceptedChatCommand;
import com.springclaw.service.chat.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Objects;

@Service
public class ChatMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageConsumer.class);

    private final ChatService chatService;
    private final AsyncChatResultStore asyncChatResultStore;
    private final ChatMessageProducer chatMessageProducer;
    private final SimpMessagingTemplate messagingTemplate;
    private final RunStateRepository runStateRepository;

    public ChatMessageConsumer(ChatService chatService,
                               AsyncChatResultStore asyncChatResultStore,
                               ChatMessageProducer chatMessageProducer,
                               SimpMessagingTemplate messagingTemplate,
                               RunStateRepository runStateRepository) {
        this.chatService = chatService;
        this.asyncChatResultStore = asyncChatResultStore;
        this.chatMessageProducer = chatMessageProducer;
        this.messagingTemplate = messagingTemplate;
        this.runStateRepository = runStateRepository;
    }

    @RabbitListener(queues = "${springclaw.rabbitmq.chat-request-queue:chat.request.queue}")
    public void consume(AsyncChatRequestMessage message) {
        RunState canonicalRun;
        try {
            canonicalRun = runStateRepository.requireByRunId(message.requestId());
            requireMatchingAcceptance(canonicalRun, message);
        } catch (RuntimeException ex) {
            // canonical 校验失败（run 不存在/字段不符）= 永久性毒消息，跳过 retry 直达 DLQ，避免无限重排卡死整条队列
            log.warn("异步消息 canonical 校验失败，拒绝重排进 DLQ: requestId={}, reason={}",
                    message.requestId(), ex.getMessage());
            throw new AmqpRejectAndDontRequeueException("canonical validation failed: " + message.requestId(), ex);
        }
        try {
            ChatResponse response = chatService.chat(new AcceptedChatCommand(
                    message.requestId(),
                    new ChatRequest(
                            message.sessionKey(),
                            message.userId(),
                            message.message(),
                            message.channel(),
                            message.responseMode(),
                            message.paradigm()
                    )
            ));
            AsyncChatResultPayload payload = asyncChatResultStore.markCompleted(
                    message,
                    response.answer(),
                    response.model()
            );
            chatMessageProducer.sendResponse(payload);
            messagingTemplate.convertAndSend("/topic/chat/" + payload.requestId(), payload);
        } catch (Exception ex) {
            // 处理失败（模型超时等）：记录失败结果给用户后正常 ack，不重排（避免重复处理/重复扣费）；
            // 错误文案固定化，避免底层异常细节（host/超时）透传给用户
            log.warn("异步聊天处理失败: requestId={}, reason={}", message.requestId(), ex.getMessage());
            AsyncChatResultPayload payload = asyncChatResultStore.markFailed(message, "处理失败，请联系管理员");
            try {
                chatMessageProducer.sendResponse(payload);
                messagingTemplate.convertAndSend("/topic/chat/" + payload.requestId(), payload);
            } catch (Exception sendEx) {
                log.warn("异步失败结果下发异常: requestId={}, reason={}", message.requestId(), sendEx.getMessage());
            }
        }
    }

    private void requireMatchingAcceptance(
            RunState run,
            AsyncChatRequestMessage message
    ) {
        SessionAccessClaim claim = run.sessionAccessClaim();
        boolean matches = Objects.equals(run.runId(), message.requestId())
                && Objects.equals(run.requestId(), message.requestId())
                && Objects.equals(run.sessionKey(), message.sessionKey())
                && Objects.equals(run.channel(), normalizedChannel(message.channel()))
                && Objects.equals(run.userId(), message.userId())
                && Objects.equals(run.originalMessage(), message.message())
                && Objects.equals(
                        run.responseMode(),
                        normalizedResponseMode(message.responseMode())
                )
                && Objects.equals(
                        run.acceptedAt(),
                        Instant.ofEpochMilli(message.createdAt())
                )
                && claim.claimType() == SessionAccessClaim.ClaimType.PERSONAL
                && Objects.equals(claim.channel(), normalizedChannel(message.channel()))
                && Objects.equals(claim.sessionKey(), message.sessionKey())
                && Objects.equals(claim.acceptedUserId(), message.userId());
        if (!matches) {
            if (claim.claimType() != SessionAccessClaim.ClaimType.PERSONAL) {
                throw new IllegalStateException(
                        "async canonical run requires PERSONAL session access claim: "
                                + message.requestId()
                );
            }
            throw new IllegalStateException(
                    "async message does not match canonical run: " + message.requestId()
            );
        }
    }

    private String normalizedChannel(String channel) {
        return StringUtils.hasText(channel) ? channel.trim() : "api";
    }

    private String normalizedResponseMode(String responseMode) {
        return StringUtils.hasText(responseMode) ? responseMode.trim() : "agent";
    }
}
