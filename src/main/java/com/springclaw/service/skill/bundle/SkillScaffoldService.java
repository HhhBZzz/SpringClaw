package com.springclaw.service.skill.bundle;

import com.springclaw.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 安全创建 skill 目录模板。
 *
 * 这里只做模板生成，不开放任意 patch/delete，避免把 agent 变成无限制文件编辑器。
 */
@Service
public class SkillScaffoldService {

    private final boolean enabled;
    private final SkillCatalogService skillCatalogService;

    public SkillScaffoldService(@Value("${springclaw.skills.scaffold-enabled:true}") boolean enabled,
                                       SkillCatalogService skillCatalogService) {
        this.enabled = enabled;
        this.skillCatalogService = skillCatalogService;
    }

    public String createPythonSkillTemplate(String skillId,
                                            String displayName,
                                            String description,
                                            String category) {
        if (!enabled) {
            return "skill 模板创建未开启（springclaw.skills.scaffold-enabled=false）";
        }
        String slug = SkillBundleSupport.sanitizeSlug(skillId);
        if (!StringUtils.hasText(slug) || !slug.matches("^[a-z0-9][a-z0-9._-]{1,62}[a-z0-9]$")) {
            throw new BusinessException(40098, "skillId 非法，仅支持 3-64 位小写字母、数字、点、下划线和短横线");
        }
        Path root = skillCatalogService.rootPath().toAbsolutePath().normalize();
        Path skillDir = root.resolve(slug).normalize();
        if (!skillDir.startsWith(root)) {
            throw new BusinessException(40095, "skill 模板路径越界");
        }
        if (Files.exists(skillDir)) {
            throw new BusinessException(40931, "skill 已存在: " + slug);
        }
        try {
            Files.createDirectories(skillDir.resolve("scripts"));
            Files.createDirectories(skillDir.resolve("references"));
            Files.createDirectories(skillDir.resolve("templates"));
            Files.createDirectories(skillDir.resolve("assets"));
            Files.writeString(skillDir.resolve(SkillBundleSupport.SKILL_FILE_NAME),
                    buildSkillMarkdown(slug, displayName, description, category),
                    StandardCharsets.UTF_8);
            Files.writeString(skillDir.resolve("scripts/run.py"), buildPythonEntrypoint(), StandardCharsets.UTF_8);
            Files.writeString(skillDir.resolve("references/README.md"),
                    "# References\n\n把这个 skill 需要长期参考的说明、字段定义或示例放在这里。\n",
                    StandardCharsets.UTF_8);
            return "已创建 Python skill 模板: " + slug + "\n目录: " + skillDir;
        } catch (IOException ex) {
            throw new BusinessException(50095, "创建 skill 模板失败: " + ex.getMessage());
        }
    }

    private String buildSkillMarkdown(String skillId,
                                      String displayName,
                                      String description,
                                      String category) {
        String safeName = limit(firstNonBlank(displayName, skillId), 80);
        String safeDescription = limit(firstNonBlank(description, safeName + " skill"), 240);
        String safeCategory = SkillBundleSupport.sanitizeSlug(firstNonBlank(category, "custom"));
        if (!StringUtils.hasText(safeCategory)) {
            safeCategory = "custom";
        }
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put("name", safeName);
        frontmatter.put("description", safeDescription);
        frontmatter.put("skillId", skillId);
        frontmatter.put("type", "python");
        frontmatter.put("entrypoint", "scripts/run.py");
        frontmatter.put("category", safeCategory);
        frontmatter.put("tier", "utility");
        frontmatter.put("inputHint", "传入 goal 或 JSON 参数，脚本从 sys.argv[1] 读取。");
        frontmatter.put("priority", 100);
        frontmatter.put("enabled", true);
        frontmatter.put("agentVisible", true);

        String body = """
                # %s

                ## Description
                %s

                ## How To Use
                1. 在 `scripts/run.py` 里实现具体逻辑。
                2. 复杂说明放进 `references/`。
                3. 示例输入、模板文件放进 `templates/`。

                ## Safety
                默认只创建模板，不自动授予高危系统权限。
                """.formatted(safeName, safeDescription);
        return SkillBundleSupport.renderMarkdown(frontmatter, body);
    }

    private String buildPythonEntrypoint() {
        return """
                import json
                import os
                import sys


                def main():
                    payload = json.loads(sys.argv[1]) if len(sys.argv) > 1 and sys.argv[1].strip() else {}
                    goal = payload.get("goal") or payload.get("query") or ""
                    skill_name = os.environ.get("SPRINGCLAW_SKILL_NAME") or os.environ.get("OPENCLAW_SKILL_NAME") or "custom"
                    print(json.dumps({
                        "skill": skill_name,
                        "goal": goal,
                        "message": "请在 scripts/run.py 中实现你的自定义逻辑"
                    }, ensure_ascii=False))


                if __name__ == "__main__":
                    main()
                """;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String limit(String value, int maxLength) {
        String safe = value == null ? "" : value.replace("\r", " ").replace("\n", " ").trim();
        return safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }
}
