package com.springclaw.service.memory.consolidation;

import com.springclaw.runtime.memory.contract.MemoryRecordVersion;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryScopeType;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.service.memory.MemoryWriteCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryConsolidationProposerTest {

    private static final Instant T0 = Instant.parse("2026-06-29T00:00:00Z");

    @Test
    void proposesSemanticCandidateFromRepeatedPreferenceEpisodes() {
        MemoryConsolidationProposal proposal = new MemoryConsolidationProposer().propose(
                List.of(
                        episodic("episode-1", "User asked for concise Chinese status updates.", List.of("preference", "summary")),
                        episodic("episode-2", "User again requested short Chinese progress summaries.", List.of("preference", "summary")),
                        episodic("episode-3", "User rejected long English progress reports.", List.of("preference", "summary"))
                )
        ).orElseThrow();

        MemoryWriteCommand command = proposal.command();
        assertThat(command.memoryType()).isEqualTo(MemoryType.SEMANTIC);
        assertThat(command.requestedStatus()).isEqualTo(MemoryStatus.CANDIDATE);
        assertThat(command.content()).contains("short Chinese progress summaries");
        assertThat(command.evidenceRefs())
                .containsExactlyInAnyOrder("evidence-episode-1", "evidence-episode-2", "evidence-episode-3");
        assertThat(proposal.sourceVersionIds())
                .containsExactlyInAnyOrder("episode-1", "episode-2", "episode-3");
    }

    @Test
    void proposesProceduralCandidateFromRepeatedWorkflowEpisodes() {
        MemoryConsolidationProposal proposal = new MemoryConsolidationProposer().propose(
                List.of(
                        episodic("episode-1", "Successful PR handoff started by verifying PR state.", List.of("workflow", "pr-review")),
                        episodic("episode-2", "PR review succeeded after checking mergeability first.", List.of("workflow", "pr-review"))
                )
        ).orElseThrow();

        assertThat(proposal.command().memoryType()).isEqualTo(MemoryType.PROCEDURAL);
        assertThat(proposal.command().content()).contains("PR review");
        assertThat(proposal.command().requestedStatus()).isEqualTo(MemoryStatus.CANDIDATE);
    }

    @Test
    void doesNotProposeFromSingleOrMixedTopicEpisode() {
        assertThat(new MemoryConsolidationProposer().propose(List.of(
                episodic("episode-1", "One isolated event.", List.of("preference"))
        ))).isEmpty();
        assertThat(new MemoryConsolidationProposer().propose(List.of(
                episodic("episode-1", "Preference event.", List.of("preference")),
                episodic("episode-2", "Workflow event.", List.of("workflow"))
        ))).isEmpty();
    }

    private static MemoryRecordVersion episodic(String versionId, String content, List<String> tags) {
        return new MemoryRecordVersion(
                null,
                "logical-" + versionId,
                versionId,
                MemoryType.EPISODIC,
                MemoryScopeType.USER,
                "alice",
                "alice",
                content,
                versionId + "-hash",
                content,
                "run-" + versionId,
                List.of("event-" + versionId),
                List.of("evidence-" + versionId),
                tags,
                0.75,
                0.85,
                MemoryStatus.ACTIVE,
                T0,
                null,
                null,
                1,
                1,
                "REFLECTION",
                "run-" + versionId,
                "terminal-reflection-v1",
                1L,
                T0,
                T0,
                false
        );
    }
}
