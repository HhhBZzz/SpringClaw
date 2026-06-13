package com.springclaw.tool.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.common.util.TextUtils;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.auth.ToolPermissionService;
import com.springclaw.service.workspace.WorkspaceGuard;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具运行时切面：统一限流与审计。
 */
@Aspect
@Component
public class ToolRuntimeAspect {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ToolGuardService toolGuardService;
    private final ToolAuditService toolAuditService;
    private final ToolPermissionService toolPermissionService;

    public ToolRuntimeAspect(ToolGuardService toolGuardService,
                             ToolAuditService toolAuditService,
                             ToolPermissionService toolPermissionService) {
        this.toolGuardService = toolGuardService;
        this.toolAuditService = toolAuditService;
        this.toolPermissionService = toolPermissionService;
    }

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object aroundTool(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String genericToolName = signature.getDeclaringType().getSimpleName() + "." + signature.getName();
        Object[] args = joinPoint.getArgs();
        String runtimeToolName = resolveRuntimeToolName(genericToolName, args);
        ToolExecutionContext context = ToolExecutionContextHolder.get();

        try {
            String userId = context == null ? null : context.userId();
            toolPermissionService.checkPermission(userId, genericToolName);
        } catch (BusinessException ex) {
            toolAuditService.recordInvoke(runtimeToolName, "DENIED", ex.getMessage(), context);
            throw ex;
        }

        toolGuardService.checkRateLimit(runtimeToolName);
        toolAuditService.recordInvoke(runtimeToolName, "START", renderToolInputDetail(runtimeToolName, args), context);

        try {
            Object result = joinPoint.proceed();
            toolAuditService.recordInvoke(runtimeToolName, "SUCCESS", summarize(result), context);
            return result;
        } catch (Throwable ex) {
            toolAuditService.recordInvoke(runtimeToolName, "FAILED", summarizeFailure(ex), context);
            throw ex;
        }
    }

    private String resolveRuntimeToolName(String genericToolName, Object[] args) {
        if (!genericToolName.startsWith("ScriptSkillToolPack.runScriptSkill")) {
            return genericToolName;
        }
        if (args == null || args.length == 0 || args[0] == null) {
            return genericToolName;
        }
        String skillName = String.valueOf(args[0]).trim();
        if (skillName.isEmpty()) {
            return genericToolName;
        }
        String normalized = skillName.replaceAll("[^a-zA-Z0-9_-]", "_");
        return genericToolName + "[" + normalized + "]";
    }

    private String renderToolInputDetail(String runtimeToolName, Object[] args) {
        Map<String, Object> payload = new LinkedHashMap<>();
        String action = resolveToolInputAction(runtimeToolName);
        String target = resolveToolInputTarget(runtimeToolName, args);
        payload.put("schema", "springclaw.tool-input.v1");
        payload.put("action", action);
        payload.put("target", target);
        payload.put("inputSummary", target);
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception ignored) {
            return "invoke";
        }
    }

    private String resolveToolInputAction(String runtimeToolName) {
        if (runtimeToolName.endsWith(".workspaceRunCommand")) {
            return "command.run";
        }
        if (runtimeToolName.endsWith(".workspaceWriteFile")) {
            return "file.write";
        }
        if (runtimeToolName.endsWith(".workspaceApplyPatch")) {
            return "file.patch";
        }
        if (runtimeToolName.startsWith("ScriptSkillToolPack.runScriptSkill")) {
            return "skill.run";
        }
        return "tool.invoke";
    }

    private String resolveToolInputTarget(String runtimeToolName, Object[] args) {
        if (runtimeToolName.endsWith(".workspaceRunCommand")
                || runtimeToolName.endsWith(".workspaceWriteFile")
                || runtimeToolName.endsWith(".workspaceApplyPatch")
                || runtimeToolName.startsWith("ScriptSkillToolPack.runScriptSkill")) {
            return firstArg(args);
        }
        return runtimeToolName;
    }

    private String firstArg(Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return "";
        }
        return TextUtils.truncate(String.valueOf(args[0]), 240);
    }

    private String summarize(Object result) {
        if (result == null) {
            return "null";
        }
        String text = String.valueOf(result);
        if (text.length() > 180) {
            return text.substring(0, 180) + "...";
        }
        return text;
    }

    private String summarizeFailure(Throwable ex) {
        if (ex instanceof WorkspaceGuard.WorkspaceGuardException guardException) {
            return renderWorkspaceGuardDetail(guardException);
        }
        return ex.getClass().getSimpleName() + ": " + ex.getMessage();
    }

    private String renderWorkspaceGuardDetail(WorkspaceGuard.WorkspaceGuardException ex) {
        WorkspaceGuard.Decision decision = ex.decision();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "springclaw.workspace-guard.v1");
        payload.put("action", decision == null || decision.action() == null ? "REJECT" : decision.action().name());
        payload.put("reasonCode", decision == null ? "" : decision.reasonCode());
        payload.put("message", ex.getMessage());
        payload.put("resolvedPath", decision == null || decision.resolvedPath() == null ? "" : decision.resolvedPath().toString());
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception ignored) {
            return "WorkspaceGuardException: " + ex.getMessage();
        }
    }
}
