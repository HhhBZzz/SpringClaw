package com.springclaw.service.chat.impl;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.usage.LlmUsageRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一封装模型调用与同 provider 内模型 failover。
 */
@Service
public class ModelCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(ModelCallExecutor.class);

    private final AiProviderService aiProviderService;
    private final ModelTransportGuardService modelTransportGuardService;
    private final LlmUsageRecordService llmUsageRecordService;
    private final int maxFailoverAttempts;
    private final int maxSameModelRetries;

    @Autowired
    public ModelCallExecutor(AiProviderService aiProviderService,
                             ModelTransportGuardService modelTransportGuardService,
                             LlmUsageRecordService llmUsageRecordService,
                             @Value("${springclaw.ai.max-failover-attempts:1}") int maxFailoverAttempts,
                             @Value("${springclaw.ai.same-model-retry-attempts:1}") int maxSameModelRetries) {
        this.aiProviderService = aiProviderService;
        this.modelTransportGuardService = modelTransportGuardService;
        this.llmUsageRecordService = llmUsageRecordService;
        this.maxFailoverAttempts = Math.max(0, maxFailoverAttempts);
        this.maxSameModelRetries = Math.max(0, maxSameModelRetries);
    }

    ModelCallExecutor(AiProviderService aiProviderService,
                      ModelTransportGuardService modelTransportGuardService,
                      LlmUsageRecordService llmUsageRecordService,
                      int maxFailoverAttempts) {
        this(aiProviderService, modelTransportGuardService, llmUsageRecordService, maxFailoverAttempts, 0);
    }

    public <T> ModelCallResult<T> execute(AiProviderService.ActiveChatClient activeClient,
                                          String source,
                                          boolean allowFailover,
                                          ModelOperation<T> operation) throws Exception {
        return executeInternal(activeClient, source, allowFailover, operation);
    }

    public <T> ModelCallResult<T> executeChat(AiProviderService.ActiveChatClient activeClient,
                                              String source,
                                              ChatRequestContext requestContext,
                                              boolean allowFailover,
                                              ChatOperation<T> operation) throws Exception {
        return executeInternal(
                activeClient,
                source,
                allowFailover,
                client -> {
                    ChatOperationResult<T> result = operation.execute(client);
                    if (result != null && result.chatResponse() != null) {
                        llmUsageRecordService.recordChatResponse(
                                new LlmUsageRecordService.ChatResponseContext(
                                        requestContext == null ? "" : requestContext.requestId(),
                                        requestContext == null ? "" : requestContext.sessionKey(),
                                        requestContext == null ? "" : requestContext.channel(),
                                        requestContext == null ? "" : requestContext.userId(),
                                        client.providerId(),
                                        client.model(),
                                        source
                                ),
                                result.chatResponse()
                        );
                    }
                    return result == null ? null : result.value();
                }
        );
    }

    private <T> ModelCallResult<T> executeInternal(AiProviderService.ActiveChatClient activeClient,
                                                   String source,
                                                   boolean allowFailover,
                                                   ModelOperation<T> operation) throws Exception {
        List<String> attemptedModels = new ArrayList<>();
        AiProviderService.ActiveChatClient currentClient = resolveInitialClient(activeClient, source, attemptedModels);
        boolean failedOver = !sameModel(activeClient.model(), currentClient.model());
        Exception lastFailure = null;
        int failoversUsed = failedOver ? 1 : 0;
        int sameModelRetriesUsed = 0;

        while (currentClient != null) {
            attemptedModels.add(currentClient.model());
            try {
                return new ModelCallResult<>(
                        operation.execute(currentClient),
                        currentClient,
                        List.copyOf(attemptedModels),
                        failedOver
                );
            } catch (Exception failure) {
                if (!allowFailover || !modelTransportGuardService.isTransportFailure(failure)) {
                    throw failure;
                }
                if (lastFailure != null) {
                    failure.addSuppressed(lastFailure);
                }
                lastFailure = failure;
                if (sameModelRetriesUsed < maxSameModelRetries) {
                    sameModelRetriesUsed++;
                    log.warn("模型调用出现传输中断，重试同一模型: provider={}, model={}, attempt={}/{}, source={}, reason={}",
                            currentClient.providerId(),
                            currentClient.model(),
                            sameModelRetriesUsed,
                            maxSameModelRetries,
                            source,
                            failure.getMessage());
                    continue;
                }
                modelTransportGuardService.markModelFailure(currentClient.providerId(), currentClient.model(), failure);
                if (failoversUsed >= maxFailoverAttempts) {
                    break;
                }
                AiProviderService.ActiveChatClient nextClient = resolveNextClient(
                        currentClient,
                        source,
                        attemptedModels
                );
                if (nextClient == null) {
                    break;
                }
                failedOver = true;
                failoversUsed++;
                log.warn("模型调用失败，尝试同 provider 模型切换: provider={}, from={}, to={}, source={}",
                        currentClient.providerId(), currentClient.model(), nextClient.model(), source);
                currentClient = nextClient;
                sameModelRetriesUsed = 0;
            }
        }
        if (lastFailure != null) {
            modelTransportGuardService.markProviderFailure(activeClient.providerId(), lastFailure);
            throw lastFailure;
        }
        throw new IllegalStateException("未找到可用模型执行请求");
    }

    public static String extractText(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null) {
            return "";
        }
        Generation generation = chatResponse.getResult();
        if (generation.getOutput() == null) {
            return "";
        }
        return generation.getOutput().getText();
    }

    private AiProviderService.ActiveChatClient resolveInitialClient(AiProviderService.ActiveChatClient activeClient,
                                                                    String source,
                                                                    List<String> attemptedModels) {
        if (!modelTransportGuardService.isModelCoolingDown(activeClient.providerId(), activeClient.model())) {
            return activeClient;
        }
        AiProviderService.ActiveChatClient nextClient = resolveNextClient(activeClient, source, attemptedModels);
        if (nextClient != null) {
            log.info("当前模型仍在冷却，直接切换到同 provider 备用模型: provider={}, from={}, to={}, source={}",
                    activeClient.providerId(), activeClient.model(), nextClient.model(), source);
            return nextClient;
        }
        return activeClient;
    }

    private AiProviderService.ActiveChatClient resolveNextClient(AiProviderService.ActiveChatClient currentClient,
                                                                 String source,
                                                                 List<String> attemptedModels) {
        for (String candidateModel : aiProviderService.listFailoverModels(currentClient.providerId(), currentClient.model())) {
            if (!StringUtils.hasText(candidateModel)
                    || attemptedModels.contains(candidateModel)
                    || modelTransportGuardService.isModelCoolingDown(currentClient.providerId(), candidateModel)) {
                continue;
            }
            return aiProviderService.activateModel(currentClient.providerId(), candidateModel, source);
        }
        return null;
    }

    private boolean sameModel(String left, String right) {
        return StringUtils.hasText(left)
                && StringUtils.hasText(right)
                && left.trim().equalsIgnoreCase(right.trim());
    }

    @FunctionalInterface
    public interface ModelOperation<T> {
        T execute(AiProviderService.ActiveChatClient client) throws Exception;
    }

    @FunctionalInterface
    public interface ChatOperation<T> {
        ChatOperationResult<T> execute(AiProviderService.ActiveChatClient client) throws Exception;
    }

    public record ModelCallResult<T>(T value,
                                     AiProviderService.ActiveChatClient client,
                                     List<String> attemptedModels,
                                     boolean failedOver) {
    }

    public record ChatOperationResult<T>(T value, ChatResponse chatResponse) {
    }

    public record ChatRequestContext(String requestId,
                                     String sessionKey,
                                     String channel,
                                     String userId) {
    }
}
