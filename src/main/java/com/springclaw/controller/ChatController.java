package com.springclaw.controller;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.dto.chat.AsyncChatAcceptedResponse;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.service.chat.ChatService;
import com.springclaw.service.chat.async.AsyncChatRequestMessage;
import com.springclaw.service.chat.async.AsyncChatResultPayload;
import com.springclaw.service.chat.async.AsyncChatResultStore;
import com.springclaw.service.chat.async.ChatMessageProducer;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.springclaw.web.auth.RequestUserContext;
import com.springclaw.web.auth.RequestUserContextHolder;

import java.util.UUID;

/**
 * 对话控制器。
 *
 * 设计说明：
 * 1. 提供 REST + SSE 两种输出模式，覆盖“普通问答 + 流式生成”两个典型场景。
 * 2. Controller 不包含业务实现，仅调用 Service，严格符合 MVC 分层。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ChatMessageProducer chatMessageProducer;
    private final AsyncChatResultStore asyncChatResultStore;

    public ChatController(ChatService chatService,
                          ChatMessageProducer chatMessageProducer,
                          AsyncChatResultStore asyncChatResultStore) {
        this.chatService = chatService;
        this.chatMessageProducer = chatMessageProducer;
        this.asyncChatResultStore = asyncChatResultStore;
    }

    @PostMapping("/send")
    public ApiResponse<ChatResponse> send(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.success(chatService.chat(normalizeRequest(request)));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        return chatService.stream(normalizeRequest(request));
    }

    @PostMapping("/async")
    public ApiResponse<AsyncChatAcceptedResponse> sendAsync(@Valid @RequestBody ChatRequest request) {
        ChatRequest normalizedRequest = normalizeRequest(request);
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String channel = StringUtils.hasText(normalizedRequest.channel()) ? normalizedRequest.channel() : "api";
        AsyncChatRequestMessage message = new AsyncChatRequestMessage(
                requestId,
                normalizedRequest.sessionKey(),
                normalizedRequest.userId(),
                normalizedRequest.message(),
                channel,
                System.currentTimeMillis()
        );
        asyncChatResultStore.markQueued(message);
        chatMessageProducer.sendRequest(message);
        return ApiResponse.success(new AsyncChatAcceptedResponse(
                requestId,
                "QUEUED",
                channel,
                System.currentTimeMillis()
        ));
    }

    @GetMapping("/async/{requestId}")
    public ApiResponse<AsyncChatResultPayload> asyncResult(@PathVariable String requestId) {
        AsyncChatResultPayload payload = asyncChatResultStore.find(requestId);
        if (payload == null) {
            return ApiResponse.fail(40404, "未找到异步结果: " + requestId);
        }
        return ApiResponse.success(payload);
    }

    private ChatRequest normalizeRequest(ChatRequest request) {
        RequestUserContext context = RequestUserContextHolder.get();
        String effectiveUserId = context == null ? request.userId() : context.username();
        if (context != null && StringUtils.hasText(request.userId())
                && !context.username().equalsIgnoreCase(request.userId().trim())) {
            throw new BusinessException(40313, "请求中的 userId 与当前登录账号不一致");
        }
        if (!StringUtils.hasText(effectiveUserId)) {
            throw new BusinessException(40066, "userId 不能为空");
        }
        return new ChatRequest(
                request.sessionKey(),
                effectiveUserId.trim(),
                request.message(),
                request.channel()
        );
    }
}
