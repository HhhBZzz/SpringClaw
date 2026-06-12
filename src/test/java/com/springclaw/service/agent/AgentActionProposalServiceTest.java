package com.springclaw.service.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.common.exception.BusinessException;
import com.springclaw.domain.entity.ScheduledTask;
import com.springclaw.service.task.ScheduledTaskService;
import com.springclaw.service.task.TaskCreationDraft;
import com.springclaw.service.task.TaskDraftService;
import com.springclaw.service.task.TaskUpsertCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentActionProposalServiceTest {

    @Test
    void shouldCreateAndConfirmScheduledTaskProposal() {
        TaskDraftService draftService = mock(TaskDraftService.class);
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        TaskCreationDraft draft = new TaskCreationDraft(
                "网页抓取 - 每天 09:00", "preset", "DAILY@09:00", "每天 09:00", "skill", "web_crawler",
                "每天 9 点抓网页", "api", "NONE", "", false, "", "将创建任务"
        );
        when(draftService.parseDraft(eq("u1"), eq("api"), any())).thenReturn(draft);
        ScheduledTask task = new ScheduledTask();
        task.setTaskId("task-1");
        task.setName("网页抓取 - 每天 09:00");
        task.setTargetType("skill");
        task.setTargetRef("web_crawler");
        when(taskService.createTask(eq("u1"), any(TaskUpsertCommand.class))).thenReturn(task);
        AgentActionProposalService service = new AgentActionProposalService(draftService, taskService, new ToolRiskPolicyService(), new ObjectMapper());

        AgentActionProposal proposal = service.createProposal(
                "s1", "api", "u1", "USER", "req-1", "每天 9 点抓网页",
                new AgentDecision("scheduled_task", "task_draft", List.of("scheduled-task"), "side_effect", true, "定时任务")
        );
        AgentActionProposalResult result = service.confirm(proposal.proposalId(), "u1", "USER", "s1");

        assertThat(proposal.actionType()).isEqualTo("scheduled_task");
        assertThat(result.status()).isEqualTo("CONFIRMED");
        assertThat(result.result()).containsEntry("taskId", "task-1");
        verify(taskService).createTask(eq("u1"), any(TaskUpsertCommand.class));
    }

    @Test
    void shouldRejectDangerousProposalForNormalUser() {
        AgentActionProposalService service = new AgentActionProposalService(mock(TaskDraftService.class), mock(ScheduledTaskService.class), new ToolRiskPolicyService(), new ObjectMapper());
        AgentActionProposal proposal = service.createProposal(
                "s1", "api", "u1", "USER", "req-1", "执行命令 rm -rf /",
                new AgentDecision("unknown", "ask_clarification", List.of("dangerous-action"), "dangerous", true, "危险动作")
        );

        assertThatThrownBy(() -> service.confirm(proposal.proposalId(), "u1", "USER", "s1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("高风险动作");
    }

    @Test
    void shouldRecordConfirmationLifecycleIntoRunTrace() throws Exception {
        AgentRunTraceService traceService = mock(AgentRunTraceService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentActionProposalService service = new AgentActionProposalService(
                mock(TaskDraftService.class),
                mock(ScheduledTaskService.class),
                new ToolRiskPolicyService(),
                objectMapper,
                traceService
        );

        AgentActionProposal proposal = service.createProposal(
                "s1", "api", "u1", "USER", "req-1", "请修改 README",
                new AgentDecision("workspace_analysis", "agent_tools", List.of("workspace-edit"), "write", true, "写入需要确认")
        );
        service.confirm(proposal.proposalId(), "u1", "USER", "s1");

        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(traceService, times(2)).record(
                eq("s1"),
                eq("api"),
                eq("u1"),
                eq("req-1"),
                anyString(),
                eq("confirmation"),
                statusCaptor.capture(),
                detailCaptor.capture(),
                eq(0L)
        );

        assertThat(statusCaptor.getAllValues()).containsExactly("started", "success");
        assertThat(readAction(objectMapper, detailCaptor.getAllValues().get(0))).isEqualTo("confirmation.required");
        assertThat(readAction(objectMapper, detailCaptor.getAllValues().get(1))).isEqualTo("confirmation.confirmed");
    }

    @Test
    void shouldRecordCancelledConfirmationIntoRunTrace() throws Exception {
        AgentRunTraceService traceService = mock(AgentRunTraceService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AgentActionProposalService service = new AgentActionProposalService(
                mock(TaskDraftService.class),
                mock(ScheduledTaskService.class),
                new ToolRiskPolicyService(),
                objectMapper,
                traceService
        );

        AgentActionProposal proposal = service.createProposal(
                "s1", "api", "u1", "USER", "req-cancel", "请修改 README",
                new AgentDecision("workspace_analysis", "agent_tools", List.of("workspace-edit"), "write", true, "写入需要确认")
        );
        service.cancel(proposal.proposalId(), "u1", "USER");

        ArgumentCaptor<String> detailCaptor = ArgumentCaptor.forClass(String.class);
        verify(traceService, times(2)).record(
                eq("s1"),
                eq("api"),
                eq("u1"),
                eq("req-cancel"),
                anyString(),
                eq("confirmation"),
                anyString(),
                detailCaptor.capture(),
                eq(0L)
        );

        assertThat(readAction(objectMapper, detailCaptor.getAllValues().get(0))).isEqualTo("confirmation.required");
        assertThat(readAction(objectMapper, detailCaptor.getAllValues().get(1))).isEqualTo("confirmation.cancelled");
    }

    private String readAction(ObjectMapper objectMapper, String detail) throws Exception {
        Map<String, Object> payload = objectMapper.readValue(detail, new TypeReference<>() {
        });
        assertThat(payload).containsEntry("schema", "springclaw.timeline-step.v1");
        assertThat(payload).containsEntry("category", "confirmation");
        assertThat(payload).containsEntry("source", "action-proposal");
        return String.valueOf(payload.get("action"));
    }
}
