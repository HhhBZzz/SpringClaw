package com.springclaw.service.memory.extraction;

import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.MemoryManagementService;
import com.springclaw.service.memory.MemoryWriteCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Service
public class TerminalMemoryExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TerminalMemoryExtractionService.class);

    static final String SEMANTIC_EXTRACTION_SCHEMA = "springclaw.semantic-memory-extraction.v1";
    static final String SEMANTIC_JUDGE_SCHEMA = "springclaw.semantic-memory-judge.v1";
    static final String TERMINAL_REFLECTION_SCHEMA = "springclaw.terminal-reflection.v1";
    static final String SEMANTIC_POLICY_VERSION = "semantic-extraction-v1";
    static final String REFLECTION_POLICY_VERSION = "terminal-reflection-v1";
    static final String MESSAGE_EVENT_SOURCE_KIND = "MESSAGE_EVENT";
    static final String RUN_REFLECTION_SOURCE_KIND = "RUN_REFLECTION";

    private final MessageEventService messageEventService;
    private final MemoryManagementService memoryManagementService;
    private final SemanticMemoryExtractor extractor;
    private final SemanticMemoryJudge judge;
    private final TerminalReflectionGenerator reflectionGenerator;
    private final MemoryExtractionPolicy policy;

    @Autowired
    public TerminalMemoryExtractionService(
            MessageEventService messageEventService,
            MemoryManagementService memoryManagementService,
            SemanticMemoryExtractor extractor,
            SemanticMemoryJudge judge,
            TerminalReflectionGenerator reflectionGenerator,
            ObjectProvider<Clock> clockProvider,
            @Value("${springclaw.memory.semantic-extraction.max-source-events:12}")
            int maxSourceEvents,
            @Value("${springclaw.memory.semantic-extraction.auto-active-importance-threshold:0.75}")
            double autoActiveImportanceThreshold,
            @Value("${springclaw.memory.semantic-extraction.auto-active-confidence-threshold:0.82}")
            double autoActiveConfidenceThreshold
    ) {
        this(
                messageEventService,
                memoryManagementService,
                extractor,
                judge,
                reflectionGenerator,
                new MemoryExtractionPolicy(
                        maxSourceEvents,
                        autoActiveImportanceThreshold,
                        autoActiveConfidenceThreshold,
                        clockProvider.getIfAvailable(Clock::systemUTC)
                )
        );
    }

    TerminalMemoryExtractionService(
            MessageEventService messageEventService,
            MemoryManagementService memoryManagementService,
            SemanticMemoryExtractor extractor,
            SemanticMemoryJudge judge,
            TerminalReflectionGenerator reflectionGenerator,
            MemoryExtractionPolicy policy
    ) {
        this.messageEventService = Objects.requireNonNull(messageEventService, "messageEventService");
        this.memoryManagementService = Objects.requireNonNull(memoryManagementService, "memoryManagementService");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.judge = Objects.requireNonNull(judge, "judge");
        this.reflectionGenerator = Objects.requireNonNull(reflectionGenerator, "reflectionGenerator");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public TerminalMemoryExtractionResult extractTerminalRun(String runId, String userId) {
        if (!StringUtils.hasText(runId) || !StringUtils.hasText(userId)) {
            return TerminalMemoryExtractionResult.empty();
        }
        TerminalMemoryExtractionContext context = context(runId.trim(), userId.trim());
        if (context.events().isEmpty()) {
            return TerminalMemoryExtractionResult.empty();
        }
        int semanticWritten = extractSemantic(context);
        int reflectionWritten = reflectTerminalRun(context);
        return new TerminalMemoryExtractionResult(semanticWritten, reflectionWritten);
    }

    private TerminalMemoryExtractionContext context(String runId, String userId) {
        List<MessageEvent> events = messageEventService.listRequestEvents(
                runId,
                userId,
                null,
                null,
                policy.maxSourceEvents(),
                true
        );
        List<MessageEvent> sourceEvents = events.stream()
                .filter(this::allowedSourceEvent)
                .toList();
        if (sourceEvents.isEmpty()) {
            return new TerminalMemoryExtractionContext(runId, "", "", userId, List.of());
        }
        MessageEvent first = sourceEvents.get(0);
        List<MemorySourceEvent> source = sourceEvents.stream()
                .map(event -> new MemorySourceEvent(
                        event.getEventKey(),
                        event.getRole(),
                        event.getEventType(),
                        event.getContent()
                ))
                .toList();
        return new TerminalMemoryExtractionContext(
                runId,
                first.getSessionKey(),
                first.getChannel(),
                userId,
                source
        );
    }

    private int extractSemantic(TerminalMemoryExtractionContext context) {
        SemanticMemoryExtractionResult result;
        try {
            result = extractor.extract(context);
        } catch (Exception ex) {
            log.warn("semantic memory extraction failed, runId={}, reason={}",
                    context.runId(), ex.getMessage());
            return 0;
        }
        if (result == null || !SEMANTIC_EXTRACTION_SCHEMA.equals(result.schema())) {
            return 0;
        }
        int written = 0;
        int ordinal = 0;
        for (SemanticMemoryCandidate candidate : result.candidates()) {
            if (writeCandidate(context, candidate, ordinal)) {
                written++;
            }
            ordinal++;
        }
        return written;
    }

    private boolean writeCandidate(
            TerminalMemoryExtractionContext context,
            SemanticMemoryCandidate candidate,
            int ordinal
    ) {
        if (!candidateUsable(context, candidate)) {
            return false;
        }
        SemanticMemoryJudgeVerdict verdict;
        try {
            verdict = judge.judge(candidate, context);
        } catch (Exception ex) {
            log.warn("semantic memory judge failed, runId={}, reason={}",
                    context.runId(), ex.getMessage());
            return false;
        }
        if (!judgeAllowsWrite(verdict)) {
            return false;
        }
        MemoryStatus status = shouldAutoActivate(candidate, verdict)
                ? MemoryStatus.ACTIVE
                : MemoryStatus.CANDIDATE;
        List<String> eventKeys = normalizedEventKeys(candidate.sourceEventKeys());
        String sourceIdentity = sourceIdentity(context.runId(), eventKeys, ordinal);
        MemoryWriteCommand command = new MemoryWriteCommand(
                sha256("semantic:" + sourceIdentity),
                MemoryType.SEMANTIC,
                MemoryScope.user(context.channel(), context.sessionKey(), context.userId()),
                candidate.content(),
                candidate.reason(),
                context.runId(),
                eventKeys,
                evidenceRefs(context.runId(), eventKeys),
                semanticTags(candidate),
                candidate.importance(),
                candidate.confidence(),
                status,
                policy.clock().instant(),
                null,
                MESSAGE_EVENT_SOURCE_KIND,
                sourceIdentity,
                SEMANTIC_POLICY_VERSION
        );
        memoryManagementService.create(command);
        return true;
    }

    private int reflectTerminalRun(TerminalMemoryExtractionContext context) {
        TerminalReflectionResult reflection;
        try {
            reflection = reflectionGenerator.reflect(context);
        } catch (Exception ex) {
            log.warn("terminal reflection failed, runId={}, reason={}",
                    context.runId(), ex.getMessage());
            return 0;
        }
        if (!reflectionUsable(context, reflection)) {
            return 0;
        }
        MemoryWriteCommand command = new MemoryWriteCommand(
                sha256("reflection:" + context.runId()),
                MemoryType.EPISODIC,
                MemoryScope.user(context.channel(), context.sessionKey(), context.userId()),
                reflection.lesson(),
                reflection.applicability(),
                context.runId(),
                sourceEventKeysFromEvidence(reflection.evidenceRefs()),
                reflection.evidenceRefs(),
                reflectionTags(reflection),
                0.7,
                reflection.confidence(),
                MemoryStatus.CANDIDATE,
                policy.clock().instant(),
                null,
                RUN_REFLECTION_SOURCE_KIND,
                context.runId(),
                REFLECTION_POLICY_VERSION
        );
        memoryManagementService.create(command);
        return 1;
    }

    private boolean allowedSourceEvent(MessageEvent event) {
        if (event == null || !StringUtils.hasText(event.getEventKey())
                || !StringUtils.hasText(event.getContent())) {
            return false;
        }
        String role = upper(event.getRole());
        String type = upper(event.getEventType());
        return ("USER".equals(role) || "ASSISTANT".equals(role))
                && ("CHAT".equals(type) || "TASK".equals(type));
    }

    private boolean candidateUsable(
            TerminalMemoryExtractionContext context,
            SemanticMemoryCandidate candidate
    ) {
        if (candidate == null || candidate.hypothetical()
                || !StringUtils.hasText(candidate.content())
                || !StringUtils.hasText(candidate.kind())
                || !context.runId().equals(candidate.sourceRunId())
                || !score(candidate.importance())
                || !score(candidate.confidence())) {
            return false;
        }
        List<String> eventKeys = normalizedEventKeys(candidate.sourceEventKeys());
        return !eventKeys.isEmpty() && knownEventKeys(context).containsAll(eventKeys);
    }

    private boolean judgeAllowsWrite(SemanticMemoryJudgeVerdict verdict) {
        if (verdict == null || !SEMANTIC_JUDGE_SCHEMA.equals(verdict.schema())) {
            return false;
        }
        String normalized = upper(verdict.verdict());
        return !"REJECT".equals(normalized)
                && verdict.evidenceGrounded()
                && !verdict.hypothetical()
                && !verdict.sensitive();
    }

    private boolean shouldAutoActivate(
            SemanticMemoryCandidate candidate,
            SemanticMemoryJudgeVerdict verdict
    ) {
        return "ACCEPT".equals(upper(verdict.verdict()))
                && candidate.importance() >= policy.autoActiveImportanceThreshold()
                && candidate.confidence() >= policy.autoActiveConfidenceThreshold()
                && !candidate.sourceEventKeys().isEmpty();
    }

    private boolean reflectionUsable(
            TerminalMemoryExtractionContext context,
            TerminalReflectionResult reflection
    ) {
        if (reflection == null
                || !TERMINAL_REFLECTION_SCHEMA.equals(reflection.schema())
                || !StringUtils.hasText(reflection.lesson())
                || !score(reflection.confidence())
                || reflection.evidenceRefs().isEmpty()) {
            return false;
        }
        Set<String> allowed = new HashSet<>(evidenceRefs(
                context.runId(),
                context.events().stream().map(MemorySourceEvent::eventKey).toList()
        ));
        return allowed.containsAll(reflection.evidenceRefs());
    }

    private static List<String> normalizedEventKeys(List<String> eventKeys) {
        if (eventKeys == null) {
            return List.of();
        }
        return eventKeys.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private static Set<String> knownEventKeys(TerminalMemoryExtractionContext context) {
        Set<String> keys = new HashSet<>();
        for (MemorySourceEvent event : context.events()) {
            if (StringUtils.hasText(event.eventKey())) {
                keys.add(event.eventKey().trim());
            }
        }
        return keys;
    }

    private static List<String> evidenceRefs(String runId, List<String> eventKeys) {
        List<String> refs = new ArrayList<>();
        refs.add("run:" + runId);
        eventKeys.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .map(key -> "event:" + key)
                .forEach(refs::add);
        return List.copyOf(refs);
    }

    private static List<String> sourceEventKeysFromEvidence(List<String> evidenceRefs) {
        if (evidenceRefs == null) {
            return List.of();
        }
        return evidenceRefs.stream()
                .filter(ref -> ref != null && ref.startsWith("event:"))
                .map(ref -> ref.substring("event:".length()))
                .filter(StringUtils::hasText)
                .sorted()
                .toList();
    }

    private static List<String> semanticTags(SemanticMemoryCandidate candidate) {
        List<String> tags = new ArrayList<>();
        tags.add("semantic-extraction");
        tags.add(upper(candidate.kind()));
        if (StringUtils.hasText(candidate.subject())) {
            tags.add(candidate.subject().trim().toLowerCase(Locale.ROOT));
        }
        return tags.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private static List<String> reflectionTags(TerminalReflectionResult reflection) {
        List<String> tags = new ArrayList<>();
        tags.add("terminal-reflection");
        if (StringUtils.hasText(reflection.outcome())) {
            tags.add(upper(reflection.outcome()));
        }
        if (StringUtils.hasText(reflection.failureMode())) {
            tags.add("failure-mode");
        }
        return tags.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private static String sourceIdentity(String runId, List<String> eventKeys, int ordinal) {
        String eventHash = sha256(String.join("\u0000", eventKeys.stream().sorted().toList()));
        return runId + ":" + eventHash + ":" + ordinal;
    }

    private static boolean score(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private static String upper(String value) {
        return StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT)
                : "";
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
