package com.springclaw.tool.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.common.util.TextUtils;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.service.auth.ToolPermissionService;
import com.springclaw.service.proposal.ApprovedProposalContext;
import com.springclaw.service.proposal.PendingToolApprovalException;
import com.springclaw.service.proposal.ToolInvocationProposal;
import com.springclaw.service.proposal.ToolInvocationProposalService;
import com.springclaw.service.proposal.ToolInvocationProposalStatus;
import com.springclaw.service.proposal.ToolInvocationSnapshot;
import com.springclaw.service.proposal.ToolInvocationSnapshotService;
import com.springclaw.service.workspace.WorkspaceGitGuard;
import com.springclaw.service.workspace.WorkspaceGuard;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 工具运行时切面：统一限流、审计、与写工具的最终风险门禁。
 *
 * <p>P0 改造（Task 6）：
 * <ul>
 *   <li>read/safe 工具：保留旧路径（permission + rate limit + audit + proceed）</li>
 *   <li>write/dangerous 工具：未确认时创建 PENDING proposal 并抛 PendingToolApprovalException；
 *       已确认时 DB 二次校验 + WorkspaceGitGuard 包住 proceed</li>
 * </ul>
 */
@Aspect
@Component
public class ToolRuntimeAspect {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ToolGuardService toolGuardService;
    private final ToolAuditService toolAuditService;
    private final ToolPermissionService toolPermissionService;
    private final CapabilityRegistry capabilityRegistry;
    private final ToolInvocationSnapshotService snapshotService;
    private final ToolInvocationProposalService proposalService;
    private final WorkspaceGitGuard workspaceGitGuard;

    public ToolRuntimeAspect(ToolGuardService toolGuardService,
                             ToolAuditService toolAuditService,
                             ToolPermissionService toolPermissionService,
                             CapabilityRegistry capabilityRegistry,
                             ToolInvocationSnapshotService snapshotService,
                             ToolInvocationProposalService proposalService,
                             WorkspaceGitGuard workspaceGitGuard) {
        this.toolGuardService = toolGuardService;
        this.toolAuditService = toolAuditService;
        this.toolPermissionService = toolPermissionService;
        this.capabilityRegistry = capabilityRegistry;
        this.snapshotService = snapshotService;
        this.proposalService = proposalService;
        this.workspaceGitGuard = workspaceGitGuard;
    }

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object aroundTool(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String simpleClass = signature.getDeclaringType().getSimpleName();
        String genericToolName = simpleClass + "." + signature.getName();
        Object[] args = joinPoint.getArgs();
        String runtimeToolName = resolveRuntimeToolName(genericToolName, args);
        ToolExecutionContext context = ToolExecutionContextHolder.get();

        // 既有：权限检查
        try {
            String userId = context == null ? null : context.userId();
            toolPermissionService.checkPermission(userId, genericToolName);
        } catch (BusinessException ex) {
            toolAuditService.recordInvoke(runtimeToolName, "DENIED", ex.getMessage(), context);
            throw ex;
        }

        // 既有：限流 + audit START
        toolGuardService.checkRateLimit(runtimeToolName);
        toolAuditService.recordInvoke(runtimeToolName, "START",
                renderToolInputDetail(runtimeToolName, args), context);

        // 新增：风险等级反查
        String riskLevel = resolveRiskLevel(simpleClass, signature.getName());
        boolean requiresProposal = "write".equalsIgnoreCase(riskLevel)
                || "dangerous".equalsIgnoreCase(riskLevel)
                || "side_effect".equalsIgnoreCase(riskLevel)
                || "execution".equalsIgnoreCase(riskLevel);

        try {
            Object result;
            if (!requiresProposal) {
                // read / null → 旧路径
                result = joinPoint.proceed();
            } else {
                ApprovedProposalContext approved = ToolExecutionContextHolder.getApprovedProposal();
                if (approved == null) {
                    // 未授权：创建 PENDING proposal、抛 PendingToolApprovalException
                    String toolsetId = capabilityRegistry.findToolsetByClassName(simpleClass);
                    if (toolsetId == null) {
                        toolsetId = simpleClass;
                    }
                    ToolInvocationSnapshot snapshot = snapshotService.capture(
                            genericToolName, toolsetId, args, riskLevel);
                    ToolInvocationProposal proposal = proposalService.createPending(snapshot, context);
                    throw new PendingToolApprovalException(proposal.proposalId());
                }
                // 已授权：DB 二次校验 + GitGuard 包住执行
                result = executeWithGuard(joinPoint, approved, genericToolName, simpleClass, args);
            }

            toolAuditService.recordInvoke(runtimeToolName, "SUCCESS", summarize(result), context);
            return result;
        } catch (PendingToolApprovalException pending) {
            // proposal 已挂起，单独审计——不视为失败
            toolAuditService.recordInvoke(runtimeToolName, "PENDING_APPROVAL",
                    "proposalId=" + pending.proposalId(), context);
            throw pending;
        } catch (Throwable ex) {
            toolAuditService.recordInvoke(runtimeToolName, "FAILED", summarizeFailure(ex), context);
            throw ex;
        }
    }

