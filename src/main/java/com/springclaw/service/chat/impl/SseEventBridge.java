package com.springclaw.service.chat.impl;

import com.springclaw.common.util.TextUtils;
import com.springclaw.service.agent.AgentActionProposal;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentQualityScore;
import com.springclaw.service.agent.AgentRun;
import com.springclaw.service.agent.AgentRunTraceEvent;
import com.springclaw.service.agent.AgentRunTraceService;
import com.springclaw.service.agent.CapabilityResult;
import com.springclaw.service.agent.VerificationResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * SSE 事件桥接层。
 * <p>
 * 负责所有 SSE 事件的 JSON 序列化与推送，以及 AgentRunTrace 的持久化记录。
 * 从 ChatServiceImpl 中剥离，使编排层专注业务流程。
 */
@Component
public class SseEventBridge {

    private final AgentRunTraceService agentRunTraceService;

    @Autowired
    public SseEventBridge(@Autowired(required = false) AgentRunTraceService agentRunTraceService) {
        this.agentRunTraceService = agentRunTraceService;
    }

    // ── 事件发送方法 ──

    public void sendToken(SseEmitter emitter, String token) {
        sendEvent(emitter, "token", token);
    }

    public void sendStatus(SseEmitter emitter, String status) {
        sendEvent(emitter, "status", status);
    }

    public void sendMeta(SseEmitter emitter, ChatContext context) {
        if (context == null) {
            return;
        }
        String productMode = AgentProductMode.resolve(context);
        if (agentRunTraceService != null) {
            agentRunTraceService.recordRunMetadata(
                    context.session() == null ? null : context.session().getSessionKey(),
                    context.channel(),
                    context.userId(),
                    context.requestId(),
                    normalizeResponseMode(context.responseMode()),
                    context.executionMode(),
                    context.intent(),
                    productMode
            );
        }
        String payload = """
                {"requestId":"%s","productMode":"%s","responseMode":"%s","executionMode":"%s","intent":"%s","routingReason":"%s","originalQuestion":"%s","effectiveQuestion":"%s"}
                """.formatted(
                jsonEscape(context.requestId()),
                jsonEscape(productMode),
                jsonEscape(normalizeResponseMode(context.responseMode())),
                jsonEscape(context.executionMode()),
                jsonEscape(context.intent()),
                jsonEscape(context.routingReason()),
                jsonEscape(context.userMessage()),
                jsonEscape(context.effectiveUserMessage())
        ).trim();
        sendEvent(emitter, "meta", payload);
    }

    public void sendDecision(SseEmitter emitter, ChatContext context) {
        AgentDecision decision = context == null ? null : context.decision();
        if (decision == null) {
            return;
        }
        String capabilities = decision.selectedCapabilities() == null
                ? ""
                : String.join(",", decision.selectedCapabilities());
        String payload = """
                {"requestId":"%s","intent":"%s","executionPath":"%s","selectedCapabilities":"%s","riskLevel":"%s","requiresConfirmation":%s,"reason":"%s"}
                """.formatted(
                jsonEscape(context.requestId()),
                jsonEscape(decision.intent()),
                jsonEscape(decision.executionPath()),
                jsonEscape(capabilities),
                jsonEscape(decision.riskLevel()),
                decision.requiresConfirmation(),
                jsonEscape(decision.reason())
        ).trim();
        sendEvent(emitter, "decision", payload);
    }

    public void sendActionRequired(SseEmitter emitter, AgentActionProposal proposal) {
        if (proposal == null) {
            return;
        }
        String payload = """
                {"proposalId":"%s","requestId":"%s","actionType":"%s","title":"%s","summary":"%s","riskLevel":"%s","expiresAt":%d,"status":"%s"}
                """.formatted(
                jsonEscape(proposal.proposalId()),
                jsonEscape(proposal.requestId()),
                jsonEscape(proposal.actionType()),
                jsonEscape(proposal.title()),
                jsonEscape(proposal.summary()),
                jsonEscape(proposal.riskLevel()),
                proposal.expiresAt(),
                jsonEscape(proposal.status())
        ).trim();
        sendEvent(emitter, "action_required", payload);
    }

