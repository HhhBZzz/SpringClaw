package com.springclaw.service.proposal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.workspace.GitOperations;
import com.springclaw.service.workspace.PathNormalizer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 创建 proposal 时的快照构建器：
 * - canonicalize args（嵌套 map 的 key 按字典序）
 * - sha256(toolName + toolsetId + canonicalJson)
 * - 解析 targetPaths（按工具名分发规则）
 * - 全部路径经 PathNormalizer 统一为 repo-relative posix（不变量 12）
 * - 检查 dirtyFiles ∩ targetPaths：非空时拒绝 proposal（不变量 6 前置）
 * - 记录 git HEAD 作为 baseline（不变量 10 前置）
 */
@Service
public class ToolInvocationSnapshotService {

    private final PathNormalizer pathNormalizer;
    private final GitOperations git;
    private final ObjectMapper canonicalMapper;

    public ToolInvocationSnapshotService(PathNormalizer pathNormalizer, GitOperations git) {
        this.pathNormalizer = pathNormalizer;
        this.git = git;
        this.canonicalMapper = new ObjectMapper()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public ToolInvocationSnapshot capture(String toolName,
                                          String toolsetId,
                                          Object[] args,
                                          String riskLevel) {
        String canonicalJson = canonicalize(args);
        String argsHash = sha256(toolName + "\n" + toolsetId + "\n" + canonicalJson);

        List<String> targetPaths = parseTargetPaths(toolName, args).stream()
                .map(p -> pathNormalizer.normalizeRepoPath(git.workspaceRoot(), p))
                .distinct()
                .toList();

        String headSha = git.headSha();

        Set<String> dirty = git.statusNameOnly().stream()
                .map(p -> pathNormalizer.normalizeRepoPath(git.workspaceRoot(), p))
                .collect(Collectors.toUnmodifiableSet());

        Set<String> intersection = dirty.stream()
                .filter(targetPaths::contains)
                .collect(Collectors.toUnmodifiableSet());
        if (!intersection.isEmpty()) {
            throw new BusinessException(40901,
                    "目标文件已有未提交改动，请先 commit/stash/清理后再执行: " + intersection);
        }

        return new ToolInvocationSnapshot(
                toolName, toolsetId, canonicalJson, argsHash,
                riskLevel, targetPaths,
                buildPreview(toolName, args, targetPaths),
                !dirty.isEmpty(), dirty,
                headSha
        );
    }

    /**
     * 给 ToolRuntimeAspect 用：confirm-resume 时只需复算 hash 与 stored argsHash 比对，
     * 不需要重新解析 targetPaths / 检查 dirty。
     */
    public String argsHash(String toolName, String toolsetId, Object[] args) {
        return sha256(toolName + "\n" + toolsetId + "\n" + canonicalize(args));
    }

    private String canonicalize(Object[] args) {
        try {
            return canonicalMapper.writeValueAsString(args == null ? new Object[0] : args);
        } catch (Exception ex) {
            throw new BusinessException(50090, "canonicalize args failed: " + ex.getMessage());
        }
    }

    private String sha256(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /**
     * 工具 → targetPaths 解析规则：
     * - workspaceWriteFile / workspaceApplyPatch / writeTextFile：args[0] 是 relativePath
     * - workspaceRunCommand / runCommand：无法静态预测目标文件，返回空列表（确认提示按命令文本展示）
     * - 其他写工具：默认返回空列表（调用方按零目标处理，等于"无法预测影响范围"）
     */
    private List<String> parseTargetPaths(String toolName, Object[] args) {
        if (args == null || args.length == 0) return List.of();
        String simple = simpleName(toolName);
        return switch (simple) {
            case "workspaceWriteFile",
                 "workspaceApplyPatch",
                 "writeTextFile" -> args[0] == null ? List.of() : List.of(args[0].toString());
            case "workspaceRunCommand" -> List.of();
            // 未知写工具回退到不变量 7 的执行期保护：任何文件改动都会被 WorkspaceGitGuard 判为
            // out-of-scope 并触发 rollback。这是 fail-closed 默认——比"放行未知工具"更安全。
            // 后续若新增写工具，应在此 switch 显式声明 targetPaths 解析规则。
            default -> List.of();
        };
    }

    private String buildPreview(String toolName, Object[] args, List<String> targetPaths) {
        String fn = simpleName(toolName);
        if (("workspaceRunCommand".equals(fn) || "runCommand".equals(fn))
                && args != null && args.length > 0) {
            return fn + ": " + truncate(String.valueOf(args[0]), 240);
        }
        return fn + ": " + String.join(", ", targetPaths);
    }

    private String simpleName(String toolName) {
        if (toolName == null) return "";
        int dot = toolName.lastIndexOf('.');
        return dot < 0 ? toolName : toolName.substring(dot + 1);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
