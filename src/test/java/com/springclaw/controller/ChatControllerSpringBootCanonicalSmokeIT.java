package com.springclaw.controller;

import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunEventType;
import com.springclaw.runtime.contract.RunState;
import com.springclaw.runtime.contract.RunStatus;
import com.springclaw.runtime.contract.SessionAccessClaim;
import com.springclaw.runtime.identity.RunIdentityFactory;
import com.springclaw.runtime.lifecycle.RunEventStore;
import com.springclaw.runtime.lifecycle.RunStateRepository;
import com.springclaw.runtime.memory.contract.MemoryScope;
import com.springclaw.runtime.memory.contract.MemoryStatus;
import com.springclaw.runtime.memory.contract.MemoryType;
import com.springclaw.runtime.memory.contract.ShortTermMemoryEntry;
import com.springclaw.runtime.memory.port.ShortTermMemoryStore;
import com.springclaw.service.agent.AgentDecision;
import com.springclaw.service.agent.AgentDecisionRequest;
import com.springclaw.service.agent.AgentDecisionService;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.agent.AgentRunTraceService;
import com.springclaw.service.agent.EngineSelector;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.auth.AuthService;
import com.springclaw.service.chat.async.AsyncChatResultStore;
import com.springclaw.service.chat.async.ChatMessageProducer;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;
import com.springclaw.service.context.ContextAssembler;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.event.MessageEventWrite;
import com.springclaw.service.memory.MemoryManagementService;
import com.springclaw.service.memory.MemoryWriteCommand;
import com.springclaw.service.memory.frame.MemoryCoordinator;
import com.springclaw.scheduled.ToolProposalCleanupTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "OPENCLAW_PRIMARY_API_KEY=test-key",
        "spring.ai.model.chat=none",
        "spring.ai.model.embedding=none",
        "springclaw.context.snapshot.factory-enabled=true",
        "springclaw.memory.frame.enabled=true",
        "springclaw.memory.core.enabled=true",
        "springclaw.memory.core.short-term-shadow-enabled=true",
        "springclaw.redisson.enabled=true",
        "springclaw.memory.vector-enabled=false",
        "spring.datasource.url=jdbc:mysql://${MYSQL_HOST:127.0.0.1}:${MYSQL_PORT:3306}/${MYSQL_DB:openclaw}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
})
@AutoConfigureMockMvc
class ChatControllerSpringBootCanonicalSmokeIT {

