package com.springclaw.tool.runtime;

import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.auth.ToolPermissionService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/**
 * 工具运行时切面：统一限流与审计。
 */
@Aspect
@Component
public class ToolRuntimeAspect {

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
            toolAuditService.recordInvoke(runtimeToolName, "FAILED", ex.getClass().getSimpleName() + ": " + ex.getMessage(), context);
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
}
