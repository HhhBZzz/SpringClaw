package com.springclaw.tool.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.tool.runtime.ToolPackDescriptor;
import com.springclaw.service.skill.bundle.SkillScaffoldService;
import com.springclaw.service.skill.bundle.SkillCatalogService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.skill.runtime.SkillRuntimeService;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
import com.springclaw.service.skill.script.ScriptSkillDefinition;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 脚本技能工具包（受控执行）。
 *
 * 设计说明：
 * 1. 借鉴 SpringClaw4J 的混合技能思路，在 Java 主链路中接入 Python 技能。
 * 2. 通过“目录沙箱 + 技能白名单 + 超时 + 输出截断”控制风险，避免命令注入。
 */
@Component
@ToolPackDescriptor(
    id = "script-skill",
    toolset = "script",
    triggerKeywords = {"脚本", "skill", "执行技能", "python", "run skill", "脚本技能"},
    fallbackCandidate = true,
    riskLevel = "execution",
    description = "执行受控 Python 脚本技能"
)
public class ScriptSkillToolPack {

    private static final int MAX_SKILL_MARKDOWN_CHARS = 6000;
    private static final int MAX_SUPPORTING_FILES = 60;

    private final boolean enabled;
    private final ScriptSkillCatalogService scriptSkillCatalogService;
    private final SkillRuntimeService skillRuntimeService;
    private final ScriptSkillExecutorService scriptSkillExecutorService;
    private final SkillScaffoldService skillScaffoldService;
    private final ObjectMapper objectMapper;

    @Autowired
    public ScriptSkillToolPack(@Value("${springclaw.skills.enabled:true}") boolean enabled,
                               ScriptSkillCatalogService scriptSkillCatalogService,
                               SkillRuntimeService skillRuntimeService,
                               ScriptSkillExecutorService scriptSkillExecutorService,
                               SkillScaffoldService skillScaffoldService) {
        this.enabled = enabled;
        this.scriptSkillCatalogService = scriptSkillCatalogService;
        this.skillRuntimeService = skillRuntimeService;
        this.scriptSkillExecutorService = scriptSkillExecutorService;
        this.skillScaffoldService = skillScaffoldService;
        this.objectMapper = new ObjectMapper();
    }

    public ScriptSkillToolPack(boolean enabled,
                               ScriptSkillCatalogService scriptSkillCatalogService,
                               SkillRuntimeService skillRuntimeService,
                               SkillScaffoldService skillScaffoldService) {
        this.enabled = enabled;
        this.scriptSkillCatalogService = scriptSkillCatalogService;
        this.skillRuntimeService = skillRuntimeService;
        this.scriptSkillExecutorService = null;
        this.skillScaffoldService = skillScaffoldService;
        this.objectMapper = new ObjectMapper();
    }

    public ScriptSkillToolPack(boolean enabled,
                               ScriptSkillCatalogService scriptSkillCatalogService,
                               String pythonCommand,
                               int timeoutSeconds,
                               int maxOutputChars,
                               ObjectMapper objectMapper) {
        this(enabled,
                scriptSkillCatalogService,
                createRuntime(enabled, scriptSkillCatalogService, pythonCommand, timeoutSeconds, maxOutputChars, objectMapper),
                createExecutor(enabled, scriptSkillCatalogService, pythonCommand, timeoutSeconds, maxOutputChars, objectMapper),
                null);
    }

    @Tool(description = "列出当前可用的脚本技能（skills 目录）")
    public String listScriptSkills() {
        if (!enabled) {
            return "脚本技能未开启（springclaw.skills.enabled=false）";
        }
        if (!Files.exists(scriptSkillCatalogService.rootPath()) || !Files.isDirectory(scriptSkillCatalogService.rootPath())) {
            return "脚本目录不存在: " + scriptSkillCatalogService.rootPath();
        }
        return scriptSkillCatalogService.describeForTool();
    }

    @Tool(description = "执行脚本技能。参数：skillName（技能名），args（键值对参数）")
    public String runScriptSkill(String skillName, Map<String, String> args) {
        if (!enabled) {
            return "脚本技能未开启（springclaw.skills.enabled=false）";
        }
        try {
            return runScriptSkill(skillName, objectMapper.writeValueAsString(args == null ? Map.of() : args));
        } catch (Exception ex) {
            throw new BusinessException(50094, "脚本参数序列化失败: " + ex.getMessage());
        }
    }

    public String runScriptSkill(String skillName, String argsJson) {
        if (!enabled) {
            return "脚本技能未开启（springclaw.skills.enabled=false）";
        }
        return skillRuntimeService.executeBySkillId(skillName, argsJson, java.util.Set.of("script"));
    }

    @Tool(description = "按自然语言目标执行脚本技能。参数：skillName（技能名），goal（自然语言目标）")
    public String runScriptSkillByGoal(String skillName, String goal) {
        if (!enabled) {
            return "脚本技能未开启（springclaw.skills.enabled=false）";
        }
        return skillRuntimeService.executeBySkillId(skillName, goal, java.util.Set.of("script"));
    }

    public String executeSkillByGoal(String skillName, String goal) {
        return runScriptSkillByGoal(skillName, goal);
    }

