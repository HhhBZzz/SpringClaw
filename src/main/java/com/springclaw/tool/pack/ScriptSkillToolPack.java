package com.springclaw.tool.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.service.skill.script.ScriptSkillCatalogService;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.util.Map;

/**
 * 脚本技能工具包（受控执行）。
 *
 * 设计说明：
 * 1. 借鉴 SpringClaw4J 的混合技能思路，在 Java 主链路中接入 Python 技能。
 * 2. 通过“目录沙箱 + 技能白名单 + 超时 + 输出截断”控制风险，避免命令注入。
 */
@Component
public class ScriptSkillToolPack {

    private final boolean enabled;
    private final ScriptSkillCatalogService scriptSkillCatalogService;
    private final ScriptSkillExecutorService scriptSkillExecutorService;

    @Autowired
    public ScriptSkillToolPack(@Value("${springclaw.skills.enabled:true}") boolean enabled,
                               ScriptSkillCatalogService scriptSkillCatalogService,
                               ScriptSkillExecutorService scriptSkillExecutorService) {
        this.enabled = enabled;
        this.scriptSkillCatalogService = scriptSkillCatalogService;
        this.scriptSkillExecutorService = scriptSkillExecutorService;
    }

    public ScriptSkillToolPack(boolean enabled,
                               ScriptSkillCatalogService scriptSkillCatalogService,
                               String pythonCommand,
                               int timeoutSeconds,
                               int maxOutputChars,
                               ObjectMapper objectMapper) {
        this(enabled,
                scriptSkillCatalogService,
                new ScriptSkillExecutorService(
                        enabled,
                        scriptSkillCatalogService,
                        pythonCommand,
                        timeoutSeconds,
                        maxOutputChars,
                        objectMapper
                ));
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
        return scriptSkillExecutorService.runScriptSkill(skillName, args);
    }

    public String runScriptSkill(String skillName, String argsJson) {
        if (!enabled) {
            return "脚本技能未开启（springclaw.skills.enabled=false）";
        }
        return scriptSkillExecutorService.runScriptSkill(skillName, argsJson);
    }

    @Tool(description = "按自然语言目标执行脚本技能。参数：skillName（技能名），goal（自然语言目标）")
    public String runScriptSkillByGoal(String skillName, String goal) {
        if (!enabled) {
            return "脚本技能未开启（springclaw.skills.enabled=false）";
        }
        return scriptSkillExecutorService.runScriptSkillByGoal(skillName, goal);
    }
}
