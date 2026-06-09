package com.springclaw.service.prompt;

import com.springclaw.common.util.TextUtils;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.files.LocalFilesystemService;
import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.SkillService;
import com.springclaw.tool.runtime.CapabilityRegistry;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SOUL 人格提示词服务。
 *
 * 设计说明：
 * 1. 通过 ApplicationRunner 在应用启动时加载 SOUL.md 到内存，避免每次请求都走磁盘 IO。
 * 2. 通过 PromptTemplate 统一拼接系统提示词，避免字符串拼接散落在业务代码里。
 */
@Service
public class SoulPromptService implements ApplicationRunner {

    private static final String DEFAULT_SOUL = "你是 SpringClaw-Java，一个专业、稳健的企业级 AI Agent 助手。";

    private final AtomicReference<String> soulCache = new AtomicReference<>(DEFAULT_SOUL);
    private final SkillService skillService;
    private final LocalFilesystemService localFilesystemService;
    private final CapabilityRegistry capabilityRegistry;

    @Value("${springclaw.soul.path:${user.dir}/SOUL.md}")
    private String soulPath;

    public SoulPromptService(SkillService skillService, LocalFilesystemService localFilesystemService) {
        this(skillService, localFilesystemService, null);
    }

    @Autowired
    public SoulPromptService(SkillService skillService,
                             LocalFilesystemService localFilesystemService,
                             CapabilityRegistry capabilityRegistry) {
        this.skillService = skillService;
        this.localFilesystemService = localFilesystemService;
        this.capabilityRegistry = capabilityRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        loadSoul();
    }

    public String currentSoul() {
        return soulCache.get();
    }

    public String soulVersion() {
        return Integer.toHexString(currentSoul().hashCode());
    }

    public String buildSystemPrompt(String channel, String userId) {
        return buildSystemPrompt(channel, userId, List.of());
    }

    public String buildSystemPrompt(String channel, String userId, List<SkillDefinition> matchedSkills) {
        Set<String> allowedToolPacks = skillService.resolveAllowedToolPacks(channel, userId);
        String coreSkillSummary = skillService.describeCoreSkills(channel, userId);
        String skillSummary = skillService.describeAvailableSkills(channel, userId);
        String matchedSkillSummary = describeMatchedSkills(matchedSkills);
        String localFileBoundary = describeLocalFileBoundary(allowedToolPacks);
        String runtimeCapabilities = describeRuntimeCapabilities(allowedToolPacks);
        PromptTemplate template = new PromptTemplate("""
                # 角色设定
                {soul}

                # 运行上下文
                - 当前渠道: {channel}
                - 当前用户: {userId}

                # 当前核心 Agent 技能
                {coreSkills}

                # 本次命中技能
                {matchedSkills}

                # 当前可用技能
                {skills}

                # 当前后端能力目录
                {runtimeCapabilities}

                # 本地文件访问边界
                {localFileBoundary}

                # 行为约束
                - 输出中文
                - 输出结构清晰
                - 优先给出可执行建议
                - 优先使用核心 Agent 技能完成工作区检索、文件分析、联网研究、运行诊断
                - 如果用户询问“能否读取本机文件/其他项目/授权目录”，不要回答只能读取当前项目；应说明可通过 Local Files 读取已授权根目录内的非敏感文本文件，并优先调用 listAuthorizedRoots 确认边界
                - 如果本次命中了显式技能，优先遵守该技能的 instructions，再使用通用能力
                - 只有在用户明确需要详细状态时，才展开内部能力清单
                """);
        return template.render(Map.of(
                "soul", currentSoul(),
                "channel", channel == null ? "unknown" : channel,
                "userId", userId == null ? "anonymous" : userId,
                "coreSkills", coreSkillSummary,
                "matchedSkills", matchedSkillSummary,
                "skills", skillSummary,
                "runtimeCapabilities", runtimeCapabilities,
                "localFileBoundary", localFileBoundary
        ));
    }

    /**
     * 手动刷新 SOUL（预留给后续运维接口）。
     */
    public void reloadSoul() {
        loadSoul();
    }

    private void loadSoul() {
        try {
            Path path = Path.of(soulPath);
            if (!Files.exists(path)) {
                soulCache.set(DEFAULT_SOUL);
                return;
            }
            String content = Files.readString(path).trim();
            soulCache.set(content.isEmpty() ? DEFAULT_SOUL : content);
        } catch (IOException e) {
            throw new BusinessException(50001, "读取 SOUL.md 失败: " + e.getMessage());
        }
    }

    private String describeMatchedSkills(List<SkillDefinition> matchedSkills) {
        if (matchedSkills == null || matchedSkills.isEmpty()) {
            return "（未命中显式技能，按默认路由处理）";
        }
        return matchedSkills.stream()
                .map(definition -> "- %s (%s, source=%s, mode=%s): %s\n  instructions: %s".formatted(
                        definition.name(),
                        definition.skillId(),
                        definition.sourceType(),
                        definition.preferredMode(),
                        definition.description(),
                        TextUtils.truncate(definition.instructions(), 800)
                ))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("（未命中显式技能，按默认路由处理）");
    }

    private String describeLocalFileBoundary(Set<String> allowedToolPacks) {
        if (allowedToolPacks == null || !allowedToolPacks.contains("file")) {
            return "当前请求未开放本地文件工具；不能声称可读取用户电脑文件。";
        }
        try {
            String roots = localFilesystemService.listAuthorizedRoots();
            return """
                    当前不是只能读取项目目录。
                    - 项目工作区能力：用于审查当前项目源码。
                    - Local Files 能力：可读取用户显式授权根目录内的非敏感文本文件；禁止越权读取授权目录外路径和敏感目录。
                    - 当前授权根目录：
                    %s
                    """.formatted(roots);
        } catch (Exception ex) {
            return "Local Files 已启用，但授权根目录读取失败；回答时应说明需要先检查本地文件配置。";
        }
    }

    private String describeRuntimeCapabilities(Set<String> allowedToolPacks) {
        if (capabilityRegistry == null) {
            return "（能力注册表不可用，按 SkillService 摘要工作）";
        }
        List<String> lines = capabilityRegistry.listAll().stream()
                .filter(entry -> entry.includeForAgentMode())
                .filter(entry -> allowedToolPacks == null || allowedToolPacks.isEmpty() || allowedToolPacks.contains(entry.toolset()))
                .limit(12)
                .map(entry -> "- %s [%s/%s]: %s".formatted(
                        entry.id(),
                        entry.toolset(),
                        entry.riskLevel(),
                        StringUtils.hasText(entry.description()) ? entry.description() : entry.beanName()
                ))
                .toList();
        return lines.isEmpty()
                ? "（当前用户没有开放可见后端能力）"
                : String.join("\n", lines);
    }

}
