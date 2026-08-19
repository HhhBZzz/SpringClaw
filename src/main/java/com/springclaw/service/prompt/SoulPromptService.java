package com.springclaw.service.prompt;

import com.springclaw.common.util.TextUtils;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.files.LocalFilesystemService;
import com.springclaw.service.skill.SkillDefinition;
import com.springclaw.service.skill.SkillService;
import com.springclaw.tool.runtime.CapabilityRegistry;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SOUL 人格提示词服务。
 *
 * 设计说明：
 * 1. 通过 ApplicationRunner 在应用启动时加载 SOUL.md 到内存，避免每次请求都走磁盘 IO。
 * 2. 系统提示词由 PromptSectionRegistry 组合(dsh PromptSection 参照):
 *    8 个段落各自注册(name/title/order/动态提供者),不再是一整块固定模板;
 *    新增段落=注册新 section,不动既有段落。SOUL 正文本身是 soul section 的动态体。
 * 3. 对外 buildSystemPrompt 签名与产出结构保持不变(调用方零改动)。
 */
@Service
public class SoulPromptService implements ApplicationRunner {

    private static final String DEFAULT_SOUL = "你是 SpringClaw-Java，一个专业、稳健的企业级 AI Agent 助手。";

    private final AtomicReference<String> soulCache = new AtomicReference<>(DEFAULT_SOUL);
    private final SkillService skillService;
    private final LocalFilesystemService localFilesystemService;
    private final CapabilityRegistry capabilityRegistry;
    private final PromptSectionRegistry sectionRegistry = new PromptSectionRegistry();

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
        registerSections();
    }

    private void registerSections() {
        sectionRegistry.register(PromptSection.of("soul", "角色设定", 10,
                ctx -> currentSoul()));
        sectionRegistry.register(PromptSection.of("runtime-context", "运行上下文", 20, ctx ->
                "- 当前渠道: %s\n- 当前用户: %s".formatted(
                        ctx.channel() == null ? "unknown" : ctx.channel(),
                        ctx.userId() == null ? "anonymous" : ctx.userId())));
        sectionRegistry.register(PromptSection.of("core-skills", "当前核心 Agent 技能", 30, ctx ->
                skillService.describeCoreSkills(ctx.channel(), ctx.userId())));
        sectionRegistry.register(PromptSection.of("matched-skills", "本次命中技能", 40, ctx ->
                describeMatchedSkills(ctx.matchedSkills())));
        sectionRegistry.register(PromptSection.of("skills", "当前可用技能", 50, ctx ->
                skillService.describeAvailableSkills(ctx.channel(), ctx.userId())));
        sectionRegistry.register(PromptSection.of("runtime-capabilities", "当前后端能力目录", 60, ctx ->
                describeRuntimeCapabilities(skillService.resolveAllowedToolPacks(ctx.channel(), ctx.userId()))));
        sectionRegistry.register(PromptSection.of("local-file-boundary", "本地文件访问边界", 70, ctx ->
                describeLocalFileBoundary(skillService.resolveAllowedToolPacks(ctx.channel(), ctx.userId()))));
        sectionRegistry.register(PromptSection.of("behavior", "行为约束", 80, ctx -> """
                - 输出中文
                - 输出结构清晰
                - 优先给出可执行建议
                - 优先使用核心 Agent 技能完成工作区检索、文件分析、联网研究、运行诊断
                - 如果用户询问“能否读取本机文件/其他项目/授权目录”，不要回答只能读取当前项目；应说明可通过 Local Files 读取已授权根目录内的非敏感文本文件，并优先调用 listAuthorizedRoots 确认边界
                - 如果本次命中了显式技能，优先遵守该技能的 instructions，再使用通用能力
                - 只有在用户明确需要详细状态时，才展开内部能力清单
                """));
    }

    /** 已注册 section 名(按渲染顺序),供诊断/运维接口展示。 */
    public List<String> sectionNames() {
        return sectionRegistry.sectionNames();
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
        return sectionRegistry.render(new PromptSectionContext(channel, userId, matchedSkills));
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
