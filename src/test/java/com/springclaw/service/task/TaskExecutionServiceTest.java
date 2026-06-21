package com.springclaw.service.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.domain.entity.ScheduledTask;
import com.springclaw.domain.entity.ScheduledTaskExecution;
import com.springclaw.dto.chat.ChatRequest;
import com.springclaw.runtime.bridge.LegacyRuntimeBridge;
import com.springclaw.runtime.lifecycle.RunAcceptance;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.chat.impl.ChatServiceImpl;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.MemoryService;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.session.AgentSessionService;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.runtime.SkillRuntimeService;
import com.springclaw.service.task.executor.TaskExecutionService;
import com.springclaw.strategy.channel.outbound.ChannelOutboundDispatcher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskExecutionServiceTest {

    @Test
    void shouldExecuteScriptSkillTask() {
        ScheduledTaskService scheduledTaskService = mock(ScheduledTaskService.class);
        ScheduledTaskExecutionService executionService = mock(ScheduledTaskExecutionService.class);
        TaskScheduleSupport scheduleSupport = new TaskScheduleSupport();
        SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
        SkillService skillService = mock(SkillService.class);
        ChatServiceImpl chatService = mock(ChatServiceImpl.class);
        AgentSessionService agentSessionService = mock(AgentSessionService.class);
        MemoryService memoryService = mock(MemoryService.class);
        MessageEventService messageEventService = mock(MessageEventService.class);
        SoulPromptService soulPromptService = mock(SoulPromptService.class);
        ChannelOutboundDispatcher dispatcher = mock(ChannelOutboundDispatcher.class);
        AuthService authService = mock(AuthService.class);
        LegacyRuntimeBridge runtimeBridge = mock(LegacyRuntimeBridge.class);
        when(authService.resolveRoleByUserId("tester")).thenReturn("USER");

        TaskExecutionService service = new TaskExecutionService(
                scheduledTaskService,
                executionService,
                scheduleSupport,
                skillRuntimeService,
                skillService,
                chatService,
                agentSessionService,
                memoryService,
                messageEventService,
                soulPromptService,
                dispatcher,
                authService,
                runtimeBridge,
                new ObjectMapper(),
                true
        );

        ScheduledTask task = new ScheduledTask();
        task.setTaskId("task_1");
        task.setOwnerUserId("tester");
        task.setName("网页抓取任务");
        task.setChannel("api");
        task.setTargetType("skill");
        task.setTargetRef("web_crawler");
        task.setInputPayload("读取这个网页 https://example.com");
        task.setPersistToSession(0);
        task.setNextRunAt(LocalDateTime.now().plusMinutes(10));

        when(skillService.resolveAllowedToolPacks("api", "tester")).thenReturn(Set.of("script"));
        when(executionService.start(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            var record = new com.springclaw.domain.entity.ScheduledTaskExecution();
            record.setExecutionId("exec_1");
            return record;
        });
        when(skillRuntimeService.executeBySkillId("web_crawler", "读取这个网页 https://example.com", Set.of("script")))
                .thenReturn("抓取成功");

        TaskExecutionOutcome outcome = service.runTask(task, "MANUAL");

        assertThat(outcome.resultPayload()).isEqualTo("抓取成功");
        verify(skillRuntimeService).executeBySkillId("web_crawler", "读取这个网页 https://example.com", Set.of("script"));
        verify(chatService, never()).executeTaskMessage(
                any(ChatRequest.class),
                anyBoolean()
        );
        verify(chatService, never()).executeTaskMessage(
                any(ChatRequest.class),
                anyBoolean(),
                anyString()
        );
        verify(executionService).complete(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any());

        ArgumentCaptor<RunAcceptance> acceptance =
                ArgumentCaptor.forClass(RunAcceptance.class);
        verify(runtimeBridge).accepted(acceptance.capture());
        assertThat(acceptance.getValue().sessionKey()).isEqualTo("task:shadow:task_1");
        assertThat(acceptance.getValue().channel()).isEqualTo("api");
        assertThat(acceptance.getValue().userId()).isEqualTo("tester");
        assertThat(acceptance.getValue().roleCodeAtAcceptance()).isEqualTo("USER");
        assertThat(acceptance.getValue().originalMessage())
                .isEqualTo("读取这个网页 https://example.com");
        assertThat(acceptance.getValue().responseMode()).isEqualTo("skill");
        assertThat(Duration.between(
                acceptance.getValue().acceptedAt(),
                acceptance.getValue().deadlineAt()
        )).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void shouldExecutePythonSkillTask() {
        ScheduledTaskService scheduledTaskService = mock(ScheduledTaskService.class);
        ScheduledTaskExecutionService executionService = mock(ScheduledTaskExecutionService.class);
        TaskScheduleSupport scheduleSupport = new TaskScheduleSupport();
        SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
        SkillService skillService = mock(SkillService.class);
        ChatServiceImpl chatService = mock(ChatServiceImpl.class);
        AgentSessionService agentSessionService = mock(AgentSessionService.class);
        MemoryService memoryService = mock(MemoryService.class);
        MessageEventService messageEventService = mock(MessageEventService.class);
        SoulPromptService soulPromptService = mock(SoulPromptService.class);
        ChannelOutboundDispatcher dispatcher = mock(ChannelOutboundDispatcher.class);
        AuthService authService = mock(AuthService.class);
        LegacyRuntimeBridge runtimeBridge = mock(LegacyRuntimeBridge.class);
        when(authService.resolveRoleByUserId("tester")).thenReturn("USER");

        TaskExecutionService service = new TaskExecutionService(
                scheduledTaskService,
                executionService,
                scheduleSupport,
                skillRuntimeService,
                skillService,
                chatService,
                agentSessionService,
                memoryService,
                messageEventService,
                soulPromptService,
                dispatcher,
                authService,
                runtimeBridge,
                new ObjectMapper(),
                true
        );

        ScheduledTask task = new ScheduledTask();
        task.setTaskId("task_python");
        task.setOwnerUserId("tester");
        task.setName("Python Skill 任务");
        task.setChannel("api");
        task.setTargetType("skill");
        task.setTargetRef("repo_inspector");
        task.setInputPayload("分析项目结构");
        task.setPersistToSession(0);
        task.setNextRunAt(LocalDateTime.now().plusMinutes(10));

        when(skillService.resolveAllowedToolPacks("api", "tester")).thenReturn(Set.of("script"));
        when(executionService.start(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            var record = new com.springclaw.domain.entity.ScheduledTaskExecution();
            record.setExecutionId("exec_python");
            return record;
        });
        when(skillRuntimeService.executeBySkillId("repo_inspector", "分析项目结构", Set.of("script")))
                .thenReturn("结构分析完成");

        TaskExecutionOutcome outcome = service.runTask(task, "MANUAL");

        assertThat(outcome.resultPayload()).isEqualTo("结构分析完成");
        verify(skillRuntimeService).executeBySkillId("repo_inspector", "分析项目结构", Set.of("script"));
        verify(chatService, never()).executeTaskMessage(
                any(ChatRequest.class),
                anyBoolean()
        );
        verify(chatService, never()).executeTaskMessage(
                any(ChatRequest.class),
                anyBoolean(),
                anyString()
        );
    }

    @Test
    void shouldExecuteAgentTaskThroughChatService() {
        ScheduledTaskService scheduledTaskService = mock(ScheduledTaskService.class);
        ScheduledTaskExecutionService executionService = mock(ScheduledTaskExecutionService.class);
        SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
        SkillService skillService = mock(SkillService.class);
        ChatServiceImpl chatService = mock(ChatServiceImpl.class);
        AgentSessionService agentSessionService = mock(AgentSessionService.class);
        MemoryService memoryService = mock(MemoryService.class);
        MessageEventService messageEventService = mock(MessageEventService.class);
        SoulPromptService soulPromptService = mock(SoulPromptService.class);
        ChannelOutboundDispatcher dispatcher = mock(ChannelOutboundDispatcher.class);
        AuthService authService = mock(AuthService.class);
        LegacyRuntimeBridge runtimeBridge = mock(LegacyRuntimeBridge.class);
        when(authService.resolveRoleByUserId("tester")).thenReturn("USER");

        TaskExecutionService service = new TaskExecutionService(
                scheduledTaskService,
                executionService,
                new TaskScheduleSupport(),
                skillRuntimeService,
                skillService,
                chatService,
                agentSessionService,
                memoryService,
                messageEventService,
                soulPromptService,
                dispatcher,
                authService,
                runtimeBridge,
                new ObjectMapper(),
                true
        );

        ScheduledTask task = new ScheduledTask();
        task.setTaskId("task_2");
        task.setOwnerUserId("tester");
        task.setName("日报任务");
        task.setChannel("api");
        task.setTargetType("agent");
        task.setTargetRef("prompt");
        task.setInputPayload("总结今天的项目进展");
        task.setPersistToSession(0);
        task.setNextRunAt(LocalDateTime.now().plusMinutes(10));

        when(executionService.start(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            var record = new ScheduledTaskExecution();
            record.setExecutionId("exec_2");
            return record;
        });
        when(chatService.executeTaskMessage(
                any(ChatRequest.class),
                anyBoolean(),
                anyString()
        )).thenAnswer(invocation -> new ChatServiceImpl.TaskChatExecutionResult(
                "task:shadow:task_2",
                "今日进展：xxx",
                invocation.getArgument(2),
                "simplified",
                "task"
        ));

        TaskExecutionOutcome outcome = service.runTask(task, "MANUAL");

        ArgumentCaptor<String> startedRunId = ArgumentCaptor.forClass(String.class);
        verify(executionService).start(
                eq("task_2"),
                eq("MANUAL"),
                startedRunId.capture()
        );
        ArgumentCaptor<RunAcceptance> acceptance =
                ArgumentCaptor.forClass(RunAcceptance.class);
        ArgumentCaptor<ChatRequest> chatRequest =
                ArgumentCaptor.forClass(ChatRequest.class);
        verify(runtimeBridge).accepted(acceptance.capture());
        verify(chatService).executeTaskMessage(
                chatRequest.capture(),
                eq(false),
                eq(startedRunId.getValue())
        );
        assertThat(acceptance.getValue().runId()).isEqualTo(startedRunId.getValue());
        assertThat(acceptance.getValue().originalMessage())
                .isEqualTo("总结今天的项目进展");
        assertThat(acceptance.getValue().responseMode()).isEqualTo("agent");
        assertThat(chatRequest.getValue().message())
                .isEqualTo(acceptance.getValue().originalMessage());
        assertThat(chatRequest.getValue().responseMode()).isEqualTo("agent");
        assertThat(outcome.resultPayload()).contains("今日进展");
        assertThat(outcome.requestId()).isEqualTo(startedRunId.getValue());
    }

    @Test
    void lifecycleAcceptanceFailureStopsScheduledExecutionBeforeExecutionRow() {
        ScheduledTaskService scheduledTaskService = mock(ScheduledTaskService.class);
        ScheduledTaskExecutionService executionService =
                mock(ScheduledTaskExecutionService.class);
        SkillRuntimeService skillRuntimeService = mock(SkillRuntimeService.class);
        ChatServiceImpl chatService = mock(ChatServiceImpl.class);
        AuthService authService = mock(AuthService.class);
        LegacyRuntimeBridge runtimeBridge = mock(LegacyRuntimeBridge.class);
        when(authService.resolveRoleByUserId("tester")).thenReturn("USER");
        when(runtimeBridge.accepted(any(RunAcceptance.class)))
                .thenThrow(new IllegalStateException("lifecycle unavailable"));
        TaskExecutionService service = new TaskExecutionService(
                scheduledTaskService,
                executionService,
                new TaskScheduleSupport(),
                skillRuntimeService,
                mock(SkillService.class),
                chatService,
                mock(AgentSessionService.class),
                mock(MemoryService.class),
                mock(MessageEventService.class),
                mock(SoulPromptService.class),
                mock(ChannelOutboundDispatcher.class),
                authService,
                runtimeBridge,
                new ObjectMapper(),
                true
        );
        ScheduledTask task = new ScheduledTask();
        task.setTaskId("task_failure");
        task.setOwnerUserId("tester");
        task.setName("失败任务");
        task.setChannel("api");
        task.setTargetType("agent");
        task.setInputPayload("执行失败验证");
        task.setPersistToSession(0);

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.runTask(task, "MANUAL")
        );

        verify(executionService, never()).start(anyString(), anyString(), anyString());
        verify(chatService, never()).executeTaskMessage(
                any(ChatRequest.class),
                anyBoolean(),
                anyString()
        );
        verify(skillRuntimeService, never())
                .executeBySkillId(anyString(), anyString(), any());
        verify(scheduledTaskService).markFinished(
                eq(task),
                eq("FAILED"),
                any(LocalDateTime.class)
        );
    }
}
