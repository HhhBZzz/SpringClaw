package com.springclaw.service.skill;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 统一 skill 定义。
 * 目标：把 builtin、script、后续 markdown skill 收敛到同一个运行时模型。
 */
public record SkillDefinition(String skillId,
                              String name,
                              String description,
                              String sourceType,
                              String sourceRef,
                              String instructions,
                              List<String> triggerKeywords,
                              List<String> triggerExamples,
                              List<String> toolPacks,
                              String preferredMode,
                              String contextPolicy,
                              String executorType,
                              String executorRef,
                              boolean enabled,
                              int priority,
                              boolean agentVisible) {

    public boolean matchesAllowedToolPacks(Set<String> allowedToolPacks) {
        if (toolPacks == null || toolPacks.isEmpty()) {
            return true;
        }
        if (allowedToolPacks == null || allowedToolPacks.isEmpty()) {
            return false;
        }
        for (String toolPack : toolPacks) {
            if (StringUtils.hasText(toolPack)
                    && allowedToolPacks.contains(toolPack.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
