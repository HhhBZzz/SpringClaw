package com.springclaw.service.skill;

import java.util.Set;

/**
 * Skill 运行时服务。
 */
public interface SkillService {

    /**
     * 解析当前请求允许使用的工具包。
     */
    Set<String> resolveAllowedToolPacks(String channel, String userId);

    /**
     * 生成可注入到系统提示词中的技能说明。
     */
    String describeAvailableSkills(String channel, String userId);

    /**
     * 生成面向 Agent 提示词的核心技能摘要。
     */
    String describeCoreSkills(String channel, String userId);
}
