package com.springclaw.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.domain.entity.ScheduledTask;
import com.springclaw.service.task.ScheduledTaskService;
import com.springclaw.service.task.TaskCreationDraft;
import com.springclaw.service.task.TaskDraftService;
import com.springclaw.service.task.TaskUpsertCommand;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class AgentActionProposalService {

    private static final long DEFAULT_TTL_MILLIS = 10 * 60 * 1000L;

    private final TaskDraftService taskDraftService;
    private final ScheduledTaskService scheduledTaskService;
    private final ToolRiskPolicyService riskPolicyService;
    private final ObjectMapper objectMapper;
    private final AgentRunTraceService agentRunTraceService;
    private final ConcurrentMap<String, AgentActionProposal> proposals = new ConcurrentHashMap<>();

    @Autowired
    public AgentActionProposalService(TaskDraftService taskDraftService,
                                      ScheduledTaskService scheduledTaskService,
                                      ToolRiskPolicyService riskPolicyService,
                                      ObjectMapper objectMapper,
                                      ObjectProvider<AgentRunTraceService> agentRunTraceServiceProvider) {
        this(
                taskDraftService,
                scheduledTaskService,
                riskPolicyService,
                objectMapper,
                agentRunTraceServiceProvider == null ? null : agentRunTraceServiceProvider.getIfAvailable()
        );
    }

    public AgentActionProposalService(TaskDraftService taskDraftService,
                                      ScheduledTaskService scheduledTaskService,
                                      ToolRiskPolicyService riskPolicyService,
                                      ObjectMapper objectMapper) {
        this(taskDraftService, scheduledTaskService, riskPolicyService, objectMapper, (AgentRunTraceService) null);
    }

    public AgentActionProposalService(TaskDraftService taskDraftService,
                                      ScheduledTaskService scheduledTaskService,
                                      ToolRiskPolicyService riskPolicyService,
                                      ObjectMapper objectMapper,
                                      AgentRunTraceService agentRunTraceService) {
        this.taskDraftService = taskDraftService;
        this.scheduledTaskService = scheduledTaskService;
        this.riskPolicyService = riskPolicyService;
        this.objectMapper = objectMapper;
        this.agentRunTraceService = agentRunTraceService;
    }

    public AgentActionProposal createProposal(String sessionKey,
                                              String channel,
                                              String userId,
                                              String roleCode,
                                              String requestId,
                                              String message,
                                              AgentDecision decision) {
        if (decision == null || !decision.requiresConfirmation()) {
            throw new BusinessException(40088, "当前请求不需要确认");
        }
        String actionType = decision.isTaskDraft() ? "scheduled_task" : "guarded_action";
        Map<String, Object> payload = new LinkedHashMap<>();
        String title;
        String summary;
        if ("scheduled_task".equals(actionType)) {
            TaskCreationDraft draft = taskDraftService.parseDraft(userId, channel, message);
            if ("CURRENT_SESSION".equalsIgnoreCase(draft.deliveryTarget())) {
                draft = new TaskCreationDraft(
                        draft.name(), draft.scheduleType(), draft.scheduleExpression(), draft.scheduleLabel(), draft.targetType(), draft.targetRef(),
                        draft.inputPayload(), draft.channel(), draft.deliveryMode(), sessionKey, draft.persistToSession(), draft.sessionKeyTemplate(), draft.summary()
                );
            }
            payload.put("draft", objectMapper.convertValue(draft, Map.class));
            title = "创建定时任务";
            summary = draft.summary();
        } else {
            payload.put("message", message == null ? "" : message.trim());
            payload.put("decision", objectMapper.convertValue(decision, Map.class));
            title = decision.isDangerous() ? "高风险动作确认" : "受保护动作确认";
            summary = decision.isDangerous()
                    ? "该请求包含高风险命令/删除类操作，默认不会自动执行。"
                    : "该请求可能写入文件或产生外部副作用，必须确认后才允许继续。";
        }
        String proposalId = UUID.randomUUID().toString().replace("-", "");
        AgentActionProposal proposal = new AgentActionProposal(
                proposalId,
                requestId,
                sessionKey,
                userId,
                actionType,
                title,
                summary,
                decision.riskLevel(),
                payload,
                System.currentTimeMillis() + DEFAULT_TTL_MILLIS,
                "PENDING"
        );
        proposals.put(proposalId, proposal);
        recordProposalTrace(proposal, channel, "started", "confirmation.required", summary);
        return proposal;
    }

    public AgentActionProposalResult confirm(String proposalId, String username, String roleCode, String currentSessionKey) {
        AgentActionProposal proposal = requirePendingProposal(proposalId, username, roleCode);
        if ("dangerous".equalsIgnoreCase(proposal.riskLevel()) && !riskPolicyService.canConfirmDangerous(roleCode)) {
            proposals.put(proposalId, withStatus(proposal, "REJECTED"));
            recordProposalTrace(proposal, "api", "failed", "confirmation.rejected", "高风险动作只允许 ADMIN/DEVELOPER 确认");
            throw new BusinessException(40331, "高风险动作只允许 ADMIN/DEVELOPER 确认");
        }
        if ("scheduled_task".equalsIgnoreCase(proposal.actionType())) {
            TaskCreationDraft draft = objectMapper.convertValue(proposal.payload().get("draft"), TaskCreationDraft.class);
            String deliveryTarget = "CURRENT_SESSION".equalsIgnoreCase(draft.deliveryTarget()) ? currentSessionKey : draft.deliveryTarget();
            ScheduledTask task = scheduledTaskService.createTask(username, new TaskUpsertCommand(
                    draft.name(), true, draft.scheduleType(), draft.scheduleExpression(), draft.targetType(), draft.targetRef(),
                    draft.inputPayload(), draft.channel(), draft.deliveryMode(), deliveryTarget, draft.persistToSession(), draft.sessionKeyTemplate()
            ));
            proposals.put(proposalId, withStatus(proposal, "CONFIRMED"));
            recordProposalTrace(proposal, "api", "success", "confirmation.confirmed", "用户已确认，定时任务已创建");
            return new AgentActionProposalResult(proposalId, "CONFIRMED", "定时任务已创建", Map.of(
                    "taskId", task.getTaskId(),
                    "name", task.getName(),
                    "targetType", task.getTargetType(),
                    "targetRef", task.getTargetRef()
            ));
        }
        proposals.put(proposalId, withStatus(proposal, "CONFIRMED"));
        recordProposalTrace(proposal, "api", "success", "confirmation.confirmed", "用户已确认，允许继续执行受保护动作");
        return new AgentActionProposalResult(proposalId, "CONFIRMED", "动作已确认，但 v1 未配置对应执行器，未产生副作用。", Map.of("executed", false));
    }

    public AgentActionProposalResult cancel(String proposalId, String username, String roleCode) {
        AgentActionProposal proposal = requireAccessibleProposal(proposalId, username, roleCode);
        proposals.put(proposalId, withStatus(proposal, "CANCELLED"));
        recordProposalTrace(proposal, "api", "success", "confirmation.cancelled", "用户已取消，未执行任何动作");
        return new AgentActionProposalResult(proposalId, "CANCELLED", "已取消，未执行任何动作。", Map.of());
    }

    private AgentActionProposal requirePendingProposal(String proposalId, String username, String roleCode) {
        AgentActionProposal proposal = requireAccessibleProposal(proposalId, username, roleCode);
        if (!"PENDING".equalsIgnoreCase(proposal.status())) {
            throw new BusinessException(40931, "该确认请求已经处理: " + proposal.status());
        }
        if (proposal.expiresAt() < System.currentTimeMillis()) {
            proposals.put(proposalId, withStatus(proposal, "EXPIRED"));
            throw new BusinessException(40932, "确认请求已过期，请重新发起");
        }
        return proposal;
    }

    private AgentActionProposal requireAccessibleProposal(String proposalId, String username, String roleCode) {
        if (!StringUtils.hasText(proposalId)) {
            throw new BusinessException(40089, "proposalId 不能为空");
        }
        AgentActionProposal proposal = proposals.get(proposalId.trim());
        if (proposal == null) {
            throw new BusinessException(40431, "确认请求不存在或已清理");
        }
        if (!isOwnerOrAdmin(proposal, username, roleCode)) {
            throw new BusinessException(40332, "无权处理该确认请求");
        }
        return proposal;
    }

    private boolean isOwnerOrAdmin(AgentActionProposal proposal, String username, String roleCode) {
        if (proposal.userId() != null && proposal.userId().equals(username)) {
            return true;
        }
        String role = roleCode == null ? "" : roleCode.trim().toUpperCase();
        return "ADMIN".equals(role) || "DEVELOPER".equals(role);
    }

    private AgentActionProposal withStatus(AgentActionProposal proposal, String status) {
        return new AgentActionProposal(
                proposal.proposalId(), proposal.requestId(), proposal.sessionKey(), proposal.userId(), proposal.actionType(), proposal.title(),
                proposal.summary(), proposal.riskLevel(), proposal.payload(), proposal.expiresAt(), status
        );
    }

    private void recordProposalTrace(AgentActionProposal proposal,
                                     String channel,
                                     String status,
                                     String action,
                                     String detail) {
        if (agentRunTraceService == null || proposal == null || !StringUtils.hasText(proposal.requestId())) {
            return;
        }
        agentRunTraceService.record(
                proposal.sessionKey(),
                StringUtils.hasText(channel) ? channel : "api",
                proposal.userId(),
                proposal.requestId(),
                proposal.title(),
                "confirmation",
                status,
                renderTimelineDetail(proposal, action, detail),
                0L
        );
    }

    private String renderTimelineDetail(AgentActionProposal proposal, String action, String detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", AgentRunTraceEvent.TIMELINE_STEP_SCHEMA);
        payload.put("category", "confirmation");
        payload.put("action", action);
        payload.put("target", proposal.actionType());
        payload.put("source", "action-proposal");
        payload.put("riskLevel", proposal.riskLevel());
        payload.put("proposalId", proposal.proposalId());
        payload.put("title", proposal.title());
        payload.put("detail", detail);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return detail == null ? "" : detail;
        }
    }
}
