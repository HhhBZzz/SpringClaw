package com.springclaw.service.memory.extraction;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class ProviderMemoryModelClient {

    private static final Logger log = LoggerFactory.getLogger(ProviderMemoryModelClient.class);

    private final AiProviderService aiProviderService;

    ProviderMemoryModelClient(AiProviderService aiProviderService) {
        this.aiProviderService = aiProviderService;
    }

    String call(
            String providerId,
            String fallbackProviderId,
            String source,
            TerminalMemoryExtractionContext context,
            String systemPrompt,
            String userPrompt
    ) throws Exception {
        Exception firstFailure = null;
        if (StringUtils.hasText(providerId)) {
            try {
                return callProvider(providerId.trim(), source, context, systemPrompt, userPrompt);
            } catch (Exception ex) {
                firstFailure = ex;
                log.warn("memory model call failed, provider={}, source={}, runId={}, reason={}",
                        providerId, source, context.runId(), ex.getMessage());
            }
        }
        if (StringUtils.hasText(fallbackProviderId)
                && !fallbackProviderId.trim().equalsIgnoreCase(providerId == null ? "" : providerId.trim())) {
            try {
                return callProvider(fallbackProviderId.trim(), source, context, systemPrompt, userPrompt);
            } catch (Exception fallbackFailure) {
                if (firstFailure != null) {
                    fallbackFailure.addSuppressed(firstFailure);
                }
                throw fallbackFailure;
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        throw new IllegalStateException("memory model provider is not configured");
    }

    private String callProvider(
            String providerId,
            String source,
            TerminalMemoryExtractionContext context,
            String systemPrompt,
            String userPrompt
    ) throws Exception {
        AiProviderService.ActiveChatClient client = aiProviderService.clientForProvider(providerId);
        if (client.chatClient() == null) {
            throw new IllegalStateException("memory model provider has no chat client: " + providerId);
        }
        var response = client.chatClient().prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .chatResponse();
        log.info("memory model call success: provider={}, model={}, source={}, runId={}",
                client.providerId(), client.model(), source, context.runId());
        return ModelCallExecutor.extractText(response);
    }
}
