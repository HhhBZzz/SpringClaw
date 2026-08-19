package com.springclaw.service.prompt;

import com.springclaw.service.skill.SkillDefinition;

import java.util.List;

/**
 * 一次系统提示词组装的请求上下文(传给各 PromptSection 的动态提供者)。
 * 各 section 从这里取渠道/用户/命中技能,不再各自穿透服务层。
 */
public record PromptSectionContext(String channel, String userId, List<SkillDefinition> matchedSkills) {
}
