package com.springclaw.tool.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        String runtimeToolName = resolveRuntimeToolName(genericToolName, joinPoint.getArgs());
        ToolExecutionContext context = ToolExecutionContextHolder.get();

        try {
            String userId = context == null ? null : context.userId();
            toolPermissionService.checkPermission(userId, genericToolName);
        } catch (BusinessException ex) {
            toolAuditService.recordInvoke(runtimeToolName, "DENIED", ex.getMessage(), context);
            throw ex;
        }

        toolGuardService.checkRateLimit(runtimeToolName);
        toolAuditService.recordInvoke(runtimeToolName, "START", "invoke", context);

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
