package com.springclaw.controller;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.response.ApiResponse;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.dto.chat.AsyncChatAcceptedResponse;
import com.springclaw.dto.chat.ChatHistoryMessage;
import com.springclaw.dto.chat.ChatHistoryResponse;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.runtime.bridge.LegacyRuntimeBridge;
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.service.agent.AgentActionProposalResult;
import com.springclaw.service.agent.AgentActionProposalService;
import com.springclaw.service.agent.AgentRunTraceEvent;
import com.springclaw.service.agent.AgentRunTraceService;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.chat.AcceptedChatCommand;
import com.springclaw.service.chat.ChatService;
import com.springclaw.service.chat.async.AsyncChatRequestMessage;
import com.springclaw.service.chat.async.AsyncChatResultPayload;
import com.springclaw.service.chat.async.AsyncChatResultStore;
import com.springclaw.service.chat.async.ChatMessageProducer;
import com.springclaw.service.event.MessageEventService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.springclaw.web.auth.RequestUserContext;
import com.springclaw.web.auth.RequestUserContextHolder;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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

    private static final Duration RUN_DEADLINE = Duration.ofMinutes(30);

    private final ChatService chatService;
    private final ChatMessageProducer chatMessageProducer;
    private final AsyncChatResultStore asyncChatResultStore;
    private final MessageEventService messageEventService;
    private final AiProviderService aiProviderService;
    private final AgentActionProposalService actionProposalService;
    private final AgentRunTraceService agentRunTraceService;
    private final RunIdentityFactory runIdentityFactory;
    private final AuthService authService;
    private final LegacyRuntimeBridge runtimeBridge;

    @Autowired
    public ChatController(ChatService chatService,
                          ChatMessageProducer chatMessageProducer,
                          AsyncChatResultStore asyncChatResultStore,
                          MessageEventService messageEventService,
                          AiProviderService aiProviderService,
                          AgentActionProposalService actionProposalService,
                          AgentRunTraceService agentRunTraceService,
                          RunIdentityFactory runIdentityFactory,
                          AuthService authService,
                          LegacyRuntimeBridge runtimeBridge) {
        this.chatService = chatService;
        this.chatMessageProducer = chatMessageProducer;
        this.asyncChatResultStore = asyncChatResultStore;
        this.messageEventService = messageEventService;
        this.aiProviderService = aiProviderService;
        this.actionProposalService = actionProposalService;
        this.agentRunTraceService = agentRunTraceService;
        this.runIdentityFactory = runIdentityFactory;
        this.authService = authService;
        this.runtimeBridge = runtimeBridge;
    }

    ChatController(ChatService chatService,
                   ChatMessageProducer chatMessageProducer,
                   AsyncChatResultStore asyncChatResultStore,
                   MessageEventService messageEventService,
                   AiProviderService aiProviderService,
                   RunIdentityFactory runIdentityFactory,
                   AuthService authService,
                   LegacyRuntimeBridge runtimeBridge) {
        this(
                chatService,
                chatMessageProducer,
                asyncChatResultStore,
                messageEventService,
                aiProviderService,
                null,
                null,
                runIdentityFactory,
                authService,
                runtimeBridge
        );
    }

    @PostMapping("/send")
    public ApiResponse<ChatResponse> send(@Valid @RequestBody ChatRequest request) {
        ChatRequest normalizedRequest = normalizeRequest(request);
        String acceptedRunId = runIdentityFactory.create();
        Instant acceptedAt = Instant.now();
        acceptRun(acceptedRunId, normalizedRequest, acceptedAt);
        return ApiResponse.success(chatService.chat(
                new AcceptedChatCommand(acceptedRunId, normalizedRequest)
        ));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        ChatRequest normalizedRequest = normalizeRequest(request);
        String acceptedRunId = runIdentityFactory.create();
        Instant acceptedAt = Instant.now();
        acceptRun(acceptedRunId, normalizedRequest, acceptedAt);
        return chatService.stream(
                new AcceptedChatCommand(acceptedRunId, normalizedRequest)
        );
    }

    @PostMapping("/async")
    public ApiResponse<AsyncChatAcceptedResponse> sendAsync(
            @Valid @RequestBody ChatRequest request
    ) {
        ChatRequest normalizedRequest = normalizeRequest(request);
        String requestId = runIdentityFactory.create();
        String channel = normalizedChannel(normalizedRequest.channel());
        AsyncChatRequestMessage message = new AsyncChatRequestMessage(
                requestId,
                normalizedRequest.sessionKey(),
                normalizedRequest.userId(),
                normalizedRequest.message(),
                channel,
                System.currentTimeMillis(),
                normalizedRequest.responseMode()
        );
        Instant acceptedAt = Instant.ofEpochMilli(message.createdAt());
        acceptRun(requestId, normalizedRequest, acceptedAt);
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

    @GetMapping("/history")
    public ApiResponse<ChatHistoryResponse> history(@RequestParam String sessionKey,
                                                    @RequestParam(defaultValue = "80") @Min(1) @Max(100) int limit) {
        if (!StringUtils.hasText(sessionKey)) {
            throw new BusinessException(40065, "sessionKey 不能为空");
        }
        RequestUserContext context = RequestUserContextHolder.get();
        if (context == null || !StringUtils.hasText(context.username())) {
            throw new BusinessException(40101, "请先登录");
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String normalizedSessionKey = sessionKey.trim();
        String username = context.username().trim();
        long sessionChatEvents = messageEventService.countSessionEvents(normalizedSessionKey, null, null, "CHAT");
        long ownedChatEvents = messageEventService.countSessionEvents(normalizedSessionKey, username, null, "CHAT");
        if (sessionChatEvents > 0 && ownedChatEvents == 0) {
            throw new BusinessException(40315, "无权查看该会话历史");
        }
        var messages = messageEventService.listSessionEvents(normalizedSessionKey, username, null, "CHAT", safeLimit, true)
                .stream()
                .filter(this::isRenderableChatMessage)
                .map(this::toHistoryMessage)
                .toList();
        return ApiResponse.success(new ChatHistoryResponse(normalizedSessionKey, messages));
    }

    @GetMapping("/model-status")
    public ApiResponse<Map<String, Object>> modelStatus() {
        AiProviderService.ActiveChatClient active = aiProviderService.activeClient();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("activeProvider", active.providerId());
        payload.put("activeModel", active.model());
        payload.put("activeDisplay", active.displayName());
        payload.put("available", active.available());
        payload.put("status", active.available() ? "online" : "degraded");
        payload.put("unavailableReason", active.unavailableReason());
        payload.put("checkedAt", System.currentTimeMillis());
        payload.put("recommendation", active.available()
                ? "模型通道可用，可以走实时 Agent 主链路。"
                : "请检查 active-provider、api-key、base-url 和 model 后重启后端。Spring Boot 不会热更新启动时读取的模型配置。");
        return ApiResponse.success(payload);
    }

    @PostMapping("/action-proposals/{proposalId}/confirm")
    public ApiResponse<AgentActionProposalResult> confirmActionProposal(@PathVariable String proposalId,
                                                                        @RequestBody(required = false) ActionProposalConfirmRequest request) {
        RequestUserContext context = requireUserContext();
        String currentSessionKey = request == null ? "" : request.currentSessionKey();
        return ApiResponse.success(actionProposalService.confirm(proposalId, context.username(), context.roleCode(), currentSessionKey));
    }

    @PostMapping("/action-proposals/{proposalId}/cancel")
    public ApiResponse<AgentActionProposalResult> cancelActionProposal(@PathVariable String proposalId) {
        RequestUserContext context = requireUserContext();
        return ApiResponse.success(actionProposalService.cancel(proposalId, context.username(), context.roleCode()));
    }

    @GetMapping("/runs/{requestId}/trace")
    public ApiResponse<java.util.List<AgentRunTraceEvent>> runTrace(@PathVariable String requestId,
                                                                    @RequestParam(defaultValue = "200") int limit) {
        RequestUserContext context = requireUserContext();
        return ApiResponse.success(agentRunTraceService.listTrace(requestId, context.username(), limit));
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
                request.channel(),
                request.responseMode()
        );
    }

    private void acceptRun(
            String runId,
            ChatRequest request,
            Instant acceptedAt
    ) {
        runtimeBridge.accepted(new RunAcceptance(
                runId,
                request.sessionKey(),
                normalizedChannel(request.channel()),
                request.userId(),
                resolveRoleCode(request.userId()),
                request.message(),
                normalizedResponseMode(request.responseMode()),
                acceptedAt,
                acceptedAt.plus(RUN_DEADLINE)
        ));
    }

    private String resolveRoleCode(String userId) {
        RequestUserContext context = RequestUserContextHolder.get();
        String roleCode = context != null && StringUtils.hasText(context.roleCode())
                ? context.roleCode()
                : authService.resolveRoleByUserId(userId);
        if (!StringUtils.hasText(roleCode)) {
            throw new IllegalStateException("roleCode unavailable for accepted run");
        }
        return roleCode.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizedChannel(String channel) {
        return StringUtils.hasText(channel) ? channel.trim() : "api";
    }

    private String normalizedResponseMode(String responseMode) {
        return StringUtils.hasText(responseMode) ? responseMode.trim() : "agent";
    }

    private RequestUserContext requireUserContext() {
        RequestUserContext context = RequestUserContextHolder.get();
        if (context == null || !StringUtils.hasText(context.username())) {
            throw new BusinessException(40101, "请先登录");
        }
        return context;
    }

    private boolean isRenderableChatMessage(MessageEvent event) {
        if (event == null || !StringUtils.hasText(event.getContent())) {
            return false;
        }
        return "USER".equalsIgnoreCase(event.getRole()) || "ASSISTANT".equalsIgnoreCase(event.getRole());
    }

    private ChatHistoryMessage toHistoryMessage(MessageEvent event) {
        String role = "USER".equalsIgnoreCase(event.getRole()) ? "user" : "agent";
        long createdAt = event.getCreateTime() == null
                ? 0L
                : event.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        String id = event.getId() == null
                ? "%s:%s:%d".formatted(event.getSessionKey(), role, createdAt)
                : String.valueOf(event.getId());
        return new ChatHistoryMessage(
                id,
                role,
                normalizeHistoryContent(role, event.getContent()),
                "",
                createdAt
        );
    }

    private String normalizeHistoryContent(String role, String content) {
        String text = content == null ? "" : content.trim();
        if ("agent".equals(role) && text.startsWith("[REFLECT]")) {
            return text.substring("[REFLECT]".length()).trim();
        }
        return text;
    }

    public record ActionProposalConfirmRequest(String currentSessionKey) {
    }
}
