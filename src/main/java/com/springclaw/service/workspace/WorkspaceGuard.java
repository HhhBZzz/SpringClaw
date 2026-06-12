package com.springclaw.service.workspace;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.common.util.TextUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Minimal workspace boundary decision model for file paths and shell commands.
 */
@Service
public class WorkspaceGuard {

    private static final Pattern PARENT_PATH_SEGMENT = Pattern.compile("(^|[\\s;&|()<>'\"=:/\\\\])\\.\\.(?=$|[\\s;&|()<>'\"=:/\\\\])");

    private final Path rootPath;

    public WorkspaceGuard(@Value("${springclaw.tools.file.root:${user.dir}}") String root) {
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
    }

    public Decision checkPath(String relativePath) {
        String normalized = relativePath == null ? "" : relativePath.trim();
        Path target = rootPath.resolve(normalized).normalize().toAbsolutePath();
        if (!target.startsWith(rootPath)) {
            return reject("PATH_OUTSIDE_WORKSPACE", "文件路径越界，禁止访问项目根目录外路径", target);
        }
        Decision resolvedDecision = checkResolvedInsideRoot(target);
        if (resolvedDecision.action() == Action.REJECT) {
            return resolvedDecision;
        }
        return new Decision(Action.ALLOW, "ALLOW", "允许访问工作区路径", target);
    }

    public Path requirePath(String relativePath) {
        Decision decision = checkPath(relativePath);
        if (decision.action() == Action.REJECT) {
            throw new WorkspaceGuardException(40067, decision);
        }
        return decision.resolvedPath();
    }

    public Decision checkCommand(String command) {
        if (!StringUtils.hasText(command)) {
            return reject("COMMAND_EMPTY", "命令不能为空", null);
        }
        String normalizedCommand = command.trim();
        String lower = normalizedCommand.toLowerCase(Locale.ROOT);
        if (containsDangerousCommand(lower)) {
            return reject("COMMAND_DANGEROUS", "命令包含危险操作，已被拦截", null);
        }
        if (containsParentPathSegment(normalizedCommand)) {
            return reject("COMMAND_PARENT_PATH", "命令包含父目录路径段，已被拦截", null);
        }
        return new Decision(Action.ALLOW, "ALLOW", "允许执行工作区命令", null);
    }

    public void requireCommand(String command) {
        Decision decision = checkCommand(command);
        if (decision.action() == Action.REJECT) {
            String detail = StringUtils.hasText(command) ? ": " + TextUtils.truncate(command.trim(), 60) : "";
            throw new WorkspaceGuardException(40065, new Decision(
                    decision.action(),
                    decision.reasonCode(),
                    decision.message() + detail,
                    decision.resolvedPath()
            ));
        }
    }

    private Decision checkResolvedInsideRoot(Path target) {
        try {
            Path realRoot = rootPath.toRealPath();
            Path existing = nearestExistingPath(target);
            if (existing == null) {
                return new Decision(Action.ALLOW, "ALLOW", "允许访问新工作区路径", target);
            }
            Path realExisting = existing.toRealPath();
            if (!realExisting.startsWith(realRoot)) {
                return reject("PATH_OUTSIDE_WORKSPACE", "文件路径越界，禁止访问项目根目录外路径", target);
            }
            return new Decision(Action.ALLOW, "ALLOW", "允许访问工作区真实路径", target);
        } catch (IOException ex) {
            return reject("PATH_CHECK_FAILED", "工作区路径校验失败: " + ex.getMessage(), target);
        }
    }

    private Path nearestExistingPath(Path target) {
        Path current = target;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        return current;
    }

    private boolean containsDangerousCommand(String lower) {
        return lower.contains("rm -rf") || lower.contains("rm -r /") || lower.contains("rmdir")
                || lower.contains(":(){ :|:&")
                || lower.contains("dd if=") && lower.contains("of=/dev/")
                || lower.contains("mkfs") || lower.contains("format")
                || lower.contains("> /dev/sd") || lower.contains("shutdown")
                || lower.contains("reboot") || lower.contains("poweroff")
                || lower.contains("chmod 777 /") || lower.contains("chown root");
    }

    private boolean containsParentPathSegment(String command) {
        return StringUtils.hasText(command) && PARENT_PATH_SEGMENT.matcher(command).find();
    }

    private Decision reject(String reasonCode, String message, Path resolvedPath) {
        return new Decision(Action.REJECT, reasonCode, message, resolvedPath);
    }

    public enum Action {
        ALLOW,
        ASK_USER,
        REJECT
    }

    public record Decision(Action action, String reasonCode, String message, Path resolvedPath) {
    }

    public static class WorkspaceGuardException extends BusinessException {

        private final Decision decision;

        public WorkspaceGuardException(int code, Decision decision) {
            super(code, decision == null ? "工作区边界校验失败" : decision.message());
            this.decision = decision;
        }

        public Decision decision() {
            return decision;
        }
    }
}
