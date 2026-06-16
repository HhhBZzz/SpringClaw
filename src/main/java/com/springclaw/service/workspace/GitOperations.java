package com.springclaw.service.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Git CLI 封装。Task 4 只用 headSha / statusNameOnly；
 * Task 5 补全 add / commit / checkoutFromSha / isTracked / deleteFile。
 */
@Component
public class GitOperations {

    private final Path workspaceRoot;

    public GitOperations(@Value("${springclaw.tools.file.root:${user.dir}}") String root) {
        this.workspaceRoot = Path.of(root).toAbsolutePath().normalize();
    }

    public Path workspaceRoot() {
        return workspaceRoot;
    }

    public String headSha() {
        return run("git", "rev-parse", "HEAD").trim();
    }

    /**
     * 返回 working tree 中所有有改动的文件（含 untracked），
     * 路径形式来自 git，可能含 backslash（Windows）— 不做规范化，留给 PathNormalizer。
     */
    public List<String> statusNameOnly() {
        // -z 用 NUL 分隔，每条记录是 "XY pathname"。
        String out = run("git", "status", "--porcelain=v1", "-z");
        if (out.isEmpty()) return List.of();
        return Arrays.stream(out.split("\0"))
                .filter(s -> s.length() > 3)
                .map(s -> s.substring(3))
                .toList();
    }

    public void add(List<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("add");
        cmd.add("--");
        cmd.addAll(paths);
        run(cmd.toArray(String[]::new));
    }

    /**
     * Commit currently staged changes with the given message and return the new HEAD sha.
     * Caller is responsible for staging via {@link #add(List)} first.
     */
    public String commit(String message) {
        run("git", "commit", "-m", message);
        return headSha();
    }

    /**
     * Restore a single path from the given commit sha. Tracked files only.
     */
    public void checkoutFromSha(String sha, String path) {
        run("git", "checkout", sha, "--", path);
    }

    /**
     * @return true iff the path is currently tracked by git.
     */
    public boolean isTracked(String relativePath) {
        try {
            run("git", "ls-files", "--error-unmatch", "--", relativePath);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Delete the file at the given repo-relative path. Used to clean untracked files
     * after rollback (tracked files use {@link #checkoutFromSha(String, String)} instead).
     */
    public void deleteFile(String relativePath) {
        Path file = workspaceRoot.resolve(relativePath);
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            throw new RuntimeException("delete file failed: " + relativePath, ex);
        }
    }

    String run(String... cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(workspaceRoot.toFile())
                    .redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new RuntimeException("git command timeout: " + String.join(" ", cmd));
            }
            if (p.exitValue() != 0) {
                throw new RuntimeException("git command failed: " + String.join(" ", cmd) + "\n" + out);
            }
            return out;
        } catch (IOException ex) {
            throw new RuntimeException("git command error: " + String.join(" ", cmd), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("git command interrupted: " + String.join(" ", cmd), ex);
        }
    }
}
