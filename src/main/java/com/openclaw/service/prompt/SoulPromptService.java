package com.openclaw.service.prompt;

import com.openclaw.common.exception.BusinessException;
import com.openclaw.service.skill.SkillDefinition;
import com.openclaw.service.skill.SkillService;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

    private static final String DEFAULT_SOUL = "你是 OpenClaw-Java，一个专业、稳健的企业级 AI Agent 助手。";

    private final AtomicReference<String> soulCache = new AtomicReference<>(DEFAULT_SOUL);
    private final SkillService skillService;

    @Value("${openclaw.soul.path:${user.dir}/SOUL.md}")
    private String soulPath;

    public SoulPromptService(SkillService skillService) {
        this.skillService = skillService;
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
        String coreSkillSummary = skillService.describeCoreSkills(channel, userId);
        String skillSummary = skillService.describeAvailableSkills(channel, userId);
        String matchedSkillSummary = describeMatchedSkills(matchedSkills);
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

                # 行为约束
                - 输出中文
                - 输出结构清晰
                - 优先给出可执行建议
                - 优先使用核心 Agent 技能完成工作区检索、文件分析、联网研究、运行诊断
                - 如果本次命中了显式技能，优先遵守该技能的 instructions，再使用通用能力
                - 只有在用户明确需要详细状态时，才展开内部能力清单
                """);
        return template.render(Map.of(
                "soul", currentSoul(),
                "channel", channel == null ? "unknown" : channel,
                "userId", userId == null ? "anonymous" : userId,
                "coreSkills", coreSkillSummary,
                "matchedSkills", matchedSkillSummary,
                "skills", skillSummary
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
                        truncate(definition.instructions(), 800)
                ))
                .reduce((left, right) -> left + "\n" + right)
                .orElse("（未命中显式技能，按默认路由处理）");
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLen) + "...";
    }
}
