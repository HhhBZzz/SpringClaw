package com.openclaw.service.skill.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openclaw.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 脚本技能执行器。
 *
 * 说明：
 * 1. 统一封装 Python skill 的真实执行逻辑，避免内部 builtin/local skill 也必须绕过 @Tool 才能运行。
 * 2. ToolPack 仍可复用这层执行器暴露给模型，内部路由也可以直接调用，职责更清楚。
 */
@Service
public class ScriptSkillExecutorService {

    private final boolean enabled;
    private final ScriptSkillCatalogService scriptSkillCatalogService;
    private final String pythonCommand;
    private final int timeoutSeconds;
    private final int maxOutputChars;
    private final ObjectMapper objectMapper;

    public ScriptSkillExecutorService(@Value("${openclaw.tools.script.enabled:false}") boolean enabled,
                                      ScriptSkillCatalogService scriptSkillCatalogService,
                                      @Value("${openclaw.tools.script.python-command:python3}") String pythonCommand,
                                      @Value("${openclaw.tools.script.timeout-seconds:8}") int timeoutSeconds,
                                      @Value("${openclaw.tools.script.max-output-chars:3000}") int maxOutputChars,
                                      ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.scriptSkillCatalogService = scriptSkillCatalogService;
        this.pythonCommand = StringUtils.hasText(pythonCommand) ? pythonCommand.trim() : "python3";
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.maxOutputChars = Math.max(256, maxOutputChars);
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        return enabled;
    }

    public String runScriptSkill(String skillName, Map<String, String> args) {
        if (!enabled) {
            return "脚本技能未开启（openclaw.tools.script.enabled=false）";
        }
        return execute(resolveDefinition(normalizeSkillName(skillName)), writePayload(normalizeArgs(args)));
    }

    public String runScriptSkill(String skillName, String argsJson) {
        if (!enabled) {
            return "脚本技能未开启（openclaw.tools.script.enabled=false）";
        }
        return execute(resolveDefinition(normalizeSkillName(skillName)),
                StringUtils.hasText(argsJson) ? argsJson.trim() : "{}");
    }

    public String runScriptSkillByGoal(String skillName, String goal) {
        if (!enabled) {
            return "脚本技能未开启（openclaw.tools.script.enabled=false）";
        }
        String normalizedSkill = normalizeSkillName(skillName);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("goal", StringUtils.hasText(goal) ? goal.trim() : "请执行默认任务");
        payload.put("workspaceRoot", Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString());
        payload.put("skillName", normalizedSkill);
        return execute(resolveDefinition(normalizedSkill), writePayload(payload));
    }

    private String execute(ScriptSkillDefinition definition, String safeArgs) {
        Path script = definition.scriptPath().toAbsolutePath().normalize();
        if (!java.nio.file.Files.exists(script) || !java.nio.file.Files.isRegularFile(script)) {
            throw new BusinessException(40431, "脚本不存在: " + definition.skillName());
        }
        if (!script.startsWith(scriptSkillCatalogService.rootPath())) {
            throw new BusinessException(40095, "脚本路径越界");
        }
        ProcessBuilder pb = new ProcessBuilder(pythonCommand, script.toString(), safeArgs);
        pb.directory(scriptSkillCatalogService.rootPath().toFile());
        pb.redirectErrorStream(true);
        pb.environment().put("OPENCLAW_WORKSPACE_ROOT", Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString());
        pb.environment().put("OPENCLAW_SCRIPT_ROOT", scriptSkillCatalogService.rootPath().toString());
        pb.environment().put("OPENCLAW_SKILL_NAME", definition.skillName());

        try {
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "脚本执行超时（" + timeoutSeconds + "s）: " + definition.skillName();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (output.length() > maxOutputChars) {
                output = output.substring(0, maxOutputChars) + "\n...<TRUNCATED>";
            }
            String header = "skill=" + definition.skillName() + ", exitCode=" + process.exitValue();
            if (!StringUtils.hasText(output)) {
                return header + "\n(无输出)";
            }
            return header + "\n" + output;
        } catch (IOException ex) {
            throw new BusinessException(50092, "脚本执行失败: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(50093, "脚本执行被中断");
        }
    }

    private ScriptSkillDefinition resolveDefinition(String skillName) {
        return scriptSkillCatalogService.findDefinition(skillName)
                .orElseThrow(() -> new BusinessException(40331, "脚本技能未授权或不存在: " + skillName));
    }

    private String normalizeSkillName(String skillName) {
        if (!StringUtils.hasText(skillName)) {
            throw new BusinessException(40096, "skillName 不能为空");
        }
        String value = skillName.trim();
        if (!value.matches("^[a-zA-Z0-9_-]{1,64}$")) {
            throw new BusinessException(40097, "skillName 非法，仅支持字母数字_-");
        }
        return value;
    }

    private String writePayload(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(50094, "脚本参数序列化失败: " + ex.getMessage());
        }
    }

    private Map<String, Object> normalizeArgs(Map<String, String> args) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (args == null || args.isEmpty()) {
            return payload;
        }
        for (Map.Entry<String, String> entry : args.entrySet()) {
            if (!StringUtils.hasText(entry.getKey())) {
                continue;
            }
            payload.put(entry.getKey().trim(), entry.getValue() == null ? "" : entry.getValue().trim());
        }
        return payload;
    }
}
