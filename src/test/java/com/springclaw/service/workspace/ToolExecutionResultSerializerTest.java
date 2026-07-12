package com.springclaw.service.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalStatus;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutionResultSerializerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ToolExecutionResultSerializer serializer =
            new ToolExecutionResultSerializer(objectMapper);

    @Test
    void serializePreservesRealResultAndGitAuditMetadata() throws Exception {
        String json = serializer.serialize(
                proposal(), Map.of("written", true, "bytes", 12), 7L,
                "def5678", List.of("src/A.java"), false);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.path("schema").asText()).isEqualTo("springclaw.tool-execution-result.v1");
        assertThat(root.path("proposalId").asText()).isEqualTo("tip-result-1");
        assertThat(root.path("toolName").asText())
                .isEqualTo("WorkspaceEditToolPack.workspaceWriteFile");
        assertThat(root.path("success").asBoolean()).isTrue();
        assertThat(root.path("fencingToken").asLong()).isEqualTo(7L);
        assertThat(root.path("noOp").asBoolean()).isFalse();
        assertThat(root.path("gitCommitSha").asText()).isEqualTo("def5678");
        assertThat(root.path("changedFiles")).containsExactly(
                objectMapper.getNodeFactory().textNode("src/A.java"));
        assertThat(root.path("result").path("written").asBoolean()).isTrue();
        assertThat(root.path("result").path("bytes").asInt()).isEqualTo(12);
        assertThat(root.path("resultTruncated").asBoolean()).isFalse();
    }

    @Test
    void serializeKeepsNullAsARealJsonNullResult() throws Exception {
        JsonNode root = objectMapper.readTree(serializer.serialize(
                proposal(), null, 8L, null, List.of(), true));

        assertThat(root.path("result").isNull()).isTrue();
        assertThat(root.path("resultType").asText()).isEqualTo("null");
        assertThat(root.path("noOp").asBoolean()).isTrue();
    }

    @Test
    void serializeTruncatesOversizedResultButKeepsEnvelopeValidAndBounded() throws Exception {
        String json = serializer.serialize(
                proposal(), "x".repeat(40_000), 9L, null, List.of(), true);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.path("resultTruncated").asBoolean()).isTrue();
        assertThat(root.path("resultSummary").asText()).hasSize(4_096);
        assertThat(root.has("result")).isFalse();
        assertThat(json.getBytes(StandardCharsets.UTF_8).length).isLessThan(16_384);
    }

    @Test
    void serializeFallsBackToSummaryWhenJacksonCannotSerializeResult() throws Exception {
        String json = serializer.serialize(
                proposal(), new SelfReferencingResult(), 10L, null, List.of(), true);

        JsonNode root = objectMapper.readTree(json);
        assertThat(root.path("resultTruncated").asBoolean()).isTrue();
        assertThat(root.path("resultSummary").asText()).contains("SelfReferencingResult");
        assertThat(root.has("result")).isFalse();
    }

    private ToolInvocationProposal proposal() {
        LocalDateTime now = LocalDateTime.now();
        return new ToolInvocationProposal(
                null, "tip-result-1", "req-1", "run-1",
                "session-A", "user-1", "USER",
                "WorkspaceEditToolPack.workspaceWriteFile", "workspace",
                "[]", "deadbeef",
                "write", List.of("src/A.java"), "preview",
                false, List.of(),
                ToolInvocationProposalStatus.EXECUTING, 0,
                null, null, null,
                "abc1234", null, null, List.of(),
                null, null,
                now, now, now.plusMinutes(15));
    }

    private static final class SelfReferencingResult {
        public SelfReferencingResult getSelf() {
            return this;
        }
    }
}