    @Tool(description = "查看某个脚本技能的完整说明和支持文件列表。参数：skillName（技能名）")
    public String inspectScriptSkill(String skillName) {
        if (!enabled) {
            return "脚本技能未开启（springclaw.skills.enabled=false）";
        }
        return scriptSkillCatalogService.findDefinition(skillName)
                .map(this::renderSkillInspection)
                .orElse("脚本技能不存在或未授权: " + skillName);
    }

    @Tool(description = "按顺序执行多个脚本技能，并把上一步结果传给下一步。参数：skillNames（如 a -> b），goal（目标）")
    public String runScriptSkillChain(String skillNames, String goal) {
        if (!enabled) {
            return "脚本技能未开启（springclaw.skills.enabled=false）";
        }
        if (scriptSkillExecutorService == null) {
            return "脚本技能链执行器未配置";
        }
        return scriptSkillExecutorService.runScriptSkillChain(skillNames, goal);
    }

    private static SkillRuntimeService createRuntime(boolean enabled,
                                                     ScriptSkillCatalogService scriptSkillCatalogService,
                                                     String pythonCommand,
                                                     int timeoutSeconds,
                                                     int maxOutputChars,
                                                     ObjectMapper objectMapper) {
        SkillRegistryService skillRegistryService = new SkillRegistryService(
                new SkillCatalogService(enabled, scriptSkillCatalogService.rootPath().toString())
        );
        return new SkillRuntimeService(
                createExecutor(enabled, scriptSkillCatalogService, pythonCommand, timeoutSeconds, maxOutputChars, objectMapper),
                null,
                skillRegistryService
        );
    }

    private static ScriptSkillExecutorService createExecutor(boolean enabled,
                                                             ScriptSkillCatalogService scriptSkillCatalogService,
                                                             String pythonCommand,
                                                             int timeoutSeconds,
                                                             int maxOutputChars,
                                                             ObjectMapper objectMapper) {
        return new ScriptSkillExecutorService(
                enabled,
                scriptSkillCatalogService,
                pythonCommand,
                timeoutSeconds,
                maxOutputChars,
                objectMapper
        );
    }

    @Tool(description = "创建一个安全的 Python skill 模板。参数：skillId、displayName、description、category")
    public String createPythonSkillTemplate(String skillId,
                                            String displayName,
                                            String description,
                                            String category) {
        if (!enabled) {
            return "脚本技能未开启（springclaw.skills.enabled=false）";
        }
        if (skillScaffoldService == null) {
            return "skill 模板服务未配置";
        }
        return skillScaffoldService.createPythonSkillTemplate(skillId, displayName, description, category);
    }

    private String renderSkillInspection(ScriptSkillDefinition definition) {
        Path skillRoot = definition.skillRootPath().toAbsolutePath().normalize();
        Path skillMarkdown = skillRoot.resolve("SKILL.md").normalize();
        List<String> lines = new ArrayList<>();
        lines.add("skill=" + definition.skillName());
        lines.add("名称: " + definition.displayName());
        lines.add("分类: " + definition.category() + ", tier=" + definition.tier());
        lines.add("描述: " + definition.description());
        lines.add("入口: " + relative(skillRoot, definition.scriptPath()));
        lines.add("");
        lines.add("SKILL.md:");
        lines.add(readTextIfSafe(skillRoot, skillMarkdown, MAX_SKILL_MARKDOWN_CHARS));
        List<String> supportingFiles = listSupportingFiles(skillRoot);
        if (!supportingFiles.isEmpty()) {
            lines.add("");
            lines.add("支持文件:");
            supportingFiles.forEach(path -> lines.add("- " + path));
        }
        return String.join("\n", lines);
    }

    private List<String> listSupportingFiles(Path skillRoot) {
        List<String> results = new ArrayList<>();
        for (String dirName : List.of("references", "templates", "scripts", "assets")) {
            Path dir = skillRoot.resolve(dirName).normalize();
            if (!Files.isDirectory(dir) || !dir.startsWith(skillRoot)) {
                continue;
            }
            try (var stream = Files.walk(dir, 4)) {
                stream.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(path -> relative(skillRoot, path)))
                        .limit(MAX_SUPPORTING_FILES)
                        .map(path -> relative(skillRoot, path))
                        .forEach(results::add);
            } catch (IOException ignored) {
                // 单个支持目录读不到时，不影响 skill 主体说明。
            }
        }
        return results.stream().distinct().limit(MAX_SUPPORTING_FILES).toList();
    }

    private String readTextIfSafe(Path root, Path file, int maxChars) {
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return "(未找到)";
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.length() <= maxChars) {
                return text.trim();
            }
            return text.substring(0, maxChars).trim() + "\n...<TRUNCATED>";
        } catch (IOException ex) {
            return "(读取失败: " + ex.getMessage() + ")";
        }
    }

    private String relative(Path root, Path path) {
        if (path == null) {
            return "";
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return normalized.toString();
        }
        String value = root.relativize(normalized).toString().replace('\\', '/');
        return StringUtils.hasText(value) ? value : ".";
    }
}
