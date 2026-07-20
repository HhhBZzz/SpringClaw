package com.springclaw.tool.pack;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.files.LocalFilesystemService;
import com.springclaw.tool.runtime.ToolPackDescriptor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件工具包。
 *
 * 设计说明：
 * 1. 仅允许访问受控根目录，避免任意文件读写风险。
 * 2. 通过 @Tool 暴露给模型，避免手写 Function Calling 协议。
 */
@Component
@ToolPackDescriptor(
    id = "file",
    toolset = "file",
    triggerKeywords = {"文件", "目录", "path", "read", "write", "list", "保存", "读取"},
    fallbackCandidate = true,
    riskLevel = "write",
    description = "受控目录的文件读写操作"
)
public class FileToolPack {

    private final LocalFilesystemService localFilesystemService;
    private final Path rootPath;

    @Autowired
    public FileToolPack(LocalFilesystemService localFilesystemService,
                        @Value("${springclaw.tools.local-write.root:${springclaw.tools.file.root:${user.dir}}}") String root) {
        this.localFilesystemService = localFilesystemService;
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
    }

    public FileToolPack(@Value("${springclaw.tools.file.root:${user.dir}}") String root,
                        @Value("${springclaw.tools.file.max-read-chars:12000}") int maxReadChars) {
        this.localFilesystemService = new LocalFilesystemService(
                root,
                ".git,node_modules,target,dist,build,.ssh,.gnupg,.aws,.kube,Library/Keychains,.env",
                maxReadChars,
                8,
                100,
                50,
                512
        );
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
    }

    @Tool(description = "列出指定目录下的文件和目录")
    public String listFiles(String relativeDir) {
        return stripDefaultRootPrefix(localFilesystemService.listFiles("", relativeDir));
    }

    @Tool(description = "读取指定文本文件内容")
    public String readTextFile(String relativeFilePath) {
        return localFilesystemService.readTextFile("", relativeFilePath);
    }

    @Tool(description = "写入文本到指定文件。默认覆盖原文件")
    public String writeTextFile(String relativeFilePath, String content, Boolean overwrite) {
        Path file = resolveSafePath(relativeFilePath, false);
        boolean canOverwrite = overwrite == null || overwrite;

        try {
            if (Files.exists(file) && !canOverwrite) {
                return "文件已存在，且 overwrite=false: " + relativeFilePath;
            }
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
            return "写入成功: " + rootPath.relativize(file);
        } catch (IOException ex) {
            throw new BusinessException(50023, "文件工具 writeTextFile 失败: " + ex.getMessage());
        }
    }

    @Tool(description = "按文件名递归搜索文件，支持通配符，例如 *.java、*Service*.java、application*.yml")
    public String searchFiles(String pattern) {
        return stripDefaultRootPrefix(localFilesystemService.searchFilesByGlob("", pattern));
    }

    @Tool(description = "按内容递归搜索文件，支持 filePattern 过滤，类似 grep。示例：keyword=ChatService, filePattern=*.java")
    public String searchInFiles(String keyword, String filePattern) {
        if (!StringUtils.hasText(keyword)) {
            throw new BusinessException(40053, "keyword 不能为空");
        }
        return stripDefaultRootPrefix(localFilesystemService.grepText(keyword, filePattern));
    }

    private Path resolveSafePath(String raw, boolean allowBlankAsRoot) {
        String value = StringUtils.hasText(raw) ? raw.trim() : "";
        if (!StringUtils.hasText(value) && allowBlankAsRoot) {
            return rootPath;
        }
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(40051, "文件路径不能为空");
        }

        Path target = rootPath.resolve(value).normalize().toAbsolutePath();
        if (!target.startsWith(rootPath)) {
            throw new BusinessException(40052, "文件路径越界，禁止访问 root 外路径");
        }
        return target;
    }

    private String stripDefaultRootPrefix(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        return text.replace("root1:", "");
    }
}
