package com.springclaw.tool.pack;

import com.springclaw.service.files.LocalFilesystemService;
import com.springclaw.tool.runtime.ToolPackDescriptor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 本地授权文件工具包。
 *
 * 面向用户显式授权的电脑目录，不等同于全盘读取。
 */
@Component
@ToolPackDescriptor(
    id = "local-files",
    toolset = "file",
    triggerKeywords = {"本地文件", "授权文件", "电脑文件", "桌面", "下载", "文档", "简历", "论文", "Desktop", "Downloads", "Documents"},
    fallbackCandidate = true,
    riskLevel = "read",
    description = "读取授权本地目录中的文件"
)
public class LocalFilesystemToolPack {

    private final LocalFilesystemService localFilesystemService;

    public LocalFilesystemToolPack(LocalFilesystemService localFilesystemService) {
        this.localFilesystemService = localFilesystemService;
    }

    @Tool(description = "列出当前 Agent 被授权读取的本地目录根。返回 root1/root2 等别名，后续读取文件时使用这些别名")
    public String listAuthorizedRoots() {
        return localFilesystemService.listAuthorizedRoots();
    }

    @Tool(description = "列出某个授权根目录下的文件。rootRef 传 root1/root2 或授权目录名；relativeDir 为空表示根目录")
    public String listAuthorizedFiles(String rootRef, String relativeDir) {
        return localFilesystemService.listFiles(rootRef, relativeDir);
    }

    @Tool(description = "读取授权目录内的文本文件。rootRef 传 root1/root2；relativeFilePath 是该授权根目录内的相对路径")
    public String readAuthorizedTextFile(String rootRef, String relativeFilePath) {
        return localFilesystemService.readTextFile(rootRef, relativeFilePath);
    }

    @Tool(description = "按文件名关键词搜索所有授权目录内的文件，例如 简历、论文、SpringClaw")
    public String searchAuthorizedFiles(String keyword) {
        return localFilesystemService.searchFiles(keyword);
    }

    @Tool(description = "按文本关键词搜索所有授权目录内的文本文件内容，返回命中文件和行号")
    public String grepAuthorizedText(String keyword) {
        return localFilesystemService.grepText(keyword);
    }
}
