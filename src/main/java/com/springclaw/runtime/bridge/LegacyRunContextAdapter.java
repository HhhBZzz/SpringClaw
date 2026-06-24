package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryFrameItem;
import com.springclaw.runtime.memory.contract.MemoryFrameLayer;
import com.springclaw.runtime.memory.contract.MemoryFrameSourceKind;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryScopeType;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.context.AssembledContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
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
                provider.getOrDefault("providerId", ""),
                provider.getOrDefault("model", ""),
                provider.getOrDefault("baseUrl", ""),
                provider.getOrDefault("available", ""),
                summary.schema(),
                Boolean.toString(summary.memoryBankUsed()),
                Integer.toString(summary.memoryBankChars()),
                Integer.toString(summary.shortTermChars()),
                Integer.toString(summary.semanticMemoryChars()),
                Integer.toString(summary.observePromptChars()),
                Integer.toString(summary.memoryLearningActiveCount()),
                Integer.toString(summary.memoryLearningFilteredCount()),
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
                memoryFrameFromLegacy(
                        context.requestId(),
                        context.channel(),
                        context.session().getSessionKey(),
                        context.userId(),
                        assembled,
                        capturedAt
                ),
                capturedAt,
                sha256(hashInput)
        );
    }

    /**
     * 从 legacy assembled 字段构建兼容性 MemoryFrame。不调用 MemoryCoordinator；
     * 仅在 eventContext/semanticContext 非空时放入兼容性 MemoryFrameItem。
     */
    private static MemoryFrame memoryFrameFromLegacy(
            String runId,
            String channel,
            String sessionKey,
            String userId,
            AssembledContext assembled,
            Instant capturedAt
    ) {
        MemoryScope scope = MemoryScope.from(SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                channel,
                sessionKey,
                userId
        ));
        List<MemoryFrameItem> shortTerm = new ArrayList<>();
        if (StringUtils.hasText(assembled.eventContext())) {
            shortTerm.add(legacyItem(assembled.eventContext(), MemoryFrameLayer.SHORT_TERM,
                    "legacy-short-term"));
        }
        List<MemoryFrameItem> semantic = new ArrayList<>();
        if (StringUtils.hasText(assembled.semanticContext())) {
            semantic.add(legacyItem(assembled.semanticContext(), MemoryFrameLayer.SEMANTIC_FACT,
                    "legacy-semantic"));
        }
        String frameHash = sha256(String.join("\n",
                runId, scope.scopeId(), assembled.eventContext(), assembled.semanticContext()));
        return new MemoryFrame(
                runId,
                scope,
                List.of(),
                List.copyOf(shortTerm),
                List.of(),
                List.copyOf(semantic),
                List.of(),
                List.of(),
                Map.of("source", "legacy-run-context-adapter"),
                List.of(),
                capturedAt,
                frameHash
        );
    }

    private static MemoryFrameItem legacyItem(String content, MemoryFrameLayer layer, String sourceId) {
        return new MemoryFrameItem(
                sourceId,
                MemoryFrameSourceKind.MESSAGE_EVENT,
                layer,
                null,
                null,
                MemoryType.EPISODIC,
                MemoryScopeType.PERSONAL_SESSION,
                null,
                content,
                sha256(content),
                List.of(),
                0.0,
                0.0,
                0.0,
                1,
                Instant.now()
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
