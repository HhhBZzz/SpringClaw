package com.springclaw.service.chat;

import com.springclaw.service.skill.SkillDefinition;

import java.util.Optional;

/**
 * Handler for a single Java builtin skill.
 */
public interface BuiltinSkillHandler {

    String skillId();

    Optional<LocalSkillFallbackService.LocalSkillResult> execute(SkillDefinition definition, String question);
}
