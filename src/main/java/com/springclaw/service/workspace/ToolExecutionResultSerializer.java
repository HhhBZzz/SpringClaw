package com.springclaw.service.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.springclaw.service.proposal.ToolInvocationProposal;
import org.springframework.stereotype.Component;

import java.util.List;

/** Encodes successful tool results and their Git/fencing audit metadata as versioned JSON. */
@Component
public class ToolExecutionResultSerializer {

    static final String SCHEMA = "springclaw.tool-execution-result.v1";
    private static final int MAX_RESULT_BYTES = 32 * 1024;
    private static final int MAX_SUMMARY_CHARS = 4_096;

    private final ObjectMapper objectMapper;

    public ToolExecutionResultSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String serialize(ToolInvocationProposal proposal,
                            Object result,
                            long fencingToken,
                            String commitSha,
                            List<String> changedFiles,
                            boolean noOp) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("schema", SCHEMA);
        envelope.put("proposalId", proposal.proposalId());
        envelope.put("toolName", proposal.toolName());
        envelope.put("success", true);
        envelope.put("fencingToken", fencingToken);
        envelope.put("noOp", noOp);
        if (commitSha == null) {
            envelope.putNull("gitCommitSha");
        } else {
            envelope.put("gitCommitSha", commitSha);
        }
        envelope.set("changedFiles", objectMapper.valueToTree(
                changedFiles == null ? List.of() : changedFiles));
        envelope.put("resultType", result == null ? "null" : result.getClass().getName());

        if (result == null) {
            envelope.putNull("result");
            envelope.put("resultTruncated", false);
        } else {
            appendResult(envelope, result);
        }

        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception ex) {
            throw new IllegalStateException("工具执行结果 JSON 编码失败", ex);
        }
    }

    private void appendResult(ObjectNode envelope, Object result) {
        try {
            byte[] encoded = objectMapper.writeValueAsBytes(result);
            if (encoded.length <= MAX_RESULT_BYTES) {
                JsonNode resultNode = objectMapper.readTree(encoded);
                envelope.set("result", resultNode);
                envelope.put("resultTruncated", false);
                return;
            }
        } catch (Exception ignored) {
            // Fall through to a bounded textual representation.
        }

        envelope.put("resultSummary", truncate(safeToString(result)));
        envelope.put("resultTruncated", true);
    }

    private String safeToString(Object result) {
        try {
            return String.valueOf(result);
        } catch (RuntimeException ex) {
            return result.getClass().getName() + " (toString failed: " + ex.getClass().getSimpleName() + ")";
        }
    }

    private String truncate(String value) {
        if (value.length() <= MAX_SUMMARY_CHARS) {
            return value;
        }
        return value.substring(0, MAX_SUMMARY_CHARS);
    }
}
