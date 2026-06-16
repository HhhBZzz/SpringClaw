package com.springclaw.service.workspace;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Git CLI 封装。Task 4 只用 headSha / statusNameOnly；
 * add / commit / checkoutFromSha / isTracked / deleteFile 在 Task 5 加。
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
