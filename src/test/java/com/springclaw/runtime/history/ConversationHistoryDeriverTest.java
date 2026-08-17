package com.springclaw.runtime.history;

import com.springclaw.runtime.contract.RunEvent;
import com.springclaw.runtime.contract.RunEventType;
import com.springclaw.runtime.lifecycle.InMemoryRunLifecycleStore;
import com.springclaw.runtime.lifecycle.RunCoordinator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConversationHistoryDeriver 契约测试(spec 2026-08-17-canonical-conversation-history §3.2):
 * 从 canonical run 事件日志派生 LLM 对话历史(USER/ASSISTANT 行+诊断摘要合成)。
 */
class ConversationHistoryDeriverTest {

    private static final Instant T0 = Instant.parse("2026-08-17T00:00:00Z");

    private final InMemoryRunLifecycleStore store = new InMemoryRunLifecycleStore();
    private final RunCoordinator coordinator = new RunCoordinator(store);
    private final ConversationHistoryDeriver deriver = new ConversationHistoryDeriver(store);

    @Test
    void derivesUserAndAssistantTurnsFromConversationEvents() {
        String runId = completeRun("s1", "什么是事件溯源?", "事件溯源是把状态变更存为事件日志。");

        List<ConversationTurn> turns = deriver.derive("s1", 8);

        assertThat(turns).extracting(ConversationTurn::role)
                .containsExactly(ConversationTurn.Role.USER, ConversationTurn.Role.ASSISTANT);
        assertThat(turns.get(0).content()).isEqualTo("什么是事件溯源?");
        assertThat(turns.get(1).content()).isEqualTo("事件溯源是把状态变更存为事件日志。");
        assertThat(turns).allSatisfy(t -> {
            assertThat(t.source()).isEqualTo(ConversationTurn.Source.CANONICAL);
            assertThat(t.runId()).isEqualTo(runId);
        });
    }

    @Test
    void derivesAcrossMultipleRunsInTimeOrder() {
        completeRun("s1", "第一问", "第一答");
        completeRun("s1", "第二问", "第二答");

        List<ConversationTurn> turns = deriver.derive("s1", 8);

        assertThat(turns).extracting(ConversationTurn::content)
                .containsExactly("第一问", "第一答", "第二问", "第二答");
    }

    @Test
    void derivesOnlyForRequestedSession() {
        completeRun("s1", "s1 的问题", "s1 的答案");
        completeRun("s2", "s2 的问题", "s2 的答案");

        List<ConversationTurn> turns = deriver.derive("s1", 8);

        assertThat(turns).extracting(ConversationTurn::content)
                .containsExactly("s1 的问题", "s1 的答案");
    }

    @Test
    void truncatesAnswerToRenderLimitLikeLegacyPath() {
        String longAnswer = "长".repeat(400);
        completeRun("s1", "q", longAnswer);

        List<ConversationTurn> turns = deriver.derive("s1", 8);

        // 与 legacy renderEventLine 截断规则一致: 220 字符
        assertThat(turns.get(1).content()).hasSize(220);
    }

    @Test
    void limitsTotalTurnCount() {
        for (int i = 0; i < 10; i++) {
            completeRun("s1", "问" + i, "答" + i);
        }

        List<ConversationTurn> turns = deriver.derive("s1", 4);

        // 取最近的 limit 条,时序保持
        assertThat(turns).extracting(ConversationTurn::content)
                .containsExactly("问8", "答8", "问9", "答9");
    }

    @Test
    void runWithoutAnswerYieldsOnlyUserTurn() {
        // 挂起/中断的 run:user.message 有、assistant.answer 无 → 只派生 USER 行
        coordinator.accept(TestRunSupport.acceptance("run-x", "s1"));
        coordinator.userMessage("run-x", "被挂起的问题", "blocking", T0);

        List<ConversationTurn> turns = deriver.derive("s1", 8);

        assertThat(turns).extracting(ConversationTurn::content)
                .containsExactly("被挂起的问题");
    }

    @Test
    void emptySessionYieldsEmptyHistory() {
        assertThat(deriver.derive("no-such-session", 8)).isEmpty();
    }

    @Test
    void questionTruncatedToRenderLimit() {
        String longQuestion = "为".repeat(400);
        completeRun("s1", longQuestion, "a");

        List<ConversationTurn> turns = deriver.derive("s1", 8);

        assertThat(turns.get(0).content()).hasSize(220);
    }

    /** 走完整状态机链完成一个 run,含 user.message/assistant.answer 对话语义。 */
    private String completeRun(String sessionKey, String question, String answer) {
        String runId = TestRunSupport.newRunId();
        // 每 run 独立时间线(固定序列),避免跨 run startedAt/finishedAt 校验互踩
        Instant t = Instant.parse("2026-08-17T00:00:00Z")
                .plusSeconds(TestRunSupport.sequence() * 60);
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
}
