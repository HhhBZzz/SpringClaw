package com.springclaw.service.memory.evaluation;

import com.springclaw.runtime.memory.contract.MemoryFrame;
import com.springclaw.runtime.memory.contract.MemoryFrameItem;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

@Component
public class MemoryUsageTraceEvaluator {

    public MemoryUsageTrace evaluate(MemoryFrame frame, String answer) {
        Objects.requireNonNull(frame, "frame");
        List<MemoryFrameItem> injectedItems = injectedItems(frame);
        if (injectedItems.isEmpty()) {
            return MemoryUsageTrace.none(false);
        }

        String normalizedAnswer = normalize(answer);
        Set<String> answerConcepts = concepts(answer);
        for (MemoryFrameItem item : injectedItems) {
            String normalizedContent = normalize(item.content());
            if (!normalizedContent.isBlank()
                    && normalizedAnswer.contains(normalizedContent)) {
                return trace(
                        MemoryUsageTrace.ReferenceKind.EXPLICIT,
                        item.sourceId()
                );
            }
        }
        for (MemoryFrameItem item : injectedItems) {
            Set<String> memoryConcepts = concepts(item.content());
            memoryConcepts.retainAll(answerConcepts);
            if (memoryConcepts.size() >= 2) {
                return trace(
                        MemoryUsageTrace.ReferenceKind.PARAPHRASE,
                        item.sourceId()
                );
            }
        }
        return MemoryUsageTrace.none(true);
    }

    private static MemoryUsageTrace trace(
            MemoryUsageTrace.ReferenceKind kind,
            String sourceId
    ) {
        return new MemoryUsageTrace(
                true,
                true,
                kind,
                "deterministic",
                List.of(sourceId)
        );
    }

    private static List<MemoryFrameItem> injectedItems(MemoryFrame frame) {
        return java.util.stream.Stream.of(
                        frame.workingMemoryRefs(),
                        frame.shortTermTurns(),
                        frame.episodicItems(),
                        frame.semanticFacts(),
                        frame.proceduralRules(),
                        frame.projectItems()
                )
                .flatMap(List::stream)
                .toList();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[\\p{Punct}\\p{IsPunctuation}]+", " ");
        return normalized.replaceAll("\\s+", " ").trim();
    }

    private static Set<String> concepts(String value) {
        String normalized = normalize(value);
        LinkedHashSet<String> concepts = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            addTokenConcept(concepts, token);
        }
        addPhraseConcepts(concepts, normalized);
        return concepts;
    }

    private static void addTokenConcept(Set<String> concepts, String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        switch (token) {
            case "short", "brief", "concise", "succinct" -> concepts.add("short");
            case "chinese", "中文", "汉语" -> concepts.add("chinese");
            case "summary", "summaries", "summarize", "progress", "update", "updates",
                    "status" -> concepts.add("summary");
            case "preference", "prefers", "prefer", "preferred" -> concepts.add("preference");
            default -> {
                if (token.length() >= 4 && token.chars().allMatch(Character::isLetterOrDigit)) {
                    concepts.add(token);
                }
            }
        }
    }

    private static void addPhraseConcepts(Set<String> concepts, String normalized) {
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
    }
}
