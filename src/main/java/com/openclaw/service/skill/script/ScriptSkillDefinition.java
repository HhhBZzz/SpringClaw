package com.openclaw.service.skill.script;

import java.nio.file.Path;
import java.util.List;

/**
 * 脚本技能定义。
 */
public record ScriptSkillDefinition(
        String skillName,
        String displayName,
        String category,
        String tier,
        String description,
        String inputHint,
        List<String> keywords,
        List<String> exampleQuestions,
        int priority,
        boolean visibleToAgent,
        Path scriptPath
) {
}