    public void sendCapabilityCall(SseEmitter emitter,
                                    String eventName,
                                    String requestId,
                                    CapabilityResult result) {
        if (result == null) {
            return;
        }
        String payload = """
                {"requestId":"%s","capabilityId":"%s","toolset":"%s","status":"%s","summary":"%s","durationMs":%d,"riskLevel":"%s","payload":"%s"}
                """.formatted(
                jsonEscape(requestId),
                jsonEscape(result.capabilityId()),
                jsonEscape(result.toolset()),
                jsonEscape(result.status()),
                jsonEscape(result.summary()),
                result.durationMs(),
                jsonEscape(result.riskLevel()),
                jsonEscape(truncateForEvent(result.payload(), 1200))
        ).trim();
        sendEvent(emitter, eventName, payload);
    }

    public void sendVerification(SseEmitter emitter, String requestId, VerificationResult verification) {
        if (verification == null) {
            return;
        }
        String payload = """
                {"requestId":"%s","status":"%s","sufficient":%s,"summary":"%s","qualityScore":%d,"qualityLevel":"%s","quality":%s}
                """.formatted(
                jsonEscape(requestId),
                jsonEscape(verification.status()),
                verification.sufficient(),
                jsonEscape(verification.summary()),
                verification.quality().overallScore(),
                jsonEscape(verification.quality().level()),
                renderQualityObject(verification.quality())
        ).trim();
        sendEvent(emitter, "verification", payload);
    }

    public void sendTrace(SseEmitter emitter,
                           String requestId,
                           String stepName,
                           String type,
                           String status,
                           String detail,
                           long durationMs) {
        AgentRunTraceEvent event = new AgentRunTraceEvent(
                TextUtils.safe(requestId),
                TextUtils.safe(stepName),
                TextUtils.safe(type),
                TextUtils.safe(status),
                TextUtils.safe(detail),
                Math.max(0L, durationMs),
                System.currentTimeMillis()
        );
        sendEvent(emitter, "trace", renderTracePayload(event));
    }

    public void sendTrace(SseEmitter emitter,
                           ChatContext context,
                           String stepName,
                           String type,
                           String status,
                           String detail,
                           long durationMs) {
        sendTrace(emitter, context, stepName, type, status, detail, durationMs, null);
    }

    public void sendTrace(SseEmitter emitter,
                           ChatContext context,
                           String stepName,
                           String type,
                           String status,
                           String detail,
                           long durationMs,
                           AgentQualityScore quality) {
        if (context == null) {
            sendTrace(emitter, "", stepName, type, status, detail, durationMs);
            return;
        }
        AgentRunTraceEvent event = agentRunTraceService == null
                ? new AgentRunTraceEvent(context.requestId(), stepName, type, status, TextUtils.safe(detail), Math.max(0L, durationMs), System.currentTimeMillis(),
                quality == null ? null : quality.overallScore(),
                quality == null ? "" : quality.level(),
                quality == null ? "" : renderQualityObject(quality))
                : agentRunTraceService.record(
                context.session().getSessionKey(),
                context.channel(),
                context.userId(),
                context.requestId(),
                stepName,
                type,
                status,
                detail,
                durationMs,
                quality
        );
        sendEvent(emitter, "trace", renderTracePayload(event));
    }

    public void sendError(SseEmitter emitter, String error) {
        sendEvent(emitter, "error", error == null ? "" : error);
    }

    public void completeEmitter(SseEmitter emitter) {
        sendEvent(emitter, "done", "done");
        emitter.complete();
    }

    public void sendAnswerChunks(SseEmitter emitter, String answer) {
        String text = answer == null ? "" : answer;
        if (!StringUtils.hasText(text)) {
            return;
        }
        int chunkSize = 48;
        for (int start = 0; start < text.length(); start += chunkSize) {
            int end = Math.min(text.length(), start + chunkSize);
            sendToken(emitter, text.substring(start, end));
        }
    }

    // ── Agent Run 事件（SSE 发送 + Trace 持久化） ──

    /**
     * 发送 AgentRun 的所有 SSE 事件（capability/skill call + verification），
     * 同时持久化 trace 记录。
     */
    public void emitAndRecordRun(SseEmitter emitter, ChatContext context, AgentRun run) {
        if (run == null) {
            return;
        }
        for (CapabilityResult result : run.capabilityResults()) {
            String eventName = "skills".equalsIgnoreCase(result.toolset()) ? "skill_call" : "tool_call";
            sendCapabilityCall(emitter, eventName, context.requestId(), result);
            sendTrace(emitter, context,
                    result.capabilityId(),
                    eventName.equals("skill_call") ? "skill" : "tool",
                    normalizeCapabilityStatus(result.status()),
                    result.summary() + " · " + truncateForEvent(result.payload(), 320),
                    result.durationMs());
        }
        sendVerification(emitter, context.requestId(), run.verification());
        if (run.verification() != null) {
            sendTrace(emitter, context,
                    "校验证据", "verification",
                    run.verification().status(),
                    run.verification().summary(),
                    0L,
                    run.verification().quality());
        }
    }

