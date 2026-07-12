package com.springclaw.service.workspace;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Produces a stable, fixed-width identity for one physical workspace. */
@Component
public class WorkspaceIdentity {

    public String id(Path workspaceRoot) {
        try {
            String canonicalPath = workspaceRoot.toRealPath().normalize().toString();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalPath.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException ex) {
            throw new IllegalStateException("工作区真实路径解析失败: " + workspaceRoot, ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("JVM 不支持 SHA-256", ex);
        }
    }
}
