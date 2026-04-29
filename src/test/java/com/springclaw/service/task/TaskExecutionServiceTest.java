package com.springclaw.service.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springclaw.domain.entity.ScheduledTask;
import com.springclaw.service.chat.BuiltinSkillExecutionService;
import com.springclaw.service.chat.impl.ChatServiceImpl;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.MemoryService;
import com.springclaw.service.prompt.SoulPromptService;
import com.springclaw.service.session.AgentSessionService;
import com.springclaw.service.skill.SkillService;
import com.springclaw.service.skill.impl.SkillRegistryService;
import com.springclaw.service.skill.script.ScriptSkillExecutorService;
import com.springclaw.service.task.executor.TaskExecutionService;
import com.springclaw.strategy.channel.outbound.ChannelOutboundDispatcher;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
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
        ScriptSkillExecutorService scriptExecutor = mock(ScriptSkillExecutorService.class);
        BuiltinSkillExecutionService builtinExecutor = mock(BuiltinSkillExecutionService.class);
        SkillRegistryService registryService = mock(SkillRegistryService.class);
        SkillService skillService = mock(SkillService.class);
        ChatServiceImpl chatService = mock(ChatServiceImpl.class);
        AgentSessionService agentSessionService = mock(AgentSessionService.class);
        MemoryService memoryService = mock(MemoryService.class);
        MessageEventService messageEventService = mock(MessageEventService.class);
        SoulPromptService soulPromptService = mock(SoulPromptService.class);
        ChannelOutboundDispatcher dispatcher = mock(ChannelOutboundDispatcher.class);

        TaskExecutionService service = new TaskExecutionService(
                scheduledTaskService,
                executionService,
                scheduleSupport,
                scriptExecutor,
                builtinExecutor,
                registryService,
                skillService,
                chatService,
                agentSessionService,
                memoryService,
                messageEventService,
                soulPromptService,
                dispatcher,
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
        when(registryService.listAgentVisibleDefinitions(Set.of("script"))).thenReturn(List.of(
                new com.springclaw.service.skill.SkillDefinition(
                        "web_crawler", "网页抓取", "desc", "SCRIPT", "scripts/run.py", "", List.of(), List.of(), List.of("script"),
                        "simplified", "session-only", "script", "web_crawler", true, 10, true
                )
        ));
        when(executionService.start(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            var record = new com.springclaw.domain.entity.ScheduledTaskExecution();
            record.setExecutionId("exec_1");
            return record;
        });
        when(scriptExecutor.runScriptSkillByGoal("web_crawler", "读取这个网页 https://example.com")).thenReturn("抓取成功");

        TaskExecutionOutcome outcome = service.runTask(task, "MANUAL");

        assertThat(outcome.resultPayload()).isEqualTo("抓取成功");
        verify(chatService, never()).executeTaskMessage(any(), anyBoolean());
        verify(executionService).complete(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void shouldExecuteAgentTaskThroughChatService() {
        ScheduledTaskService scheduledTaskService = mock(ScheduledTaskService.class);
        ScheduledTaskExecutionService executionService = mock(ScheduledTaskExecutionService.class);
        ScriptSkillExecutorService scriptExecutor = mock(ScriptSkillExecutorService.class);
        BuiltinSkillExecutionService builtinExecutor = mock(BuiltinSkillExecutionService.class);
        SkillRegistryService registryService = mock(SkillRegistryService.class);
        SkillService skillService = mock(SkillService.class);
        ChatServiceImpl chatService = mock(ChatServiceImpl.class);
        AgentSessionService agentSessionService = mock(AgentSessionService.class);
        MemoryService memoryService = mock(MemoryService.class);
        MessageEventService messageEventService = mock(MessageEventService.class);
        SoulPromptService soulPromptService = mock(SoulPromptService.class);
        ChannelOutboundDispatcher dispatcher = mock(ChannelOutboundDispatcher.class);

        TaskExecutionService service = new TaskExecutionService(
                scheduledTaskService,
                executionService,
                new TaskScheduleSupport(),
                scriptExecutor,
                builtinExecutor,
                registryService,
                skillService,
                chatService,
                agentSessionService,
                memoryService,
                messageEventService,
                soulPromptService,
                dispatcher,
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
            var record = new com.springclaw.domain.entity.ScheduledTaskExecution();
            record.setExecutionId("exec_2");
            return record;
        });
        when(chatService.executeTaskMessage(any(), anyBoolean())).thenReturn(
                new ChatServiceImpl.TaskChatExecutionResult("task:shadow:task_2", "今日进展：xxx", "req_1", "simplified", "task")
        );

        TaskExecutionOutcome outcome = service.runTask(task, "MANUAL");

        assertThat(outcome.resultPayload()).contains("今日进展");
        verify(chatService).executeTaskMessage(any(), anyBoolean());
    }
}
