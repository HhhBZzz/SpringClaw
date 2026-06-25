package com.springclaw.service.memory.frame;

import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryFrameItem;
import com.springclaw.runtime.memory.contract.MemoryFrameLayer;
import com.springclaw.runtime.memory.contract.MemoryFrameOmission;
import com.springclaw.runtime.memory.contract.MemoryFrameSourceKind;
import com.springclaw.runtime.memory.contract.MemoryRetrievalTrace;
import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.contract.ProjectMemoryItem;
import com.springclaw.runtime.memory.contract.ShortTermMemoryEntry;
import com.springclaw.runtime.memory.port.MemoryRecordStore;
import com.springclaw.runtime.memory.port.ProjectMemorySource;
import com.springclaw.runtime.memory.port.ShortTermMemoryStore;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
        this.recordStore = Objects.requireNonNull(recordStore, "recordStore");
        this.shortTermStoreSupplier = Objects.requireNonNull(
                shortTermStoreSupplier,
                "shortTermStoreSupplier"
        );
        this.projectMemorySource = Objects.requireNonNull(
                projectMemorySource,
                "projectMemorySource"
        );
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
        List<MemoryFrameItem> semantic = new ArrayList<>();
        List<MemoryFrameItem> procedural = new ArrayList<>();
        recordItems(scope, sourceCounts).forEach(item -> {
            switch (item.layer()) {
                case EPISODIC -> episodic.add(item);
                case SEMANTIC_FACT -> semantic.add(item);
                case PROCEDURAL_RULE -> procedural.add(item);
                default -> omissions.add(new MemoryFrameOmission(
                        MemoryFrameOmission.Category.UNSUPPORTED_TYPE,
                        item.layer(),
                        item.sourceId(),
                        "unsupported durable memory layer"
                ));
            }
        });
        List<MemoryFrameItem> project = projectItems(scope, omissions, sourceCounts);

        DedupedItems deduped = dedupe(
                shortTerm,
                episodic,
                semantic,
                procedural,
                project,
                omissions
        );

        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("schema", "springclaw.memory-frame.v1");
        summary.put("maxChars", String.valueOf(maxChars));
        summary.put("questionHash", sha256(request.question()));

        MemoryFrame placeholder = new MemoryFrame(
                request.runId(),
                scope,
                List.of(),
                deduped.shortTerm(),
                deduped.episodic(),
                deduped.semantic(),
                deduped.procedural(),
                deduped.project(),
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
        return entries.stream()
                .filter(entry -> isAllowedShortTermRole(entry.role()))
                .map(entry -> fromShortTerm(scope, entry))
                .toList();
    }

    private List<MemoryFrameItem> recordItems(
            MemoryScope scope,
            SourceTally sourceCounts
    ) {
        List<MemoryRecordVersion> records = recordStore.findActiveByScope(
                scope,
                Set.of(MemoryType.EPISODIC, MemoryType.SEMANTIC, MemoryType.PROCEDURAL),
                ACTIVE_RECORD_LIMIT
        );
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
