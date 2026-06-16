package com.springclaw.service.workspace;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 把任意写法的路径规范化为 repo-relative posix 路径（不变量 12）。
 * 所有 targetPaths/dirtyFiles/changedFiles 比较都必须先经过此方法。
 *
 * <p><b>安全前提</b>：仅做词法规范化（{@link java.nio.file.Path#normalize()}），
 * 不解析符号链接。如果 workspace 内存在指向外部的 symlink，本方法不会发现 escape。
 * P0 威胁模型为「agent 路径计算可能出错」而非「恶意 agent 预先布置 symlink」；
 * 升级威胁模型时改用 {@code toRealPath(LinkOption.NOFOLLOW_LINKS)}-based 校验，
 * 并加 workspace 启动期 symlink 扫描。
 */
@Component
public class PathNormalizer {

    public String normalizeRepoPath(Path workspaceRoot, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new SecurityException("empty path");
        }
        String unified = rawPath.replace('\\', '/');
        Path normalized = workspaceRoot.resolve(unified).normalize();
        if (!normalized.startsWith(workspaceRoot)) {
            throw new SecurityException("path escapes workspace: " + rawPath);
        }
        return workspaceRoot.relativize(normalized).toString().replace('\\', '/');
    }
}