    private Object executeWithGuard(ProceedingJoinPoint joinPoint,
                                    ApprovedProposalContext approved,
                                    String genericToolName,
                                    String simpleClass,
                                    Object[] args) throws Throwable {
        ToolInvocationProposal latest = proposalService.findByProposalId(approved.proposalId())
                .orElseThrow(() -> new SecurityException("proposal 不存在: " + approved.proposalId()));

        // 不变量 5：状态必须是 EXECUTING（由 ProposalExecutionService 在 Task 7 时迁移）
        if (latest.status() != ToolInvocationProposalStatus.EXECUTING) {
            throw new SecurityException("proposal 状态非法: " + latest.status());
        }
        if (!genericToolName.equals(latest.toolName())) {
            proposalService.markFailed(latest.proposalId(), "toolName 不匹配");
            throw new SecurityException("toolName 不匹配");
        }
        if (!Objects.equals(latest.requestId(), approved.requestId())) {
            proposalService.markFailed(latest.proposalId(), "requestId 不匹配");
            throw new SecurityException("requestId 不匹配");
        }
        if (!Objects.equals(latest.runId(), approved.runId())) {
            proposalService.markFailed(latest.proposalId(), "runId 不匹配");
            throw new SecurityException("runId 不匹配");
        }
        if (!Objects.equals(latest.userId(), approved.userId())) {
            proposalService.markFailed(latest.proposalId(), "userId 不匹配");
            throw new SecurityException("userId 不匹配");
        }

        // 不变量 6：复算 hash 与 stored 比对
        String toolsetId = capabilityRegistry.findToolsetByClassName(simpleClass);
        if (toolsetId == null) {
            toolsetId = simpleClass;
        }
        String currentArgsHash = snapshotService.argsHash(genericToolName, toolsetId, args);
        if (!latest.argumentsHash().equals(currentArgsHash)) {
            proposalService.markFailed(latest.proposalId(), "args 被篡改");
            throw new SecurityException("args 被篡改");
        }

        // 不变量 7、10、14、15 在 GitGuard 内
        try {
            return workspaceGitGuard.execute(latest, () -> {
                try {
                    return joinPoint.proceed();
                } catch (RuntimeException | Error rex) {
                    throw rex;
                } catch (Exception ex) {
                    throw ex;
                } catch (Throwable t) {
                    // joinPoint.proceed() 声明 Throwable，但实际只会是 Exception/Error
                    throw new RuntimeException(t);
                }
            });
        } catch (SecurityException sec) {
            proposalService.markFailed(latest.proposalId(), sec.getMessage());
            throw sec;
        } catch (Throwable ex) {
            proposalService.markFailed(latest.proposalId(),
                    ex.getClass().getSimpleName() + ": "
                            + (ex.getMessage() == null ? "" : ex.getMessage()));
            throw ex;
        }
    }

    private String resolveRiskLevel(String simpleClass, String methodName) {
        if ("FileToolPack".equals(simpleClass)
                && ("listFiles".equals(methodName)
                || "readTextFile".equals(methodName)
                || "searchFiles".equals(methodName)
                || "searchInFiles".equals(methodName))) {
            return "read";
        }
        return capabilityRegistry.findRiskLevelByClassName(simpleClass);
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