    /**
     * 仅持久化 AgentRun 的 trace 记录（非流式场景，无 SSE）。
     */
    public void recordRunTrace(ChatContext context, AgentRun run) {
        if (agentRunTraceService == null || context == null || run == null) {
            return;
        }
        for (CapabilityResult result : run.capabilityResults()) {
            agentRunTraceService.record(
                    context.session().getSessionKey(),
                    context.channel(),
                    context.userId(),
                    context.requestId(),
                    result.capabilityId(),
                    "skills".equalsIgnoreCase(result.toolset()) ? "skill" : "tool",
                    normalizeCapabilityStatus(result.status()),
                    result.summary() + " · " + truncateForEvent(result.payload(), 320),
                    result.durationMs()
            );
        }
        if (run.verification() != null) {
            agentRunTraceService.record(
                    context.session().getSessionKey(),
                    context.channel(),
                    context.userId(),
                    context.requestId(),
                    "校验证据", "verification",
                    run.verification().status(),
                    run.verification().summary(),
                    0L
            );
        }
    }

    // ── 公共工具方法 ──

    public String normalizeCapabilityStatus(String status) {
        return "failed".equalsIgnoreCase(status) ? "failed" : "success";
    }

    public String truncateForEvent(String text, int limit) {
        String value = text == null ? "" : text.trim();
        int safeLimit = Math.max(80, limit);
        return value.length() <= safeLimit ? value : value.substring(0, safeLimit) + "...";
    }

    public String renderQualityObject(AgentQualityScore quality) {
        if (quality == null) {
            return "null";
        }
        return """
                {"overallScore":%d,"routeScore":%d,"toolScore":%d,"evidenceScore":%d,"reflectionScore":%d,"answerScore":%d,"costScore":%d,"riskScore":%d,"level":"%s","reason":"%s","reasons":%s}
                """.formatted(
                quality.overallScore(),
                quality.routeScore(),
                quality.toolScore(),
                quality.evidenceScore(),
                quality.reflectionScore(),
                quality.answerScore(),
                quality.costScore(),
                quality.riskScore(),
                jsonEscape(quality.level()),
                jsonEscape(quality.reason()),
                renderJsonArray(quality.reasons())
        ).trim();
    }

    // ── 内部方法 ──

    private void sendEvent(SseEmitter emitter, String eventName, String data) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data == null ? "" : data));
        } catch (IOException e) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
                // Client disconnected or emitter was already completed.
            }
        } catch (IllegalStateException ignored) {
            // Client disconnected or emitter was already completed.
        }
    }

    private String renderTracePayload(AgentRunTraceEvent event) {
        return """
                {"requestId":"%s","stepName":"%s","type":"%s","status":"%s","detail":"%s","durationMs":%d,"timestamp":%d,"qualityScore":%s,"qualityLevel":"%s","evaluation":%s}
                """.formatted(
                jsonEscape(event.requestId()),
                jsonEscape(event.stepName()),
                jsonEscape(event.type()),
                jsonEscape(event.status()),
                jsonEscape(event.detail()),
                Math.max(0L, event.durationMs()),
                event.timestamp(),
                event.qualityScore() == null ? "null" : String.valueOf(event.qualityScore()),
                jsonEscape(event.qualityLevel()),
                StringUtils.hasText(event.evaluationJson()) ? event.evaluationJson() : "null"
        ).trim();
    }

    private String renderJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
                .map(value -> "\"" + jsonEscape(value) + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String jsonEscape(String text) {
        String value = text == null ? "" : text;
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(ch);
            }
        }
        return builder.toString();
    }

    private String normalizeResponseMode(String rawResponseMode) {
        if (!StringUtils.hasText(rawResponseMode)) {
            return "agent";
        }
        return switch (rawResponseMode.trim().toLowerCase(Locale.ROOT)) {
            case "fast", "deep", "tool" -> rawResponseMode.trim().toLowerCase(Locale.ROOT);
            default -> "agent";
        };
    }
}
