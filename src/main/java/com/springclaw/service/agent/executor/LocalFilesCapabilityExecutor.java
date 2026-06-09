package com.springclaw.service.agent.executor;

import com.springclaw.common.util.TextUtils;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.CapabilityExecutor;
import com.springclaw.service.agent.CapabilityResult;
import com.springclaw.service.context.AssembledContext;
import com.springclaw.tool.pack.LocalFilesystemToolPack;
import com.springclaw.tool.runtime.ToolExecutionContext;
import com.springclaw.tool.runtime.ToolExecutionContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class LocalFilesCapabilityExecutor extends AbstractCapabilityExecutor implements CapabilityExecutor {

    private final LocalFilesystemToolPack localFilesystemToolPack;

    public LocalFilesCapabilityExecutor(LocalFilesystemToolPack localFilesystemToolPack) {
        this.localFilesystemToolPack = localFilesystemToolPack;
    }

    @Override
    public String toolset() {
        return "local-files";
    }

    @Override
    public boolean supports(AgentDecision decision) {
        return intent(decision, "local_files");
    }

    @Override
    public List<CapabilityResult> execute(AgentDecision decision, AssembledContext assembled, String requestId) {
        List<CapabilityResult> results = new ArrayList<>();
        ToolExecutionContext context = new ToolExecutionContext(assembled.sessionKey(), assembled.channel(), assembled.userId(), requestId, "AGENT-RUNTIME");
        try (ToolExecutionContextHolder.Scope ignored = ToolExecutionContextHolder.open(context)) {
            results.add(run("local-files.roots", toolset(), "read", "列出授权本地目录", localFilesystemToolPack::listAuthorizedRoots));
            DirectoryTarget directoryTarget = detectDirectoryTarget(assembled.question());
            if (directoryTarget != null) {
                results.add(run(
                        "local-files.list-" + directoryTarget.id(),
                        toolset(),
                        "read",
                        "列出" + directoryTarget.label() + "目录文件",
                        () -> localFilesystemToolPack.listAuthorizedFiles("root1", directoryTarget.relativeDir())
                ));
                return results;
            }
            String keyword = extractSearchKeyword(assembled.question());
            if (StringUtils.hasText(keyword)) {
                results.add(run("local-files.search", toolset(), "read", "搜索授权目录文件", () -> localFilesystemToolPack.searchAuthorizedFiles(keyword)));
            }
        }
        return results;
    }

    private String extractSearchKeyword(String question) {
        String text = question == null ? "" : question.trim();
        if (!StringUtils.hasText(text)) {
            return "";
        }
        for (String keyword : List.of("桌面", "下载", "简历", "论文", "docx", "pdf", "md", "csv", "json")) {
            if (text.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                return keyword;
            }
        }
        return text.length() <= 40 ? text : "";
    }

    private DirectoryTarget detectDirectoryTarget(String question) {
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (TextUtils.containsAny(text, "桌面", "desktop")) {
            return new DirectoryTarget("desktop", "桌面", "Desktop");
        }
        if (TextUtils.containsAny(text, "下载", "downloads", "download")) {
            return new DirectoryTarget("downloads", "下载", "Downloads");
        }
        if (TextUtils.containsAny(text, "文档", "documents")) {
            return new DirectoryTarget("documents", "文档", "Documents");
        }
        return null;
    }


    private record DirectoryTarget(String id, String label, String relativeDir) {
    }
}
