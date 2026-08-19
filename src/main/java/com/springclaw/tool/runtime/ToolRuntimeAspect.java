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
import com.springclaw.runtime.lifecycle.RunCoordinator;
import com.springclaw.service.proposal.ToolGateway;
import com.springclaw.service.workspace.WorkspaceGitGuard;
import com.springclaw.service.workspace.WorkspaceGuard;
import com.springclaw.tool.pack.ApprovedSystemCommand;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 工具运行时切面：AOP 捕获(签名/参数/上下文)+ 委托 ToolInvocationPipeline 执行策略。
 *
 * <p>管线化(dsh 工具瀑布参照):权限/命令白名单/限流是 guard 段,
 * proceed(含 write 分支的 proposal 校验+GitGuard 包装)是 execute 段,
 * 审计与 canonical timeline emit 是 complete 段。切面本身只剩捕获与装配。</p>
 *
 * <p>P0 语义保留:write/dangerous/side_effect/execution 工具未确认时创建
 * PENDING proposal 并抛 PendingToolApprovalException;已确认走 DB 二次校验
 * + WorkspaceGitGuard 包住 proceed。</p>
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
    private final ToolGateway toolGateway;
    private final com.springclaw.service.context.ToolResultPruner toolResultPruner;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ObjectProvider<RunCoordinator> runCoordinatorProvider;

    public ToolRuntimeAspect(ToolGuardService toolGuardService,
                             ToolAuditService toolAuditService,
                             ToolPermissionService toolPermissionService,
                             CapabilityRegistry capabilityRegistry,
                             ToolInvocationSnapshotService snapshotService,
                             ToolInvocationProposalService proposalService,
                             WorkspaceGitGuard workspaceGitGuard,
                             ToolGateway toolGateway,
                             com.springclaw.service.context.ToolResultPruner toolResultPruner) {
        this.toolGuardService = toolGuardService;
        this.toolAuditService = toolAuditService;
        this.toolPermissionService = toolPermissionService;
        this.capabilityRegistry = capabilityRegistry;
        this.snapshotService = snapshotService;
        this.proposalService = proposalService;
        this.workspaceGitGuard = workspaceGitGuard;
        this.toolGateway = toolGateway;
        this.toolResultPruner = toolResultPruner;
    }

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object aroundTool(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String simpleClass = signature.getDeclaringType().getSimpleName();
        String genericToolName = simpleClass + "." + signature.getName();
        Object[] args = joinPoint.getArgs();
        String runtimeToolName = resolveRuntimeToolName(genericToolName, args);
        ToolExecutionContext context = ToolExecutionContextHolder.get();

        // 权限否决单独审计 DENIED(先于管线,保持既有审计语义)
        try {
            String userId = context == null ? null : context.userId();
            toolPermissionService.checkPermission(userId, genericToolName);
        } catch (BusinessException ex) {
            toolAuditService.recordInvoke(runtimeToolName, "DENIED", ex.getMessage(), context);
            throw ex;
        }

        toolAuditService.recordInvoke(runtimeToolName, "START",
                renderToolInputDetail(runtimeToolName, args), context);

        String riskLevel = resolveRiskLevel(simpleClass, signature.getName());
        boolean requiresProposal = "write".equalsIgnoreCase(riskLevel)
                || "dangerous".equalsIgnoreCase(riskLevel)
                || "side_effect".equalsIgnoreCase(riskLevel)
                || "execution".equalsIgnoreCase(riskLevel);

        ToolInvocationPipeline pipeline = ToolInvocationPipeline.builder()
                // guard:命令白名单(SystemToolPack.runCommand 专用)
                .addGuard(inv -> {
                    rejectUnsupportedSystemCommand(simpleClass, signature.getName(), inv.args());
                    return ToolInvocationPipeline.GuardDecision.proceed();
                })
                // guard:限流
                .addGuard(inv -> {
                    toolGuardService.checkRateLimit(inv.toolName());
                    return ToolInvocationPipeline.GuardDecision.proceed();
                })
                // execute:read 旧路径(canonical emit + proceed)/write proposal 分支;
                // String 结果超预算免模型剪枝(头/尾保留+marker,dsh tool-result-pruner)
                .onExecute(inv -> {
                    Object result = requiresProposal
                            ? executeWriteWithProposal(joinPoint, approvedOrNull(), genericToolName,
                                    simpleClass, inv.args(), riskLevel)
                            : proceedWithTimeline(joinPoint, context, inv.toolName());
                    return pruneIfString(result);
                })
                // complete:审计收尾(SUCCESS/FAILED)+ PENDING_APPROVAL 透传
                .onComplete((inv, outcome) -> {
                    if ("success".equals(outcome.status())) {
                        toolAuditService.recordInvoke(inv.toolName(), "SUCCESS",
                                summarize(outcome.result()), context);
                    } else if ("failed".equals(outcome.status())
                            && outcome.error() instanceof PendingToolApprovalException pending) {
                        toolAuditService.recordInvoke(inv.toolName(), "PENDING_APPROVAL",
                                "proposalId=" + pending.proposalId(), context);
                    } else if ("failed".equals(outcome.status())) {
                        toolAuditService.recordInvoke(inv.toolName(), "FAILED",
                                summarizeFailure(outcome.error()), context);
                    }
                })
                .build();

        return pipeline.invoke(new ToolRuntimeInvocation(runtimeToolName, args));
    }

    private ApprovedProposalContext approvedOrNull() {
        return ToolExecutionContextHolder.getApprovedProposal();
    }

    /** String 工具结果超预算剪枝;非 String 结果透传。 */
    private Object pruneIfString(Object result) {
        if (result instanceof String text) {
            return toolResultPruner.prune(text);
        }
        return result;
    }

    private Object proceedWithTimeline(ProceedingJoinPoint joinPoint, ToolExecutionContext context,
                                       String runtimeToolName) throws Throwable {
        String runId = context == null ? null : context.runId();
        RunCoordinator coordinator = (runId == null || runCoordinatorProvider == null)
                ? null : runCoordinatorProvider.getIfAvailable();
        if (coordinator != null) {
            try {
                coordinator.toolStarted(runId, runtimeToolName, Instant.now());
            } catch (RuntimeException ignored) {
                // emit 失败不影响工具执行
            }
        }
        boolean succeeded = false;
        try {
            Object result = joinPoint.proceed();
            succeeded = true;
            return result;
        } finally {
            if (coordinator != null) {
                try {
                    if (succeeded) {
                        coordinator.toolSucceeded(runId, runtimeToolName, 0L, Instant.now());
                    } else {
                        coordinator.toolFailed(runId, runtimeToolName, "TOOL_THREW", Instant.now());
                    }
                } catch (RuntimeException ignored) {
                    // emit 失败不影响工具执行
                }
            }
        }
    }

    private Object executeWriteWithProposal(ProceedingJoinPoint joinPoint,
                                            ApprovedProposalContext approved,
                                            String genericToolName,
                                            String simpleClass,
                                            Object[] args,
                                            String riskLevel) throws Throwable {
        if (approved == null) {
            String toolsetId = capabilityRegistry.findToolsetByClassName(simpleClass);
            if (toolsetId == null) {
                toolsetId = simpleClass;
            }
            ToolInvocationSnapshot snapshot = snapshotService.capture(
                    genericToolName, toolsetId, args, riskLevel);
            ToolInvocationProposal proposal = toolGateway.requestApproval(snapshot,
                    ToolExecutionContextHolder.get());
            throw new PendingToolApprovalException(proposal.proposalId());
        }
        return executeWithGuard(joinPoint, approved, genericToolName, simpleClass, args);
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
        if ("SystemToolPack".equals(simpleClass) && "runCommand".equals(methodName)) {
            return "execution";
        }
        if ("FileToolPack".equals(simpleClass)
                && ("listFiles".equals(methodName)
                || "readTextFile".equals(methodName)
                || "searchFiles".equals(methodName)
                || "searchInFiles".equals(methodName))) {
            return "read";
        }
        return capabilityRegistry.findRiskLevelByClassName(simpleClass);
    }

    private void rejectUnsupportedSystemCommand(String simpleClass, String methodName, Object[] args) {
        if (!"SystemToolPack".equals(simpleClass) || !"runCommand".equals(methodName)) {
            return;
        }
        String command = args != null && args.length == 1 && args[0] instanceof String value ? value : null;
        if (!ApprovedSystemCommand.isApproved(command)) {
            throw new BusinessException(40062, "命令包含不允许的字符（;|&<>$() 等），或为空");
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

    /** 管线调用载体:捕获一次的运行时工具名+原始参数。 */
    private record ToolRuntimeInvocation(String toolName, Object[] args) implements ToolInvocation {
    }
}