    private static final String TOKEN = "phase-3c-token";
    private static final String RUN_ID = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
    private static final String SESSION_KEY = "phase-3c-session";
    private static final String USER_ID = "phase-3c-user";
    private static final String CHANNEL = "api";
    private static final Instant T0 = Instant.parse("2026-06-25T03:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RunStateRepository runStateRepository;

    @Autowired
    private RunEventStore runEventStore;

    @Autowired
    private MemoryManagementService memoryManagementService;

    @Autowired
    private MessageEventService messageEventService;

    @Autowired
    private ShortTermMemoryStore shortTermMemoryStore;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private RunIdentityFactory runIdentityFactory;

    @MockitoBean
    private AiProviderService aiProviderService;

    @MockitoBean
    private AgentDecisionService agentDecisionService;

    @MockitoBean
    private EngineSelector engineSelector;

    @MockitoBean
    private ChatMessageProducer chatMessageProducer;

    @MockitoBean
    private AsyncChatResultStore asyncChatResultStore;

    @MockitoBean
    private AgentRunTraceService agentRunTraceService;

    @MockitoBean
    private ToolProposalCleanupTask toolProposalCleanupTask;

    @MockitoSpyBean
    private MemoryCoordinator memoryCoordinator;

    @MockitoSpyBean
    private ContextAssembler contextAssembler;

    @BeforeEach
    void setUp() {
        cleanCommittedData();
        seedMemoryFrameSources();

        when(authService.authenticateToken(TOKEN))
                .thenReturn(new AuthService.UserIdentity(
                        USER_ID,
                        "USER",
                        System.currentTimeMillis() + 60_000
                ));
        when(authService.resolveRoleByUserId(USER_ID)).thenReturn("USER");

        when(runIdentityFactory.create()).thenReturn(RUN_ID);
        when(runIdentityFactory.accept(any())).thenAnswer(invocation -> invocation.getArgument(0));

        when(aiProviderService.activeClient()).thenReturn(new AiProviderService.ActiveChatClient(
                "provider",
                "model",
                "https://example.test",
                mock(ChatClient.class),
                true,
                ""
        ));

        when(agentDecisionService.decide(any(AgentDecisionRequest.class)))
                .thenReturn(new AgentDecision(
                        "general",
                        "basic_model",
                        List.of("web"),
                        "read",
                        false,
                        "springboot smoke"
                ));

        AgentEngine engine = mock(AgentEngine.class);
        when(engine.name()).thenReturn("simplified");
        when(engine.execute(any(ChatContext.class), any()))
                .thenReturn(new ChatExecutionResult(
                        "observe",
                        "plan",
                        "action",
                        "springboot smoke answer",
                        true
                ));
        when(engineSelector.select(any(ChatContext.class))).thenReturn(engine);
    }

    @AfterEach
    void tearDown() {
        cleanCommittedData();
    }

    @Test
    void httpSendUsesCanonicalRuntimeWithRealSpringBootContextAndMemoryAdapters() throws Exception {
        mockMvc.perform(post("/api/chat/send")
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionKey": " phase-3c-session ",
                                  "message": "请用已有记忆回答",
                                  "channel": "api",
                                  "responseMode": "agent"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sessionKey").value(SESSION_KEY))
                .andExpect(jsonPath("$.data.answer").value("springboot smoke answer"))
                .andExpect(jsonPath("$.data.model").value("provider:model"));

        RunState state = runStateRepository.requireByRunId(RUN_ID);
        assertThat(state.status()).isEqualTo(RunStatus.DEGRADED);
        assertThat(state.contextSnapshot()).isNotNull();
        assertThat(state.contextSnapshot().contextSourceSummary())
                .containsEntry("schema", "springclaw.context-snapshot.v1")
                .containsEntry("shortTermCount", "1")
                .containsEntry("semanticCount", "1")
                .containsEntry("proceduralCount", "1");
        assertThat(state.contextSnapshot().shortTermEvents())
                .contains("上一轮用户说 SpringBoot smoke short-term memory");
        assertThat(state.contextSnapshot().semanticRecallItems())
                .contains("用户偏好：Phase 3C 需要真实 SpringBoot 冒烟测试");
        assertThat(state.contextSnapshot().activeLearningRules())
                .contains("执行规则：Runtime smoke 必须检查 canonical event 顺序");
        assertThat(runEventStore.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .containsExactly(
                        RunEventType.RUN_CREATED,
                        RunEventType.CONTEXT_READY,
                        RunEventType.DECISION_MADE,
                        RunEventType.STRATEGY_STARTED,
                        RunEventType.VERIFICATION_COMPLETED,
                        RunEventType.RUN_DEGRADED
                );
        verify(memoryCoordinator).retrieve(any());
        verifyNoInteractions(contextAssembler);
    }

    private void seedMemoryFrameSources() {
        MemoryScope scope = personalScope();
        messageEventService.append(new MessageEventWrite(
                "phase-3c-short-term-user",
                SESSION_KEY,
                CHANNEL,
                USER_ID,
                "USER",
                "CHAT",
                "上一轮用户说 SpringBoot smoke short-term memory",
                "previous-run"
        ));
        shortTermMemoryStore.append(scope, new ShortTermMemoryEntry(
                1L,
                "phase-3c-short-term-user",
                "previous-run",
                "USER",
                USER_ID,
                "上一轮用户说 SpringBoot smoke short-term memory",
                T0
        ));
        memoryManagementService.create(memoryCommand(
                "phase-3c-semantic",
                MemoryType.SEMANTIC,
                "用户偏好：Phase 3C 需要真实 SpringBoot 冒烟测试",
                "phase-3c semantic summary"
        ));
        memoryManagementService.create(memoryCommand(
                "phase-3c-procedural",
                MemoryType.PROCEDURAL,
                "执行规则：Runtime smoke 必须检查 canonical event 顺序",
                "phase-3c procedural summary"
        ));
    }

    private static MemoryWriteCommand memoryCommand(
            String logicalMemoryId,
            MemoryType memoryType,
            String content,
            String summary
    ) {
        return new MemoryWriteCommand(
                logicalMemoryId,
                memoryType,
                personalScope(),
                content,
                summary,
                null,
                List.of(),
                List.of("phase-3c-evidence"),
                List.of("phase-3c"),
                0.8,
                0.9,
                MemoryStatus.ACTIVE,
                T0,
                null,
                null,
                null,
                null
        );
    }

    private static MemoryScope personalScope() {
        return MemoryScope.from(SessionAccessClaim.personal(
                SessionAccessClaim.AcceptanceOrigin.AUTHENTICATED_API,
                CHANNEL,
                SESSION_KEY,
                USER_ID
        ));
    }

    private void cleanCommittedData() {
        MemoryScope scope = personalScope();
        redissonClient.getKeys().delete(
                "springclaw:memory:short-term:v2:"
                        + scope.scopeType().name()
                        + ":"
                        + scope.scopeId()
                        + ":order",
                "springclaw:memory:short-term:v2:"
                        + scope.scopeType().name()
                        + ":"
                        + scope.scopeId()
                        + ":entry",
                "springclaw:guard:rate:" + SESSION_KEY,
                "springclaw:guard:lock:" + SESSION_KEY
        );
        jdbcTemplate.update("DELETE FROM memory_index_outbox WHERE logical_memory_id LIKE 'phase-3c-%'");
        jdbcTemplate.update("DELETE FROM memory_record WHERE logical_memory_id LIKE 'phase-3c-%'");
        jdbcTemplate.update("DELETE FROM message_event WHERE session_key = ? OR request_id = ?", SESSION_KEY, RUN_ID);
        jdbcTemplate.update("DELETE FROM agent_session WHERE session_key = ?", SESSION_KEY);
    }
}
