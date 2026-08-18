package com.springclaw.runtime.history;

import com.springclaw.service.context.ContextAssembler;
import com.springclaw.runtime.lifecycle.InMemoryRunLifecycleStore;
import com.springclaw.runtime.lifecycle.RunCoordinator;
import com.springclaw.service.event.MessageEventService;
import com.springclaw.service.memory.MemoryBankService;
import com.springclaw.service.memory.MemoryService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 双路等价性对账(spec 2026-08-17-canonical-conversation-history §5.4):
 * 同一组对话事实,canonical 派生路径与 message_event legacy 路径渲染出的
 * LLM 历史内容等价(对话行/顺序)。这是测试内对账——生产只走单路+回退。
 */
class ConversationHistoryEquivalenceTest {

    private static final Instant T0 = Instant.parse("2026-08-17T08:00:00Z");

    @Test
    void canonicalDerivedHistoryRendersSameConversationLinesAsLegacyPath() {
        // 同一对话事实双写:canonical 事件 + 等价 message_event 行
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        String runId1 = persistRun(coordinator, "s1", "第一问", "第一答", 0);
        String runId2 = persistRun(coordinator, "s1", "第二问", "第二答", 60);

        // canonical 路径:deriver 派生
        ConversationHistoryDeriver deriver = new ConversationHistoryDeriver(store);
        MessageEventService messageEventService = Mockito.mock(MessageEventService.class);
        MemoryService memoryService = Mockito.mock(MemoryService.class);
        when(messageEventService.listRecent(Mockito.eq("s1"), Mockito.anyInt()))
                .thenReturn(List.of(
                        legacyEvent("USER", "CHAT", "第一问"),
                        legacyEvent("ASSISTANT", "CHAT", "[REFLECT] 第一答"),
                        legacyEvent("USER", "CHAT", "第二问"),
                        legacyEvent("ASSISTANT", "CHAT", "[REFLECT] 第二答")
                ));
        when(memoryService.recallBySession(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(List.of());

        ContextAssembler canonicalAssembler = new ContextAssembler(
                messageEventService, memoryService, new MemoryBankService(false, "", 400),
                8, 8, 400, deriver, "canonical");
        ContextAssembler legacyAssembler = new ContextAssembler(
                messageEventService, memoryService, new MemoryBankService(false, "", 400),
                8, 8, 400, null, "message-event");

        String canonicalContext = canonicalAssembler.assemble("s1", "api", "u1", "第三问").eventContext();
        String legacyContext = legacyAssembler.assemble("s1", "api", "u1", "第三问").eventContext();

        // 对话行逐行等价(chat 场景:legacy 从 [OBSERVE]/[REFLECT] 提取原文,canonical 直取)
        assertThat(canonicalContext)
                .contains("- USER: 第一问")
                .contains("- ASSISTANT: 第一答")
                .contains("- USER: 第二问")
                .contains("- ASSISTANT: 第二答");
        assertThat(legacyContext.split("\n"))
                .as("legacy 路径产出相同对话行集合")
                .containsExactlyInAnyOrder(canonicalContext.split("\n"));
        // 溯源:canonical 行来自两个 run
        assertThat(deriver.derive("s1", 16))
                .extracting(ConversationTurn::runId)
                .containsExactly(runId1, runId1, runId2, runId2);
    }

    @Test
    void suspendedRunDerivesSameLinesAsLegacySuspensionRows() {
        // T5a(spec 2026-08-18 §3.1)后的等价性:挂起 run 双写
        // canonical(user.message + assistant.answer SUSPENDED)与
        // legacy(USER 行 + suspension ASSISTANT 行)渲染同一组对话行。
        InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
        RunCoordinator coordinator = new RunCoordinator(store);
        String runId = TestRunSupport.newRunId();
        Instant t = T0;
        coordinator.accept(TestRunSupport.acceptanceAt(runId, "s1", t));
        coordinator.userMessage(runId, "删除那个文件", "blocking", t.plusSeconds(1));
        coordinator.assistantAnswer(runId, "该操作需要确认:删除 report.xlsx?", "SUSPENDED", t.plusSeconds(2));

        ConversationHistoryDeriver deriver = new ConversationHistoryDeriver(store);
        MessageEventService messageEventService = Mockito.mock(MessageEventService.class);
        MemoryService memoryService = Mockito.mock(MemoryService.class);
        Mockito.when(messageEventService.listRecent(Mockito.eq("s1"), Mockito.anyInt()))
                .thenReturn(List.of(
                        legacyEvent("USER", "CHAT", "删除那个文件"),
                        legacyEvent("ASSISTANT", "CHAT", "该操作需要确认:删除 report.xlsx?")
                ));
        Mockito.when(memoryService.recallBySession(Mockito.anyString(), Mockito.anyString(), Mockito.anyInt()))
                .thenReturn(List.of());

        ContextAssembler canonicalAssembler = new ContextAssembler(
                messageEventService, memoryService, new MemoryBankService(false, "", 400),
                8, 8, 400, deriver, "canonical");
        ContextAssembler legacyAssembler = new ContextAssembler(
                messageEventService, memoryService, new MemoryBankService(false, "", 400),
                8, 8, 400, null, "message-event");

        String canonicalContext = canonicalAssembler.assemble("s1", "api", "u1", "确认").eventContext();
        String legacyContext = legacyAssembler.assemble("s1", "api", "u1", "确认").eventContext();

        assertThat(canonicalContext)
                .contains("- USER: 删除那个文件")
                .contains("- ASSISTANT: 该操作需要确认:删除 report.xlsx?");
        assertThat(legacyContext.split("\n"))
                .as("suspension 场景两路对话行集合一致")
                .containsExactlyInAnyOrder(canonicalContext.split("\n"));
    }

    private String persistRun(RunCoordinator coordinator, String sessionKey,
                              String question, String answer, int offsetSec) {
        String runId = TestRunSupport.newRunId();
        Instant t = T0.plusSeconds(offsetSec);
        coordinator.accept(TestRunSupport.acceptanceAt(runId, sessionKey, t));
        coordinator.contextReady(runId, TestRunSupport.snapshot(runId), t.plusSeconds(1));
        coordinator.decided(runId, TestRunSupport.decision(runId), t.plusSeconds(2));
        coordinator.userMessage(runId, question, "blocking", t.plusSeconds(3));
        coordinator.running(runId, "test-strategy", t.plusSeconds(4));
        coordinator.verifying(runId, t.plusSeconds(5));
        coordinator.completed(runId,
                TestRunSupport.completionAt(runId, t.plusSeconds(6)),
                TestRunSupport.resultAt(runId, t.plusSeconds(6)),
                t.plusSeconds(6));
        coordinator.assistantAnswer(runId, answer, "FINAL", t.plusSeconds(7));
        return runId;
    }

    private static com.springclaw.domain.entity.MessageEvent legacyEvent(
            String role, String eventType, String content) {
        com.springclaw.domain.entity.MessageEvent e = new com.springclaw.domain.entity.MessageEvent();
        e.setSessionKey("s1");
        e.setChannel("api");
        e.setUserId("u1");
        e.setRole(role);
        e.setEventType(eventType);
        e.setContent(content);
        return e;
    }
}
