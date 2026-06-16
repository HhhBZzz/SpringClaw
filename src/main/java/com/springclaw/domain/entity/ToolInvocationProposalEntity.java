package com.springclaw.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalStatus;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * tool_invocation_proposal 表实体。
 *
 * 不继承 BaseEntity——本表的审计时间由 ToolInvocationProposalService 显式控制，
 * 不能让 MyBatis-Plus 的 FieldFill 在 update 时偷偷改 update_time，
 * 否则状态机 / 乐观锁 CAS 的"原子推进"语义会失真。
 *
 * JSON 数组字段（target_paths、dirty_files_at_create、git_changed_files）以 TEXT 落库，
 * 由 fromDomain / toDomain 显式编解码，避免与 MyBatis-Plus TypeHandler 的隐式行为耦合。
 */
@Data
@TableName("tool_invocation_proposal")
public class ToolInvocationProposalEntity implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_OF_STRING = new TypeReference<>() {};

    @TableId(type = IdType.AUTO)
    private Long id;

    private String proposalId;
    private String requestId;
    private String runId;
    private String sessionKey;
    private String userId;
    private String roleCode;

    private String toolName;
    private String toolsetId;
    private String argumentsCanonicalJson;
    private String argumentsHash;
    private String riskLevel;
    private String targetPaths;          // JSON array
    private String previewSummary;

    private Integer workspaceDirtyAtCreate;  // 0/1
    private String dirtyFilesAtCreate;        // JSON array

    private String status;
    private Integer version;

    private LocalDateTime executedAt;
    private String executionResult;
    private String executionError;

    private String gitHeadShaAtCreate;
    private String gitBaselineSha;
    private String gitCommitSha;
    private String gitChangedFiles;           // JSON array

    private LocalDateTime reviewedAt;
    private String reviewReason;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime expiresAt;

    @TableField("deleted")
    @TableLogic(value = "0", delval = "1")
    private Integer deleted;

    public static ToolInvocationProposalEntity fromDomain(ToolInvocationProposal p) {
        ToolInvocationProposalEntity e = new ToolInvocationProposalEntity();
        e.id = p.id();
        e.proposalId = p.proposalId();
        e.requestId = p.requestId();
        e.runId = p.runId();
        e.sessionKey = p.sessionKey();
        e.userId = p.userId();
        e.roleCode = p.roleCode();
        e.toolName = p.toolName();
        e.toolsetId = p.toolsetId();
        e.argumentsCanonicalJson = p.argumentsCanonicalJson();
        e.argumentsHash = p.argumentsHash();
        e.riskLevel = p.riskLevel();
        e.targetPaths = encodeList(p.targetPaths());
        e.previewSummary = p.previewSummary();
        e.workspaceDirtyAtCreate = p.workspaceDirtyAtCreate() ? 1 : 0;
        e.dirtyFilesAtCreate = encodeList(p.dirtyFilesAtCreate());
        e.status = p.status() == null ? null : p.status().name();
        e.version = p.version();
        e.executedAt = p.executedAt();
        e.executionResult = p.executionResult();
        e.executionError = p.executionError();
        e.gitHeadShaAtCreate = p.gitHeadShaAtCreate();
        e.gitBaselineSha = p.gitBaselineSha();
        e.gitCommitSha = p.gitCommitSha();
        e.gitChangedFiles = encodeList(p.gitChangedFiles());
        e.reviewedAt = p.reviewedAt();
        e.reviewReason = p.reviewReason();
        e.createTime = p.createTime();
        e.updateTime = p.updateTime();
        e.expiresAt = p.expiresAt();
        e.deleted = 0;
        return e;
    }

    public ToolInvocationProposal toDomain() {
        return new ToolInvocationProposal(
                id, proposalId, requestId, runId, sessionKey, userId, roleCode,
                toolName, toolsetId, argumentsCanonicalJson, argumentsHash, riskLevel,
                decodeList(targetPaths), previewSummary,
                workspaceDirtyAtCreate != null && workspaceDirtyAtCreate == 1,
                decodeList(dirtyFilesAtCreate),
                status == null ? null : ToolInvocationProposalStatus.valueOf(status),
                version == null ? 0 : version,
                executedAt, executionResult, executionError,
                gitHeadShaAtCreate, gitBaselineSha, gitCommitSha,
                decodeList(gitChangedFiles),
                reviewedAt, reviewReason,
                createTime, updateTime, expiresAt
        );
    }

    public static String encodeList(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (Exception ex) {
            throw new IllegalStateException("encode JSON array failed", ex);
        }
    }

    public static List<String> decodeList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, LIST_OF_STRING);
        } catch (Exception ex) {
            // 安全敏感字段（targetPaths/dirtyFilesAtCreate/gitChangedFiles）腐烂不应被静默归零，
            // 否则越界写文件检查会基于空目标列表通过。Fail loud。
            throw new IllegalStateException("decode JSON array failed: " + json, ex);
        }
    }
}
