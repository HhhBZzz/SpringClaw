package com.springclaw.runtime.bridge;

import com.springclaw.domain.entity.AgentSession;
import com.springclaw.runtime.contract.ContextSnapshot;
import com.springclaw.runtime.contract.ExecutionDecision;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.service.context.ContextInjection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

final class LegacyLifecycleObserverCanonicalModeTestContext {

    private LegacyLifecycleObserverCanonicalModeTestContext() {
    }

    static ChatContext context(String runId) {
        AgentSession session = new AgentSession();
        session.setSessionKey("session-1");
        session.setUserId("user-1");
        session.setChannel("api");
        AssembledContext assembled = new AssembledContext(
                "session-1",
                "api",
                "user-1",
                "effective",
                "short-term",
                "semantic",
                "observe-prompt"
        );
        return new ChatContext(
                session,
                "api",
                "user-1",
                "USER",
                "original",
                "effective",
                runId,
                "system",
                assembled,
                new AiProviderService.ActiveChatClient(
                        "provider",
                        "model",
                        "https://example.test",
                        null,
                        true,
                        ""
                ),
                "simplified",
                "legacy routing",
                "agent",
                "web_research",
                new AgentDecision(
                        "web_research",
                        "agent_tools",
                        List.of("web"),
                        "read",
                        false,
                        "legacy decision"
                ),
                new ContextInjection(
                        "observe-prompt",
                        "",
                        "",
                        Map.of("contextSummary", assembled.sourceSummary())
                )
        );
    }

    static ContextSnapshot snapshot(String runId, Instant at) {
        return new ContextSnapshot(
                runId,
                "session-1",
                "user-1",
                "api",
                "user-1",
                "USER",
                "original",
                "effective",
                "system",
                "provider:model",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                Map.of(),
                new MemoryFrame(
                        runId,
                        MemoryScope.from(claim()),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        Map.of(),
                        List.of(),
                        at,
                        "frame-hash-" + runId
                ),
                at,
                "snapshot-hash-" + runId
        );
    }

    static ExecutionDecision decision(String runId, Instant at) {
        return new ExecutionDecision(
                runId,
                "web_research",
                "agent_tools",
                "agent",
                "read",
                List.of("web"),
                List.of(),
                Map.of(),
                List.of(),
                0.8,
                "legacy decision",
                "legacy",
                at
        );
    }

    private static SessionAccessClaim claim() {
        return SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                "api",
                "session-1",
                "user-1"
        );
    }
}
