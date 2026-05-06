package com.springclaw.service.skill.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.skill.bundle.SkillUsageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * script skill 执行器。
 */
@Service
public class ScriptSkillExecutorService {

    private final boolean enabled;
    private final ScriptSkillCatalogService scriptSkillCatalogService;
    private final String pythonCommand;
    private final int timeoutSeconds;
    private final int maxOutputChars;
    private final ObjectMapper objectMapper;
    private final SkillUsageService skillUsageService;

    @Autowired
    public ScriptSkillExecutorService(@Value("${springclaw.skills.enabled:true}") boolean enabled,
                                      ScriptSkillCatalogService scriptSkillCatalogService,
                                      @Value("${springclaw.skills.python:python3}") String pythonCommand,
                                      @Value("${springclaw.skills.timeout-seconds:8}") int timeoutSeconds,
                                      @Value("${springclaw.skills.max-output-chars:3000}") int maxOutputChars,
                                      ObjectMapper objectMapper,
                                      SkillUsageService skillUsageService) {
        this.enabled = enabled;
        this.scriptSkillCatalogService = scriptSkillCatalogService;
        this.pythonCommand = StringUtils.hasText(pythonCommand) ? pythonCommand.trim() : "python3";
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.maxOutputChars = Math.max(256, maxOutputChars);
        this.objectMapper = objectMapper;
        this.skillUsageService = skillUsageService;
    }

    public ScriptSkillExecutorService(boolean enabled,
                                      ScriptSkillCatalogService scriptSkillCatalogService,
                                      String pythonCommand,
                                      int timeoutSeconds,
                                      int maxOutputChars,
                                      ObjectMapper objectMapper) {
        this(enabled, scriptSkillCatalogService, pythonCommand, timeoutSeconds, maxOutputChars, objectMapper, null);
    }

    public boolean enabled() {
        return enabled;
    }

    public String runScriptSkill(String skillName, Map<String, String> args) {
        if (!enabled) {
            return "脚本技能未开启（springclaw.skills.enabled=false）";
        }
        return execute(resolveDefinition(normalizeSkillName(skillName)), writePayload(normalizeArgs(args)));
    }

    public String runScriptSkill(String skillName, String argsJson) {
        if (!enabled) {
            return "脚本技能未开启（springclaw.skills.enabled=false）";
        }
        return execute(resolveDefinition(normalizeSkillName(skillName)),
                StringUtils.hasText(argsJson) ? argsJson.trim() : "{}");
    }

    public String runScriptSkillByGoal(String skillName, String goal) {
        if (!enabled) {
            return "脚本技能未开启（springclaw.skills.enabled=false）";
        }
        String normalizedSkill = normalizeSkillName(skillName);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("goal", StringUtils.hasText(goal) ? goal.trim() : "请执行默认任务");
        payload.put("workspaceRoot", Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString());
        payload.put("skillName", normalizedSkill);
        return execute(resolveDefinition(normalizedSkill), writePayload(payload));
    }

    public String runScriptSkillChain(String skillNames, String goal) {
        if (!enabled) {
            return "脚本技能未开启（springclaw.skills.enabled=false）";
        }
        List<String> chain = parseSkillChain(skillNames);
        if (chain.isEmpty()) {
            throw new BusinessException(40098, "skill 链不能为空");
        }
        if (chain.size() > 6) {
            throw new BusinessException(40099, "skill 链最多支持 6 个步骤");
        }
        String normalizedGoal = StringUtils.hasText(goal) ? goal.trim() : "请执行默认任务";
        String previousResult = "";
        List<String> outputs = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            String skillName = chain.get(i);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("goal", normalizedGoal);
            payload.put("originalGoal", normalizedGoal);
            payload.put("skillName", skillName);
            payload.put("chainStep", i + 1);
            payload.put("chainTotal", chain.size());
            payload.put("workspaceRoot", workspaceRoot());
            if (StringUtils.hasText(previousResult)) {
                payload.put("previousResult", previousResult);
            }
            String output = execute(resolveDefinition(skillName), writePayload(payload));
            outputs.add("## " + (i + 1) + ". " + skillName + "\n" + output);
            previousResult = output;
        }
        return "skillChain=" + String.join(" -> ", chain) + "\n\n" + String.join("\n\n", outputs);
    }

    private String execute(ScriptSkillDefinition definition, String safeArgs) {
        Path script = definition.scriptPath().toAbsolutePath().normalize();
        Path skillRoot = definition.skillRootPath().toAbsolutePath().normalize();
        if (!java.nio.file.Files.exists(script) || !java.nio.file.Files.isRegularFile(script)) {
            throw new BusinessException(40431, "脚本不存在: " + definition.skillName());
        }
        if (!script.startsWith(scriptSkillCatalogService.rootPath()) || !skillRoot.startsWith(scriptSkillCatalogService.rootPath())) {
            throw new BusinessException(40095, "脚本路径越界");
        }
        ProcessBuilder pb = new ProcessBuilder(pythonCommand, script.toString(), safeArgs);
        pb.directory(skillRoot.toFile());
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        String workspaceRoot = workspaceRoot();
        String scriptRoot = scriptSkillCatalogService.rootPath().toString();
        env.put("SPRINGCLAW_WORKSPACE_ROOT", workspaceRoot);
        env.put("SPRINGCLAW_SCRIPT_ROOT", scriptRoot);
        env.put("SPRINGCLAW_SKILL_ROOT", skillRoot.toString());
        env.put("SPRINGCLAW_SKILL_NAME", definition.skillName());
        env.put("OPENCLAW_WORKSPACE_ROOT", workspaceRoot);
        env.put("OPENCLAW_SCRIPT_ROOT", scriptRoot);
        env.put("OPENCLAW_SKILL_ROOT", skillRoot.toString());
        env.put("OPENCLAW_SKILL_NAME", definition.skillName());
        List<String> pythonPathEntries = new ArrayList<>();
        pythonPathEntries.add(scriptSkillCatalogService.rootPath().toString());
        Path sharedLibRoot = scriptSkillCatalogService.rootPath().resolve("_shared").normalize();
        if (sharedLibRoot.startsWith(scriptSkillCatalogService.rootPath())) {
            pythonPathEntries.add(sharedLibRoot.toString());
        }
        Path legacySharedLibRoot = scriptSkillCatalogService.rootPath().getParent();
        if (legacySharedLibRoot != null) {
            pythonPathEntries.add(legacySharedLibRoot.toString());
        }
        String existingPythonPath = env.getOrDefault("PYTHONPATH", "");
        String prefix = pythonPathEntries.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .reduce((left, right) -> left + java.io.File.pathSeparator + right)
                .orElse("");
        if (StringUtils.hasText(prefix)) {
            env.put("PYTHONPATH", StringUtils.hasText(existingPythonPath) ? prefix + java.io.File.pathSeparator + existingPythonPath : prefix);
        }
        recordUse(definition);

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

    private void recordUse(ScriptSkillDefinition definition) {
        if (skillUsageService != null && definition != null) {
            skillUsageService.recordUse(definition.skillName());
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

    private List<String> parseSkillChain(String skillNames) {
        if (!StringUtils.hasText(skillNames)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String token : skillNames.split("\\s*(?:->|,|，|\\n)\\s*")) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            result.add(normalizeSkillName(token));
        }
        return List.copyOf(result);
    }

    private String workspaceRoot() {
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString();
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
