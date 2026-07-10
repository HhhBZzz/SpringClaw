package com.springclaw.service.memory.frame;

import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryFrameItem;
import com.springclaw.runtime.memory.contract.MemoryFrameLayer;
import com.springclaw.runtime.memory.contract.MemoryFrameOmission;
import com.springclaw.runtime.memory.contract.MemoryFrameSourceKind;
import com.springclaw.runtime.memory.contract.MemoryRetrievalTrace;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryScopeType;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.contract.ProjectMemoryItem;
import com.springclaw.runtime.memory.contract.ShortTermMemoryEntry;
import com.springclaw.runtime.memory.port.MemoryRecordStore;
import com.springclaw.runtime.memory.port.ProjectMemorySource;
import com.springclaw.runtime.memory.port.ShortTermMemoryStore;
import com.springclaw.domain.entity.MessageEvent;
import com.springclaw.service.event.MessageEventService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public class MemoryCoordinator {

    private static final int ACTIVE_RECORD_LIMIT = 500;
    private static final int SHORT_TERM_ENTRY_LIMIT = 40;

    private final MemoryRecordStore recordStore;
    private final Supplier<ShortTermMemoryStore> shortTermStoreSupplier;
    private final ProjectMemorySource projectMemorySource;
    private final MessageEventService messageEventService;
    private final Clock clock;
    private final int maxChars;
    private final int traceMaxWarnings;

    public MemoryCoordinator(
            MemoryRecordStore recordStore,
            ObjectProvider<ShortTermMemoryStore> shortTermStoreProvider,
            ProjectMemorySource projectMemorySource,
            Clock clock,
            int maxChars,
            int traceMaxWarnings
    ) {
        this(
                recordStore,
                shortTermStoreProvider::getIfAvailable,
                projectMemorySource,
                null,
                clock,
                maxChars,
                traceMaxWarnings
        );
    }

    public MemoryCoordinator(
            MemoryRecordStore recordStore,
            ObjectProvider<ShortTermMemoryStore> shortTermStoreProvider,
            ProjectMemorySource projectMemorySource,
            MessageEventService messageEventService,
            Clock clock,
            int maxChars,
            int traceMaxWarnings
    ) {
        this(
                recordStore,
                shortTermStoreProvider::getIfAvailable,
                projectMemorySource,
                messageEventService,
                clock,
                maxChars,
                traceMaxWarnings
        );
    }

    MemoryCoordinator(
            MemoryRecordStore recordStore,
            Supplier<ShortTermMemoryStore> shortTermStoreSupplier,
            ProjectMemorySource projectMemorySource,
            Clock clock,
            int maxChars,
            int traceMaxWarnings
    ) {
        this(
                recordStore,
                shortTermStoreSupplier,
                projectMemorySource,
                null,
                clock,
                maxChars,
                traceMaxWarnings
        );
    }

    MemoryCoordinator(
            MemoryRecordStore recordStore,
            Supplier<ShortTermMemoryStore> shortTermStoreSupplier,
            ProjectMemorySource projectMemorySource,
            MessageEventService messageEventService,
            Clock clock,
            int maxChars,
            int traceMaxWarnings
    ) {
        this.recordStore = Objects.requireNonNull(recordStore, "recordStore");
        this.shortTermStoreSupplier = Objects.requireNonNull(
                shortTermStoreSupplier,
                "shortTermStoreSupplier"
        );
        this.projectMemorySource = Objects.requireNonNull(
                projectMemorySource,
                "projectMemorySource"
        );
        this.messageEventService = messageEventService;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxChars = Math.max(1_000, maxChars);
        this.traceMaxWarnings = Math.max(1, traceMaxWarnings);
    }

    public MemoryFrameResult retrieve(MemoryFrameRequest request) {
        Objects.requireNonNull(request, "request");
        MemoryScope scope = request.scope();
        Instant capturedAt = clock.instant();
        List<MemoryFrameOmission> omissions = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        SourceTally sourceCounts = new SourceTally();

        List<MemoryFrameItem> shortTerm = shortTermItems(
                request,
                scope,
                omissions,
                warnings,
                sourceCounts
        );
        List<MemoryFrameItem> episodic = new ArrayList<>();
        List<MemoryFrameItem> semanticCandidates = new ArrayList<>();
        List<MemoryFrameItem> procedural = new ArrayList<>();
        recordItems(scope, sourceCounts).forEach(item -> {
            switch (item.layer()) {
                case EPISODIC -> episodic.add(item);
                case SEMANTIC_FACT -> semanticCandidates.add(item);
                case PROCEDURAL_RULE -> procedural.add(item);
                default -> omissions.add(new MemoryFrameOmission(
                        MemoryFrameOmission.Category.UNSUPPORTED_TYPE,
                        item.layer(),
                        item.sourceId(),
                        "unsupported durable memory layer"
                ));
            }
        });
        List<MemoryFrameItem> semantic = applyRelevanceFilter(
                semanticCandidates,
                request.question(),
                omissions
        );
        List<MemoryFrameItem> project = projectItems(scope, omissions, sourceCounts);

        DedupedItems deduped = dedupe(
                shortTerm,
                episodic,
                semantic,
                procedural,
                project,
                omissions
        );
        DedupedItems budgeted = new DedupedItems(
                applyLayerBudget(deduped.shortTerm(), MemoryFrameLayer.SHORT_TERM, omissions),
                applyLayerBudget(deduped.episodic(), MemoryFrameLayer.EPISODIC, omissions),
                applyLayerBudget(deduped.semantic(), MemoryFrameLayer.SEMANTIC_FACT, omissions),
                applyLayerBudget(deduped.procedural(), MemoryFrameLayer.PROCEDURAL_RULE, omissions),
                applyLayerBudget(deduped.project(), MemoryFrameLayer.PROJECT, omissions)
        );

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("schema", "springclaw.memory-frame.v1");
        summary.put("maxChars", String.valueOf(maxChars));
        summary.put("questionHash", sha256(request.question()));

        MemoryFrame placeholder = new MemoryFrame(
                request.runId(),
                scope,
                List.of(),
                budgeted.shortTerm(),
                budgeted.episodic(),
                budgeted.semantic(),
                budgeted.procedural(),
                budgeted.project(),
                summary,
                omissions,
                capturedAt,
                "pending"
        );
        String frameHash = MemoryFrameHasher.hash(placeholder);
        MemoryFrame frame = new MemoryFrame(
                request.runId(),
                scope,
                placeholder.workingMemoryRefs(),
                placeholder.shortTermTurns(),
                placeholder.episodicItems(),
                placeholder.semanticFacts(),
                placeholder.proceduralRules(),
                placeholder.projectItems(),
                placeholder.sourceSummary(),
                placeholder.omissions(),
                capturedAt,
                frameHash
        );
        MemoryRetrievalTrace trace = new MemoryRetrievalTrace(
                request.runId(),
                scope,
                frameHash,
                sourceCounts.asMap(),
                includedCounts(frame),
                omissionCounts(omissions),
                warnings.stream().limit(traceMaxWarnings).toList(),
                capturedAt
        );
        return new MemoryFrameResult(frame, trace);
    }

    private List<MemoryFrameItem> shortTermItems(
            MemoryFrameRequest request,
            MemoryScope scope,
            List<MemoryFrameOmission> omissions,
            List<String> warnings,
            SourceTally sourceCounts
    ) {
        ShortTermMemoryStore store = shortTermStoreSupplier.get();
        if (store == null) {
            omissions.add(new MemoryFrameOmission(
                    MemoryFrameOmission.Category.SOURCE_UNAVAILABLE,
                    MemoryFrameLayer.SHORT_TERM,
                    "short-term",
                    "short-term store unavailable"
            ));
            warnings.add("short-term store unavailable");
            return List.of();
        }
        List<ShortTermMemoryEntry> entries = store.readRecent(
                scope,
                SHORT_TERM_ENTRY_LIMIT
        );
        sourceCounts.add("shortTerm", entries.size());
        if (entries.isEmpty()) {
            List<ShortTermMemoryEntry> persistedEntries = persistedShortTermEntries(
                    scope,
                    SHORT_TERM_ENTRY_LIMIT,
                    warnings
            );
            if (!persistedEntries.isEmpty()) {
                sourceCounts.add("persistedShortTerm", persistedEntries.size());
                return persistedEntries.stream()
                        .filter(entry -> isAllowedShortTermRole(entry.role()))
                        .map(entry -> fromShortTerm(scope, entry))
                        .toList();
            }
        }
        return entries.stream()
                .filter(entry -> isAllowedShortTermRole(entry.role()))
                .map(entry -> fromShortTerm(scope, entry))
                .toList();
    }

    private List<ShortTermMemoryEntry> persistedShortTermEntries(
            MemoryScope scope,
            int limit,
            List<String> warnings
    ) {
        if (messageEventService == null) {
            return List.of();
        }
        String userFilter = scope.scopeType() == MemoryScopeType.PERSONAL_SESSION
                ? scope.authorizationPrincipal()
                : null;
        try {
            List<MessageEvent> events = messageEventService.listSessionEvents(
                    scope.sessionKey(),
                    userFilter,
                    null,
                    "CHAT",
                    limit,
                    true
            );
            if (events == null || events.isEmpty()) {
                return List.of();
            }
            List<ShortTermMemoryEntry> entries = new ArrayList<>();
            for (MessageEvent event : events) {
                ShortTermMemoryEntry entry = toShortTermEntry(scope, event);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            return List.copyOf(entries);
        } catch (RuntimeException ex) {
            warnings.add("persisted short-term fallback unavailable: " + ex.getMessage());
            return List.of();
        }
    }

    private static ShortTermMemoryEntry toShortTermEntry(
            MemoryScope scope,
            MessageEvent event
    ) {
        if (event == null
                || event.getId() == null
                || event.getId() <= 0
                || !"CHAT".equalsIgnoreCase(event.getEventType())
                || !isAllowedShortTermRole(event.getRole())
                || !StringUtils.hasText(event.getEventKey())
                || !StringUtils.hasText(event.getRequestId())
                || !StringUtils.hasText(event.getUserId())
                || !StringUtils.hasText(event.getContent())) {
            return null;
        }
        if (scope.scopeType() == MemoryScopeType.PERSONAL_SESSION
                && !scope.authorizationPrincipal().equals(event.getUserId())) {
            return null;
        }
        Instant occurredAt = event.getCreateTime() == null
                ? Instant.EPOCH
                : event.getCreateTime().atOffset(ZoneOffset.UTC).toInstant();
        return new ShortTermMemoryEntry(
                event.getId(),
                event.getEventKey(),
                event.getRequestId(),
                event.getRole(),
                event.getUserId(),
                event.getContent(),
                occurredAt
        );
    }

    private List<MemoryFrameItem> recordItems(
            MemoryScope scope,
            SourceTally sourceCounts
    ) {
        List<MemoryRecordVersion> records = new ArrayList<>(recordStore.findActiveByScope(
                scope,
                Set.of(MemoryType.EPISODIC, MemoryType.SEMANTIC, MemoryType.PROCEDURAL),
                ACTIVE_RECORD_LIMIT
        ));
        if (scope.crossSessionUserMemoryAllowed()
                && scope.scopeType() != MemoryScopeType.USER) {
            MemoryScope userScope = MemoryScope.user(
                    scope.channel(),
                    scope.sessionKey(),
                    scope.requestingUserId()
            );
            records.addAll(recordStore.findActiveByScope(
                    userScope,
                    Set.of(MemoryType.EPISODIC, MemoryType.SEMANTIC, MemoryType.PROCEDURAL),
                    ACTIVE_RECORD_LIMIT
            ));
        }
        sourceCounts.add("memoryRecord", records.size());
        return records.stream()
                .map(this::fromRecord)
                .toList();
    }

    private List<MemoryFrameItem> projectItems(
            MemoryScope scope,
            List<MemoryFrameOmission> omissions,
            SourceTally sourceCounts
    ) {
        List<ProjectMemoryItem> projectItems = projectMemorySource.read(scope);
        sourceCounts.add("project", projectItems.size());
        List<MemoryFrameItem> included = new ArrayList<>();
        for (ProjectMemoryItem project : projectItems) {
            if (project.reviewStatus() == ProjectMemoryItem.ReviewStatus.APPROVED
                    || project.reviewStatus() == ProjectMemoryItem.ReviewStatus.ACTIVE) {
                included.add(fromProject(project));
            } else {
                omissions.add(new MemoryFrameOmission(
                        MemoryFrameOmission.Category.AUTHORIZATION_SCOPE_MISMATCH,
                        MemoryFrameLayer.PROJECT,
                        project.sourcePath(),
                        "project memory status not approved for runtime context: "
                                + project.reviewStatus()
                ));
            }
        }
        return List.copyOf(included);
    }

    private DedupedItems dedupe(
            List<MemoryFrameItem> shortTerm,
            List<MemoryFrameItem> episodic,
            List<MemoryFrameItem> semantic,
            List<MemoryFrameItem> procedural,
            List<MemoryFrameItem> project,
            List<MemoryFrameOmission> omissions
    ) {
        Set<String> seen = new HashSet<>();
        List<MemoryFrameItem> dedupedShortTerm = dedupeLayer(shortTerm, seen, omissions);
        List<MemoryFrameItem> dedupedEpisodic = dedupeLayer(episodic, seen, omissions);
        List<MemoryFrameItem> dedupedSemantic = dedupeLayer(semantic, seen, omissions);
        List<MemoryFrameItem> dedupedProcedural = dedupeLayer(procedural, seen, omissions);
        List<MemoryFrameItem> dedupedProject = dedupeLayer(project, seen, omissions);
        return new DedupedItems(
                dedupedShortTerm,
                dedupedEpisodic,
                dedupedSemantic,
                dedupedProcedural,
                dedupedProject
        );
    }

    private List<MemoryFrameItem> dedupeLayer(
            List<MemoryFrameItem> items,
            Set<String> seenContentHashes,
            List<MemoryFrameOmission> omissions
    ) {
        List<MemoryFrameItem> kept = new ArrayList<>();
        for (MemoryFrameItem item : items) {
            if (seenContentHashes.add(item.contentHash())) {
                kept.add(item);
            } else {
                omissions.add(new MemoryFrameOmission(
                        MemoryFrameOmission.Category.DUPLICATE_CONTENT,
                        item.layer(),
                        item.sourceId(),
                        "duplicate content hash"
                ));
            }
        }
        return List.copyOf(kept);
    }

    private List<MemoryFrameItem> applyRelevanceFilter(
            List<MemoryFrameItem> items,
            String question,
            List<MemoryFrameOmission> omissions
    ) {
        if (items.size() <= 1) {
            return items;
        }
        Set<String> queryConcepts = relevanceConcepts(question);
        if (queryConcepts.isEmpty()) {
            return items;
        }
        List<ScoredItem> scored = items.stream()
                .map(item -> new ScoredItem(
                        item,
                        relevanceScore(item, queryConcepts)
                ))
                .toList();
        boolean anyRelevant = scored.stream()
                .anyMatch(item -> item.score() > 0);
        if (!anyRelevant) {
            return items;
        }
        List<MemoryFrameItem> kept = new ArrayList<>();
        for (ScoredItem scoredItem : scored) {
            if (scoredItem.score() > 0) {
                kept.add(scoredItem.item());
            } else {
                omissions.add(new MemoryFrameOmission(
                        MemoryFrameOmission.Category.LOW_SCORE,
                        scoredItem.item().layer(),
                        scoredItem.item().sourceId(),
                        "irrelevant to current question"
                ));
            }
        }
        return List.copyOf(kept);
    }

    private List<MemoryFrameItem> applyLayerBudget(
            List<MemoryFrameItem> items,
            MemoryFrameLayer layer,
            List<MemoryFrameOmission> omissions
    ) {
        if (items.isEmpty()) {
            return items;
        }
        int limit = MemoryFrameBudget.of(maxChars).limitFor(layer);
        if (limit <= 0) {
            items.forEach(item -> omissions.add(new MemoryFrameOmission(
                    MemoryFrameOmission.Category.BUDGET_TRUNCATED,
                    item.layer(),
                    item.sourceId(),
                    "layer budget is zero"
            )));
            return List.of();
        }
        List<MemoryFrameItem> kept = new ArrayList<>();
        int used = 0;
        for (MemoryFrameItem item : items) {
            int chars = item.content().length();
            if (used + chars <= limit) {
                kept.add(item);
                used += chars;
            } else {
                omissions.add(new MemoryFrameOmission(
                        MemoryFrameOmission.Category.BUDGET_TRUNCATED,
                        item.layer(),
                        item.sourceId(),
                        "layer budget exceeded: " + layer
                ));
            }
        }
        return List.copyOf(kept);
    }

    private static int relevanceScore(
            MemoryFrameItem item,
            Set<String> queryConcepts
    ) {
        Set<String> itemConcepts = relevanceConcepts(item.content());
        itemConcepts.addAll(relevanceConcepts(String.join(" ", item.evidenceRefs())));
        int score = 0;
        for (String concept : queryConcepts) {
            if (itemConcepts.contains(concept)) {
                score++;
            }
        }
        return score;
    }

    private static Set<String> relevanceConcepts(String value) {
        if (value == null || value.isBlank()) {
            return new LinkedHashSet<>();
        }
        String normalized = value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        LinkedHashSet<String> concepts = new LinkedHashSet<>();
        for (String rawToken : normalized.split("\\s+")) {
            String token = rawToken.trim();
            if (token.isBlank() || isStopWord(token)) {
                continue;
            }
            switch (token) {
                case "short", "brief", "concise", "succinct" -> concepts.add("short");
                case "summary", "summaries", "summarize", "progress", "status",
                        "update", "updates" -> concepts.add("summary");
                case "chinese", "中文", "汉语" -> concepts.add("chinese");
                case "language", "english" -> concepts.add("language");
                case "restaurant", "restaurants", "italian", "food" -> concepts.add("food");
                default -> {
                    if (token.length() >= 4 && token.chars().allMatch(Character::isLetterOrDigit)) {
                        concepts.add(token);
                    }
                }
            }
        }
        if (normalized.contains("中文") || normalized.contains("汉语")) {
            concepts.add("chinese");
        }
        if (normalized.contains("简短") || normalized.contains("短")) {
            concepts.add("short");
        }
        if (normalized.contains("同步")
                || normalized.contains("总结")
                || normalized.contains("摘要")
                || normalized.contains("进展")) {
            concepts.add("summary");
        }
        return concepts;
    }

    private static boolean isStopWord(String token) {
        return Set.of(
                "a", "an", "the", "and", "or", "to", "for", "of", "in",
                "on", "with", "about", "how", "what", "which", "should",
                "you", "your", "user", "alice", "bob", "give", "current",
                "question", "content", "version", "memory", "prefers",
                "prefer", "preference", "likes"
        ).contains(token);
    }

    private static boolean isAllowedShortTermRole(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase();
        return "USER".equals(normalized) || "ASSISTANT".equals(normalized);
    }

    private static MemoryFrameItem fromShortTerm(
            MemoryScope scope,
            ShortTermMemoryEntry entry
    ) {
        return new MemoryFrameItem(
                entry.eventKey(),
                MemoryFrameSourceKind.MESSAGE_EVENT,
                MemoryFrameLayer.SHORT_TERM,
                "",
                "",
                null,
                scope.scopeType(),
                scope.scopeId(),
                entry.content(),
                sha256(entry.content()),
                List.of(entry.eventKey()),
                0.5,
                1.0,
                scoreByEventId(entry.eventId()),
                1,
                entry.occurredAt()
        );
    }

    private MemoryFrameItem fromRecord(MemoryRecordVersion record) {
        return new MemoryFrameItem(
                record.memoryVersionId(),
                MemoryFrameSourceKind.MEMORY_RECORD,
                layerFor(record.memoryType()),
                record.logicalMemoryId(),
                record.memoryVersionId(),
                record.memoryType(),
                record.scopeType(),
                record.scopeId(),
                record.content(),
                record.contentHash(),
                record.evidenceRefs(),
                record.importance(),
                record.confidence(),
                score(record.importance(), record.confidence()),
                record.version(),
                record.updatedAt()
        );
    }

    private static MemoryFrameItem fromProject(ProjectMemoryItem item) {
        return new MemoryFrameItem(
                item.sourcePath(),
                MemoryFrameSourceKind.PROJECT_MARKDOWN,
                MemoryFrameLayer.PROJECT,
                "",
                "",
                null,
                null,
                "",
                item.content(),
                item.contentHash(),
                List.of(item.sourcePath()),
                item.reviewStatus() == ProjectMemoryItem.ReviewStatus.APPROVED ? 0.9 : 0.7,
                1.0,
                item.reviewStatus() == ProjectMemoryItem.ReviewStatus.APPROVED ? 0.9 : 0.7,
                1,
                item.updatedAt()
        );
    }

    private static MemoryFrameLayer layerFor(MemoryType type) {
        return switch (type) {
            case EPISODIC -> MemoryFrameLayer.EPISODIC;
            case SEMANTIC -> MemoryFrameLayer.SEMANTIC_FACT;
            case PROCEDURAL -> MemoryFrameLayer.PROCEDURAL_RULE;
        };
    }

    private static double score(double importance, double confidence) {
        return Math.max(0.0d, Math.min(1.0d, (importance + confidence) / 2.0d));
    }

    private static double scoreByEventId(long eventId) {
        if (eventId <= 0) {
            return 0.5d;
        }
        return Math.max(0.1d, Math.min(1.0d, 0.5d + Math.min(eventId, 1000L) / 2000.0d));
    }

    private static Map<String, Integer> includedCounts(MemoryFrame frame) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("shortTerm", frame.shortTermTurns().size());
        counts.put("episodic", frame.episodicItems().size());
        counts.put("semantic", frame.semanticFacts().size());
        counts.put("procedural", frame.proceduralRules().size());
        counts.put("project", frame.projectItems().size());
        return counts;
    }

    private static Map<MemoryFrameOmission.Category, Integer> omissionCounts(
            List<MemoryFrameOmission> omissions
    ) {
        EnumMap<MemoryFrameOmission.Category, Integer> counts =
                new EnumMap<>(MemoryFrameOmission.Category.class);
        for (MemoryFrameOmission omission : omissions) {
            counts.merge(omission.category(), 1, Integer::sum);
        }
        return Map.copyOf(counts);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record DedupedItems(
            List<MemoryFrameItem> shortTerm,
            List<MemoryFrameItem> episodic,
            List<MemoryFrameItem> semantic,
            List<MemoryFrameItem> procedural,
            List<MemoryFrameItem> project
    ) {
    }

    private record ScoredItem(MemoryFrameItem item, int score) {
    }

    private static final class SourceTally {
        private final Map<String, Integer> counts = new LinkedHashMap<>();

        void add(String source, int count) {
            counts.merge(source, Math.max(0, count), Integer::sum);
        }

        Map<String, Integer> asMap() {
            return Map.copyOf(counts);
        }
    }
}
