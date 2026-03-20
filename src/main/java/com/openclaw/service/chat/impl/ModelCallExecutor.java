package com.openclaw.service.chat.impl;

import com.openclaw.service.ai.AiProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final int maxFailoverAttempts;

    public ModelCallExecutor(AiProviderService aiProviderService,
                             ModelTransportGuardService modelTransportGuardService,
                             @Value("${openclaw.ai.max-failover-attempts:1}") int maxFailoverAttempts) {
        this.aiProviderService = aiProviderService;
        this.modelTransportGuardService = modelTransportGuardService;
        this.maxFailoverAttempts = Math.max(0, maxFailoverAttempts);
    }

    public <T> ModelCallResult<T> execute(AiProviderService.ActiveChatClient activeClient,
                                          String source,
                                          boolean allowFailover,
                                          ModelOperation<T> operation) throws Exception {
        List<String> attemptedModels = new ArrayList<>();
        AiProviderService.ActiveChatClient currentClient = resolveInitialClient(activeClient, source, attemptedModels);
        boolean failedOver = !sameModel(activeClient.model(), currentClient.model());
        Exception lastFailure = null;
        int failoversUsed = failedOver ? 1 : 0;

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
                modelTransportGuardService.markModelFailure(currentClient.providerId(), currentClient.model(), failure);
                if (lastFailure != null) {
                    failure.addSuppressed(lastFailure);
                }
                lastFailure = failure;
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
            }
        }
        if (lastFailure != null) {
            modelTransportGuardService.markProviderFailure(activeClient.providerId(), lastFailure);
            throw lastFailure;
        }
        throw new IllegalStateException("未找到可用模型执行请求");
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

    public record ModelCallResult<T>(T value,
                                     AiProviderService.ActiveChatClient client,
                                     List<String> attemptedModels,
                                     boolean failedOver) {
    }
}
