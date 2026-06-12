package com.springclaw.tool.pack;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.util.TextUtils;
import com.springclaw.service.chat.impl.AutonomousExecutionTracker;
import com.springclaw.service.workspace.WorkspaceGuard;
import com.springclaw.tool.runtime.ToolPackDescriptor;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 工作区编辑工具包 — 让 Agent 能自主修改项目文件和执行命令。
 *
 * 这是 SpringClaw 从"只能看的 chatbot"进化为"能干活的 agent"的关键能力。
 * 参考 Codex (apply_patch + shell)、Claude Code (edit + bash)、Hermes (execute_code)。
 *
 * 安全设计：
 * - 所有文件操作限制在项目根目录内（path traversal 拦截）
 * - shell 命令有白名单和超时控制
 * - 写操作需要 requiresConfirmation=true（AgentDecision 控制）
 */
@Component
@ToolPackDescriptor(
    id = "workspace-edit",
    toolset = "workspace",
    triggerKeywords = {"修改", "编辑", "修复", "实现", "写代码", "改代码", "重构", "apply patch", "edit file"},
    fallbackCandidate = false,
    riskLevel = "write",
    preferredMode = "opar",
    description = "修改项目文件内容和执行验证命令（写文件、搜索替换、运行命令）"
)
public class WorkspaceEditToolPack {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceEditToolPack.class);

    private final Path rootPath;
    private final int maxCommandTimeoutSeconds;
    private final int maxCommandOutputChars;
    private final WorkspaceGuard workspaceGuard;

    @Autowired
    public WorkspaceEditToolPack(
            @Value("${springclaw.tools.file.root:${user.dir}}") String root,
            @Value("${springclaw.tools.workspace.command-timeout-seconds:30}") int maxCommandTimeoutSeconds,
            @Value("${springclaw.tools.workspace.max-command-output-chars:8000}") int maxCommandOutputChars,
            WorkspaceGuard workspaceGuard) {
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
        this.maxCommandTimeoutSeconds = Math.max(5, maxCommandTimeoutSeconds);
        this.maxCommandOutputChars = Math.max(1000, maxCommandOutputChars);
        this.workspaceGuard = workspaceGuard == null ? new WorkspaceGuard(root) : workspaceGuard;
    }

    public WorkspaceEditToolPack(String root,
                                 int maxCommandTimeoutSeconds,
                                 int maxCommandOutputChars) {
        this(root, maxCommandTimeoutSeconds, maxCommandOutputChars, new WorkspaceGuard(root));
    }

    /**
     * 将搜索替换规则应用到指定文件，用于精准修改代码片段。
     * 类似 Codex 的 apply_patch 和 Claude Code 的 edit。
     *
     * @param relativePath 相对项目根目录的文件路径
     * @param searchText   要搜索的原始文本片段（必须精确匹配）
     * @param replaceText  替换后的文本
     * @return 操作结果描述
     */
    @Tool(description = "在项目文件中做搜索替换修改。searchText 必须精确匹配文件中的原始文本片段，replaceText 是替换后的内容。用于精准修改代码。这是 WorkspaceEditToolPack 专有的编辑工具。")
    public String workspaceApplyPatch(String relativePath, String searchText, String replaceText) {
        if (!StringUtils.hasText(relativePath)) {
            throw new BusinessException(40060, "文件路径不能为空");
        }
        if (!StringUtils.hasText(searchText)) {
            throw new BusinessException(40061, "搜索文本不能为空");
        }

        Path file = resolveSafePath(relativePath);
        if (!Files.exists(file)) {
            throw new BusinessException(40062, "文件不存在: " + relativePath);
        }
        if (!Files.isRegularFile(file)) {
            throw new BusinessException(40063, "不是常规文件: " + relativePath);
        }

        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int index = content.indexOf(searchText);
            if (index < 0) {
                // 尝试忽略空白差异的模糊匹配
                String normalizedSearch = normalizeWhitespace(searchText);
                String normalizedContent = normalizeWhitespace(content);
                int fuzzyIndex = normalizedContent.indexOf(normalizedSearch);
                if (fuzzyIndex < 0) {
                    return "搜索文本未在文件中找到: " + relativePath + "\n请确认 searchText 是文件中的原始文本片段。";
                }
                // 模糊匹配成功，但需要精确定位原始位置
                // 给模型提示让它重新 read 文件获取精确文本
                return "搜索文本与文件内容有空白差异，未能精确匹配: " + relativePath
                        + "\n建议先用 readTextFile 读取文件获取精确的原始文本，再用精确文本调用 applyPatch。";
            }

            String newContent = content.substring(0, index) + replaceText + content.substring(index + searchText.length());
            Files.writeString(file, newContent, StandardCharsets.UTF_8);

            long originalLines = countLines(content);
            long newLines = countLines(newContent);

            // 记录到自主循环执行追踪器
            AutonomousExecutionTracker tracker = ToolExecutionContextHolder.getTracker();
            if (tracker != null) {
                tracker.recordApplyPatch(relativePath, true);
            }

            return "修改成功: " + rootPath.relativize(file) + "\n"
                    + "替换了 " + originalLines + " → " + newLines + " 行"
                    + "（差异 " + (newLines - originalLines) + " 行）";
        } catch (IOException ex) {
            throw new BusinessException(50060, "文件修改失败: " + ex.getMessage());
        }
    }

    /**
     * 执行 shell 命令用于验证修改结果（编译检查、跑测试等）。
     * 类似 Codex 的 shell 和 Claude Code 的 bash。
     * 命令在项目根目录执行，有超时控制。
     *
     * @param command 要执行的 shell 命令
     * @return 命令输出（stdout + stderr），截断到最大长度
     */
    @Tool(description = "在项目根目录执行 shell 命令，用于编译检查、跑测试、git 操作等验证。命令有超时限制，输出截断到最大长度。这是 WorkspaceEditToolPack 专有的命令执行工具。")
    public String workspaceRunCommand(String command) {
        if (!StringUtils.hasText(command)) {
            throw new BusinessException(40064, "命令不能为空");
        }
        String normalizedCommand = command.trim();
        // 安全检查：拦截危险命令
        workspaceGuard.requireCommand(normalizedCommand);

        log.info("执行命令: {}", TextUtils.truncate(normalizedCommand, 120));

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", normalizedCommand)
                    .directory(rootPath.toFile())
                    .redirectErrorStream(true);

            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            boolean completed = process.waitFor(maxCommandTimeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return "命令超时（" + maxCommandTimeoutSeconds + "秒），已强制终止。\n部分输出:\n" + truncate(output);
            }

            int exitCode = process.exitValue();
            String truncatedOutput = truncate(output);

            // 记录到自主循环执行追踪器
            AutonomousExecutionTracker tracker = ToolExecutionContextHolder.getTracker();
            if (tracker != null) {
                tracker.recordRunCommand(normalizedCommand, exitCode);
                // 命令验证了副作用（例如 ls/cat 检查文件是否存在）
                tracker.recordSideEffectVerification();
            }

            return "exitCode=" + exitCode + "\n" + truncatedOutput;
        } catch (IOException ex) {
            throw new BusinessException(50061, "命令执行失败: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50062, "命令执行被中断: " + ex.getMessage());
        }
    }

    /**
     * 创建新文件或完全覆盖写入文件内容。
     *
     * @param relativePath 相对项目根目录的文件路径
     * @param content      文件完整内容
     * @return 操作结果描述
     */
    @Tool(description = "创建新文件或完全覆盖写入文件内容。用于创建新类、配置文件等。会自动创建中间目录。这是 WorkspaceEditToolPack 专有的写文件工具。")
    public String workspaceWriteFile(String relativePath, String content) {
        if (!StringUtils.hasText(relativePath)) {
            throw new BusinessException(40066, "文件路径不能为空");
        }

        Path file = resolveSafePath(relativePath);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);

            // 记录到自主循环执行追踪器
            AutonomousExecutionTracker tracker = ToolExecutionContextHolder.getTracker();
            if (tracker != null) {
                tracker.recordWriteFile(relativePath, (int) countLines(content));
            }

            return "写入成功: " + rootPath.relativize(file) + " (" + countLines(content) + " 行)";
        } catch (IOException ex) {
            throw new BusinessException(50063, "文件写入失败: " + ex.getMessage());
        }
    }

    private Path resolveSafePath(String relativePath) {
        return workspaceGuard.requirePath(relativePath);
    }

    private String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= maxCommandOutputChars) return text;
        return text.substring(0, maxCommandOutputChars) + "\n...<TRUNCATED, total=" + text.length() + " chars>";
    }

    private String normalizeWhitespace(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", " ").trim();
    }

    private long countLines(String content) {
        if (content == null || content.isEmpty()) return 0;
        return content.split("\n", -1).length;
    }
}
