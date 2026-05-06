package com.springclaw.service.skill.bundle;

/**
 * Hermes 风格 skill 使用侧写。
 *
 * 运行态统计放在 sidecar 文件里，不写回用户维护的 SKILL.md。
 */
public record SkillUsageRecord(String skillId,
                               int useCount,
                               int viewCount,
                               int patchCount,
                               String lastUsedAt,
                               String lastViewedAt,
                               String lastPatchedAt,
                               String createdAt,
                               String state,
                               boolean pinned,
                               String createdBy) {
}
