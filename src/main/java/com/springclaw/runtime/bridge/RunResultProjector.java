package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.CompletionDecision;
import com.springclaw.runtime.contract.RunResult;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public final class RunResultProjector {

    public TerminalObservation adaptDegraded(
            ChatContext context,
            ChatExecutionResult executionResult,
            String answer,
            Instant completedAt
    ) {
        List<String> evidenceRefs = evidenceRefs(executionResult);
        CompletionDecision decision = new CompletionDecision(
                context.requestId(),
                CompletionDecision.Outcome.DEGRADE,
                "LEGACY_UNVERIFIED_RESULT",
                "Legacy execution returned an answer without canonical completion verification.",
                evidenceRefs,
                List.of("canonical-completion-verification"),
                false,
                0,
                0.0,
                completedAt
        );
        AiProviderService.ActiveChatClient activeClient = context.activeClient();
        RunResult result = new RunResult(
                context.requestId(),
                RunStatus.DEGRADED,
                answer,
                RunResult.AnswerKind.DEGRADED,
                activeClient == null ? "" : activeClient.providerId(),
                activeClient == null ? "" : activeClient.model(),
                evidenceRefs,
                List.of(),
                0.0,
                Map.of(),
                "",
                "",
                completedAt
        );
        return new TerminalObservation(decision, result);
    }

    private static List<String> evidenceRefs(ChatExecutionResult result) {
        List<String> refs = new ArrayList<>();
        if (StringUtils.hasText(result.observe())) {
            refs.add("legacy:observe");
        }
        if (StringUtils.hasText(result.plan())) {
            refs.add("legacy:plan");
        }
        if (StringUtils.hasText(result.action())) {
            refs.add("legacy:action");
        }
        if (StringUtils.hasText(result.reflect())) {
            refs.add("legacy:reflect");
        }
        return List.copyOf(refs);
    }

    public record TerminalObservation(
            CompletionDecision decision,
            RunResult result
    ) {
    }
}
