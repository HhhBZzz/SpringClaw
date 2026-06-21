package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.context.AssembledContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Component
public final class LegacyRunContextAdapter {

    public ContextSnapshot adapt(ChatContext context, Instant capturedAt) {
        AssembledContext assembled = context.assembled();
        AssembledContext.ContextSourceSummary summary = assembled.sourceSummary();
        Map<String, String> provider = providerSnapshot(context.activeClient());
        Map<String, String> sources = Map.of(
                "schema", summary.schema(),
                "memoryBankUsed", Boolean.toString(summary.memoryBankUsed()),
                "memoryBankChars", Integer.toString(summary.memoryBankChars()),
                "shortTermChars", Integer.toString(summary.shortTermChars()),
                "semanticMemoryChars", Integer.toString(summary.semanticMemoryChars()),
                "observePromptChars", Integer.toString(summary.observePromptChars()),
                "memoryLearningActiveCount",
                Integer.toString(summary.memoryLearningActiveCount()),
                "memoryLearningFilteredCount",
                Integer.toString(summary.memoryLearningFilteredCount())
        );
        List<String> allowedCapabilities = context.decision() == null
                ? List.of()
                : context.decision().selectedCapabilities();
        String ownerUserId = context.session().getUserId();
        if (!StringUtils.hasText(ownerUserId)) {
            ownerUserId = context.userId();
        }
        String hashInput = String.join(
                "\n",
                context.requestId(),
                context.session().getSessionKey(),
                context.channel(),
                context.userId(),
                context.roleCode(),
                context.userMessage(),
                context.effectiveUserMessage(),
                context.systemPrompt(),
                assembled.observePrompt(),
                assembled.eventContext(),
                assembled.semanticContext(),
                provider.toString(),
                sources.toString(),
                allowedCapabilities.toString()
        );
        return new ContextSnapshot(
                context.requestId(),
                context.session().getSessionKey(),
                ownerUserId,
                context.channel(),
                context.userId(),
                context.roleCode(),
                context.userMessage(),
                context.effectiveUserMessage(),
                context.systemPrompt(),
                "",
                textList(assembled.eventContext()),
                textList(assembled.semanticContext()),
                List.of(),
                allowedCapabilities,
                provider,
                sources,
                capturedAt,
                sha256(hashInput)
        );
    }

    private static Map<String, String> providerSnapshot(
            AiProviderService.ActiveChatClient activeClient
    ) {
        if (activeClient == null) {
            return Map.of();
        }
        return Map.of(
                "providerId", safe(activeClient.providerId()),
                "model", safe(activeClient.model()),
                "baseUrl", safe(activeClient.baseUrl()),
                "available", Boolean.toString(activeClient.available())
        );
    }

    private static List<String> textList(String value) {
        return StringUtils.hasText(value) ? List.of(value) : List.of();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
