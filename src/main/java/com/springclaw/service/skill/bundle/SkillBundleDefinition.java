package com.springclaw.service.skill.bundle;

import com.springclaw.service.skill.SkillDefinition;

import java.nio.file.Path;
import java.util.List;

/**
 * skill 目录的统一解析结果。
 */
public record SkillBundleDefinition(String skillId,
                                    String slug,
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
                                    boolean agentVisible,
                                    String category,
                                    String tier,
                                    String inputHint,
                                    Path bundlePath,
                                    Path skillPath,
                                    Path entrypointPath) {

    public SkillDefinition toRuntimeDefinition() {
        return new SkillDefinition(
                skillId,
                name,
                description,
                sourceType,
                sourceRef,
                instructions,
                triggerKeywords,
                triggerExamples,
                toolPacks,
                preferredMode,
                contextPolicy,
                executorType,
                executorRef,
                enabled,
                priority,
                agentVisible
        );
    }
}
