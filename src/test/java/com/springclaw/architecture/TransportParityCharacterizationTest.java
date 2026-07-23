package com.springclaw.architecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.config.websocket.WebSocketConfig;
import com.springclaw.controller.ChatController;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.dto.chat.ChatResponse;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.lifecycle.RunStateRepository;
import com.springclaw.service.agent.AgentRunTraceService;
import com.springclaw.service.chat.AcceptedChatCommand;
import com.springclaw.service.chat.ChatService;
import com.springclaw.service.chat.async.AsyncChatRequestMessage;
import com.springclaw.service.chat.async.AsyncChatResultPayload;
import com.springclaw.service.chat.async.AsyncChatResultStore;
import com.springclaw.service.chat.async.ChatMessageConsumer;
import com.springclaw.service.chat.async.ChatMessageProducer;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;
import com.springclaw.service.chat.impl.ChatPersistenceIntent;
import com.springclaw.service.chat.impl.ChatResultPersister;
import com.springclaw.service.chat.impl.SseEventBridge;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterizes the currently distinct transport and projector paths without
 * treating an in-test inventory as evidence.
 */
class TransportParityCharacterizationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Async result store uses the configured 24-hour TTL for its Redis projection")
    void asyncStoreAppliesConfiguredTwentyFourHourTtl() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(redisson.<String>getBucket("custom:request-24h")).thenReturn(bucket);

        AsyncChatResultStore store = new AsyncChatResultStore(
                providerOf(redisson),
                OBJECT_MAPPER,
                null,
                " custom: ",
                24
        );

        AsyncChatResultPayload queued = store.markQueued(message("request-24h"));

        verify(bucket).set(OBJECT_MAPPER.writeValueAsString(queued), 24L, TimeUnit.HOURS);
    }

    @Test
    @DisplayName("Async result store clamps TTL and defaults a blank Redis prefix")
    void asyncStoreClampsTtlAndDefaultsBlankPrefix() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(redisson.<String>getBucket("springclaw:chat:async:request-clamped")).thenReturn(bucket);

        AsyncChatResultStore store = new AsyncChatResultStore(
                providerOf(redisson),
                OBJECT_MAPPER,
                null,
                "  ",
                0
        );

        AsyncChatResultPayload queued = store.markQueued(message("request-clamped"));

        verify(bucket).set(OBJECT_MAPPER.writeValueAsString(queued), 1L, TimeUnit.HOURS);
    }

    @Test
    @DisplayName("Caffeine remains the readable store when Redis is absent")
    void asyncStoreWritesAndReadsLocallyWithoutRedis() {
        AsyncChatResultStore store = new AsyncChatResultStore(
                providerOf(null),
                OBJECT_MAPPER,
                null,
                "results:",
                24
        );

        AsyncChatResultPayload completed = store.markCompleted(message(" local-only "), "answer", "model");

        assertThat(completed.requestId()).isEqualTo(" local-only ");
        assertThat(store.find("local-only"))
                .isEqualTo(new AsyncChatResultPayload(
                        "local-only",
                        completed.status(),
                        completed.sessionKey(),
                        completed.channel(),
                        completed.answer(),
                        completed.model(),
                        completed.createdAt(),
                        completed.completedAt(),
                        completed.errorMessage()
                ));
    }

    @Test
    @DisplayName("Redis is an optional projection and failures fall back to the local Caffeine result")
    void asyncStoreFallsBackToLocalWhenRedisWriteAndReadFail() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(redisson.<String>getBucket("results:request-fallback")).thenReturn(bucket);
        doThrow(new IllegalStateException("redis write unavailable"))
                .when(bucket).set(anyString(), eq(24L), eq(TimeUnit.HOURS));
        when(bucket.get()).thenThrow(new IllegalStateException("redis read unavailable"));
        AsyncChatResultStore store = new AsyncChatResultStore(
                providerOf(redisson),
                OBJECT_MAPPER,
                null,
                "results:",
                24
        );

        AsyncChatResultPayload failed = store.markFailed(message("request-fallback"), "boom");

        assertThat(store.find(" request-fallback ")).isEqualTo(failed);
    }

    @Test
    @DisplayName("Redis projection is read when available")
    void asyncStoreReadsOptionalRedisProjection() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<String> bucket = mock(RBucket.class);
        when(redisson.<String>getBucket("results:request-redis")).thenReturn(bucket);
        AsyncChatResultPayload projected = new AsyncChatResultPayload(
                "request-redis",
                "COMPLETED",
                "session",
                "api",
                "projected answer",
                "projected model",
                100L,
                200L,
                ""
        );
        when(bucket.get()).thenReturn(OBJECT_MAPPER.writeValueAsString(projected));
        AsyncChatResultStore store = new AsyncChatResultStore(
                providerOf(redisson),
                OBJECT_MAPPER,
                null,
                "results:",
                24
        );

        assertThat(store.find("request-redis")).isEqualTo(projected);
    }

    @Test
    @DisplayName("Rabbit consumer stores the polling result and projects the same payload over Rabbit and STOMP")
    void asyncConsumerProjectsCompletedResultToPollingRabbitAndStomp() {
        ChatService chatService = mock(ChatService.class);
        AsyncChatResultStore resultStore = mock(AsyncChatResultStore.class);
        ChatMessageProducer producer = mock(ChatMessageProducer.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        AsyncChatRequestMessage message = message("request-completed");
        RunStateRepository repository = mock(RunStateRepository.class);
        when(repository.requireByRunId(message.requestId()))
                .thenReturn(createdRun(message));
        ChatMessageConsumer consumer = new ChatMessageConsumer(
                chatService,
                resultStore,
                producer,
                messagingTemplate,
                repository
        );
        AcceptedChatCommand expectedCommand = new AcceptedChatCommand(
                message.requestId(),
                new ChatRequest(
                        message.sessionKey(),
                        message.userId(),
                        message.message(),
                        message.channel(),
                        message.responseMode(),
                        message.paradigm()
                )
        );
        ChatResponse response = new ChatResponse(
                message.requestId(),
                message.sessionKey(),
                "answer",
                "model",
                123L
        );
        AsyncChatResultPayload payload = new AsyncChatResultPayload(
                message.requestId(),
                "COMPLETED",
                message.sessionKey(),
                message.channel(),
                response.answer(),
                response.model(),
                message.createdAt(),
                456L,
                ""
        );
        when(chatService.chat(expectedCommand)).thenReturn(response);
        when(resultStore.markCompleted(message, response.answer(), response.model())).thenReturn(payload);

        consumer.consume(message);

        verify(chatService).chat(expectedCommand);
        verify(resultStore).markCompleted(message, response.answer(), response.model());
        verify(producer).sendResponse(payload);
        verify(messagingTemplate).convertAndSend("/topic/chat/request-completed", payload);
    }

    @Test
    @DisplayName("Rabbit consumer stores and projects the same failed payload when chat execution fails")
    void asyncConsumerProjectsFailedResultToPollingRabbitAndStomp() {
        ChatService chatService = mock(ChatService.class);
        AsyncChatResultStore resultStore = mock(AsyncChatResultStore.class);
        ChatMessageProducer producer = mock(ChatMessageProducer.class);
        SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
        AsyncChatRequestMessage message = message("request-failed");
        RunStateRepository repository = mock(RunStateRepository.class);
        when(repository.requireByRunId(message.requestId()))
                .thenReturn(createdRun(message));
        ChatMessageConsumer consumer = new ChatMessageConsumer(
                chatService,
                resultStore,
                producer,
                messagingTemplate,
                repository
        );
        AcceptedChatCommand expectedCommand = new AcceptedChatCommand(
                message.requestId(),
                new ChatRequest(
                        message.sessionKey(),
                        message.userId(),
                        message.message(),
                        message.channel(),
                        message.responseMode(),
                        message.paradigm()
                )
        );
        AsyncChatResultPayload payload = new AsyncChatResultPayload(
                message.requestId(),
                "FAILED",
                message.sessionKey(),
                message.channel(),
                "",
                "",
                message.createdAt(),
                456L,
                "chat unavailable"
        );
        when(chatService.chat(expectedCommand))
                .thenThrow(new IllegalStateException("chat unavailable"));
        when(resultStore.markFailed(message, "chat unavailable")).thenReturn(payload);

        consumer.consume(message);

        verify(chatService).chat(expectedCommand);
        verify(resultStore).markFailed(message, "chat unavailable");
        verify(producer).sendResponse(payload);
        verify(messagingTemplate).convertAndSend("/topic/chat/request-failed", payload);
    }

    @Test
    @DisplayName("REST sync, SSE, async submission, and polling paths are bound to actual controller methods")
    void controllerTransportInventoryIsBoundToProductionMethods() throws Exception {
        assertThat(ChatController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/chat");

        Method sync = ChatController.class.getMethod("send", ChatRequest.class);
        assertThat(sync.getAnnotation(PostMapping.class).value()).containsExactly("/send");
        assertThat(sync.getReturnType().getSimpleName()).isEqualTo("ApiResponse");

        Method sse = ChatController.class.getMethod("stream", ChatRequest.class);
        PostMapping sseMapping = sse.getAnnotation(PostMapping.class);
        assertThat(sseMapping.value()).containsExactly("/stream");
        assertThat(sseMapping.produces()).containsExactly(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(sse.getReturnType()).isEqualTo(SseEmitter.class);

        Method submitAsync = ChatController.class.getMethod("sendAsync", ChatRequest.class);
        assertThat(submitAsync.getAnnotation(PostMapping.class).value()).containsExactly("/async");

        Method pollAsync = ChatController.class.getMethod("asyncResult", String.class);
        assertThat(pollAsync.getAnnotation(GetMapping.class).value()).containsExactly("/async/{requestId}");
        assertThat(pollAsync.getReturnType().getSimpleName()).isEqualTo("ApiResponse");
    }

    @Test
    @DisplayName("Rabbit, local Caffeine, optional Redis, and WebSocket/STOMP projectors are actual production members")
    void asyncTransportAndProjectorInventoryIsBoundToProductionMembers() throws Exception {
        Method consume = ChatMessageConsumer.class.getMethod("consume", AsyncChatRequestMessage.class);
        assertThat(consume.getAnnotation(RabbitListener.class).queues())
                .containsExactly("${springclaw.rabbitmq.chat-request-queue:chat.request.queue}");

        Constructor<AsyncChatResultStore> storeConstructor = AsyncChatResultStore.class.getConstructor(
                ObjectProvider.class,
                ObjectMapper.class,
                com.springclaw.runtime.lifecycle.RunStateRepository.class,
                String.class,
                long.class
        );
        assertThat(storeConstructor.getParameterTypes()[0]).isEqualTo(ObjectProvider.class);
        assertThat(storeConstructor.getParameters()[3].getAnnotation(Value.class).value())
                .isEqualTo("${springclaw.rabbitmq.async-result-key-prefix:springclaw:chat:async:}");
        assertThat(storeConstructor.getParameters()[4].getAnnotation(Value.class).value())
                .isEqualTo("${springclaw.rabbitmq.async-result-ttl-hours:24}");
        assertThat(AsyncChatResultStore.class.getMethod(
                "markQueued",
                AsyncChatRequestMessage.class
        ).getReturnType()).isEqualTo(AsyncChatResultPayload.class);
        assertThat(AsyncChatResultStore.class.getMethod("find", String.class).getReturnType())
                .isEqualTo(AsyncChatResultPayload.class);

        AsyncChatResultStore localFallbackStore = new AsyncChatResultStore(
                providerOf(null),
                OBJECT_MAPPER,
                null,
                "inventory:",
                24
        );
        AsyncChatResultPayload locallyStored = localFallbackStore.markQueued(message("inventory-local"));
        assertThat(localFallbackStore.find("inventory-local")).isEqualTo(locallyStored);

        assertThat(WebSocketConfig.class.isAnnotationPresent(EnableWebSocketMessageBroker.class)).isTrue();
        WebSocketConfig webSocketConfig = new WebSocketConfig();
        MessageBrokerRegistry brokerRegistry = mock(MessageBrokerRegistry.class);
        webSocketConfig.configureMessageBroker(brokerRegistry);
        verify(brokerRegistry).enableSimpleBroker("/topic");
        verify(brokerRegistry).setApplicationDestinationPrefixes("/app");

        StompEndpointRegistry endpointRegistry = mock(StompEndpointRegistry.class);
        StompWebSocketEndpointRegistration endpointRegistration =
                mock(StompWebSocketEndpointRegistration.class);
        when(endpointRegistry.addEndpoint("/ws/chat")).thenReturn(endpointRegistration);
        webSocketConfig.registerStompEndpoints(endpointRegistry);
        verify(endpointRegistration).setAllowedOriginPatterns("*");
    }

    @Test
    @DisplayName("Conversation persistence and SSE trace projection remain distinct production collaborators")
    void persistenceAndTraceInventoryIsBoundToProductionMembers() throws Exception {
        assertThat(ChatResultPersister.class.getMethod(
                "persist",
                ChatContext.class,
                String.class,
                ChatExecutionResult.class,
                ChatPersistenceIntent.class
        )).isNotNull();

        assertThat(SseEventBridge.class.getMethod(
                "sendTrace",
                SseEmitter.class,
                ChatContext.class,
                String.class,
                String.class,
                String.class,
                String.class,
                long.class
        )).isNotNull();
        assertThat(SseEventBridge.class.getMethod(
                "recordRunTrace",
                ChatContext.class,
                com.springclaw.service.agent.AgentRun.class
        )).isNotNull();
        assertThat(Arrays.stream(SseEventBridge.class.getDeclaredConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .anyMatch(AgentRunTraceService.class::equals)).isTrue();
    }

    private static RunState createdRun(AsyncChatRequestMessage message) {
        Instant acceptedAt = Instant.ofEpochMilli(message.createdAt());
        return new RunState(
                message.requestId(), message.requestId(), 0, RunStatus.CREATED,
                message.sessionKey(), message.channel(), message.userId(),
                SessionAccessClaim.personal(
                        SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                        message.channel(),
                        message.sessionKey(),
                        message.userId()
                ),
                "USER",
                message.message(),
                message.responseMode() == null ? "agent" : message.responseMode(),
                acceptedAt, null, acceptedAt, null,
                acceptedAt.plus(Duration.ofMinutes(30)),
                null, null, "", 1, "", List.of(), null, null, Map.of(), null,
                null
        );
    }

    private static AsyncChatRequestMessage message(String requestId) {
        return new AsyncChatRequestMessage(
                requestId,
                "session",
                "user",
                "hello",
                "api",
                100L,
                "standard",
                null
        );
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RedissonClient> providerOf(RedissonClient client) {
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return provider;
    }

}
