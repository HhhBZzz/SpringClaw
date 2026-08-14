# 事件地基统一与共享循环核抽取 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 spec `docs/superpowers/specs/2026-08-14-event-foundation-and-loop-kernel-design.md`——Phase 1 修复 canonical 事件语义残缺（run.completed 零发射、无 turn/step 边界、payload 恒空、verification 名实不符、双开关半持久态），Phase 2 抽取 AgentLoopKernel 吃掉 5 个范式引擎的骨架重复。

**Architecture:** 事件全部经 `RunCoordinator` 唯一入口发射；turn/step 走 observation 路径（不动状态机）；成功判定经 `CompletionVerifier` SPI 链；5 引擎先做 `StepScope` 埋点（Phase 1 过渡态），Phase 2 由内核接管步进与发射。

**Tech Stack:** Java 17 / Spring Boot 3.5 / JUnit 5 + Mockito + AssertJ。测试环境：docker MySQL 3307 + Redis 6379。

---

## 全局约定（每个任务都适用）

- **全量回归命令**（必须带环境变量，否则集成测试连不上库）：

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test
```

- 单测类运行：`MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test -Dtest=XxxTest`
- 基线：**1091 tests, 0 failures, 0 errors**（commit 82274479）。任何任务完成后不得低于"基线 + 本任务新增"全绿。
- 外部契约零破坏（spec §4）：`message_event` 写入路径、SSE trace 字段、既有 21 个 wireName（除 verification.completed 走读侧别名）一律不动。
- commit 信息结尾加 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`。

---

# Phase 1 — 事件地基

### Task 1: RunEventType 扩展 + verification 改名 + 读侧别名

**Files:**
- Modify: `src/main/java/com/springclaw/runtime/contract/RunEventType.java`
- Modify: `src/main/java/com/springclaw/runtime/lifecycle/RunCoordinator.java:200`（verifying() 引用枚举名）
- Create: `src/test/java/com/springclaw/runtime/contract/RunEventTypeTest.java`
- Modify（枚举名引用同步）: `src/test/java/com/springclaw/runtime/lifecycle/RunCoordinatorTest.java`、`src/test/java/com/springclaw/runtime/bridge/RunLifecycleObserverIntegrationTest.java`、`src/test/java/com/springclaw/controller/ChatControllerSpringBootCanonicalSmokeIT.java`、`src/test/java/com/springclaw/service/chat/impl/ChatControllerCanonicalHttpSmokeTest.java`、`src/test/java/com/springclaw/service/chat/impl/AcceptedRunCanonicalSmokeTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.springclaw.runtime.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunEventTypeTest {

    @Test
    void exposesTurnAndStepBoundaryWireNames() {
        assertThat(RunEventType.TURN_STARTED.wireName()).isEqualTo("turn.started");
        assertThat(RunEventType.TURN_COMPLETED.wireName()).isEqualTo("turn.completed");
        assertThat(RunEventType.STEP_STARTED.wireName()).isEqualTo("step.started");
        assertThat(RunEventType.STEP_COMPLETED.wireName()).isEqualTo("step.completed");
    }

    @Test
    void wireNamesAreUnique() {
        long distinct = java.util.Arrays.stream(RunEventType.values())
                .map(RunEventType::wireName)
                .distinct()
                .count();
        assertThat(distinct).isEqualTo(RunEventType.values().length);
    }

    @Test
    void verificationStartedSupersedesVerificationCompletedWithReadAlias() {
        // 存量 DB 行的 event_json 里是 "verification.completed"——读侧别名保证旧记录可反序列化
        assertThat(RunEventType.fromWireName("verification.started"))
                .isEqualTo(RunEventType.VERIFICATION_STARTED);
        assertThat(RunEventType.fromWireName("verification.completed"))
                .isEqualTo(RunEventType.VERIFICATION_STARTED);
    }

    @Test
    void unknownWireNameStillRejected() {
        assertThatThrownBy(() -> RunEventType.fromWireName("no.such.event"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test -Dtest=RunEventTypeTest
```
预期：编译失败（TURN_STARTED/VERIFICATION_STARTED 不存在）。

- [ ] **Step 3: 实现**

`RunEventType.java` 全量替换为：

```java
package com.springclaw.runtime.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Canonical run event families. The wire name (dotted, e.g. {@code context.ready})
 * is the stable serialization contract across trace, audit, and transport projections
 * — see unified-runtime architecture spec § 7.9.
 *
 * <p>读侧别名：{@code verification.completed} 是 {@code verification.started} 的
 * 历史名（该事件实际在进入 VERIFYING 时发射）。存量持久化记录按别名读取，写入一律用新名。</p>
 */
public enum RunEventType {
    RUN_CREATED("run.created"),
    CONTEXT_READY("context.ready"),
    DECISION_MADE("decision.made"),
    STRATEGY_STARTED("strategy.started"),
    TURN_STARTED("turn.started"),
    STEP_STARTED("step.started"),
    MODEL_CALLED("model.called"),
    TOOL_REQUESTED("tool.requested"),
    CONFIRMATION_REQUIRED("confirmation.required"),
    CONFIRMATION_APPROVED("confirmation.approved"),
    CONFIRMATION_REJECTED("confirmation.rejected"),
    TOOL_STARTED("tool.started"),
    TOOL_SUCCEEDED("tool.succeeded"),
    TOOL_FAILED("tool.failed"),
    STEP_COMPLETED("step.completed"),
    TURN_COMPLETED("turn.completed"),
    VERIFICATION_STARTED("verification.started"),
    MEMORY_EXTRACTED("memory.extracted"),
    REFLECTED("reflect.completed"),
    ANSWER_COMPOSED("answer.composed"),
    RUN_COMPLETED("run.completed"),
    RUN_DEGRADED("run.degraded"),
    RUN_FAILED("run.failed"),
    DELIVERY_ATTEMPTED("delivery.attempted"),
    DELIVERY_FAILED("delivery.failed");

    private static final Map<String, RunEventType> WIRE_LOOKUP = Stream
            .concat(
                    Arrays.stream(values()).map(type -> Map.entry(type.wireName(), type)),
                    // 历史别名：写新读旧
                    Stream.of(Map.entry("verification.completed", VERIFICATION_STARTED))
            )
            .collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

    private final String wireName;

    RunEventType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static RunEventType fromWireName(String value) {
        RunEventType type = WIRE_LOOKUP.get(value);
        if (type == null) {
            throw new IllegalArgumentException("Unknown run event type: " + value);
        }
        return type;
    }
}
```

注意：枚举声明顺序按事件流时序排列（turn/step 夹在 STRATEGY 与 VERIFICATION 之间），但 `values()` 顺序不影响任何行为——事件排序由 sequence 决定。`RunCoordinator.java:200` 的 `RunEventType.VERIFICATION_COMPLETED` 改为 `RunEventType.VERIFICATION_STARTED`。

5 个测试文件中的 `RunEventType.VERIFICATION_COMPLETED` 全部替换为 `RunEventType.VERIFICATION_STARTED`（sed 或逐处编辑；本任务只改枚举名，断言序列在 Task 8 再按新事件流更新——若中间状态有断言失败，把对应断言行改为 `VERIFICATION_STARTED` 后必须全绿才能提交）。

- [ ] **Step 4: 运行确认通过**

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test -Dtest="RunEventTypeTest,RunCoordinatorTest,RunLifecycleObserverIntegrationTest"
```
预期：PASS（RunCoordinatorTest/IntegrationTest 在别名生效后只差枚举名，已同步）。

- [ ] **Step 5: 快速全量回归**

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test
```
预期：全绿（smoke 断言若因枚举名已同步则不变）。

- [ ] **Step 6: Commit**

```bash
git add -A src/main/java/com/springclaw/runtime/contract/RunEventType.java src/main/java/com/springclaw/runtime/lifecycle/RunCoordinator.java src/test
git commit -m "feat(runtime): RunEventType 增 turn/step 边界事件,verification 改名+读侧别名"
```

---

### Task 2: RunCoordinator observation payload 富化 API

**Files:**
- Modify: `src/main/java/com/springclaw/runtime/lifecycle/RunCoordinator.java`
- Modify: `src/test/java/com/springclaw/runtime/lifecycle/RunCoordinatorTest.java`

- [ ] **Step 1: 写失败测试**（追加到 RunCoordinatorTest；沿用该文件现有的 store/coordinator 夹具与 `acceptRun()` 辅助——执行时先读该文件头部 80 行对齐命名）

```java
    @Test
    void modelCalledCarriesProviderAndModelPayload() {
        acceptRun();
        coordinator.modelCalled(RUN_ID, "primary", "deepseek-chat", T0);

        RunEvent event = lastEvent();
        assertThat(event.eventType()).isEqualTo(RunEventType.MODEL_CALLED);
        assertThat(event.payloadSchema()).isEqualTo("springclaw.runtime.observation.v1");
        assertThat(event.payload())
                .isEqualTo("{\"model\":\"deepseek-chat\",\"providerId\":\"primary\"}");
    }

    @Test
    void toolObservationsCarryStructuredPayload() {
        acceptRun();
        coordinator.toolStarted(RUN_ID, "SystemToolPack.runCommand", T0);
        coordinator.toolSucceeded(RUN_ID, "SystemToolPack.runCommand", 42L, T0);
        coordinator.toolFailed(RUN_ID, "WebToolPack.fetch", "TIMEOUT", T0);

        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType, RunEvent::payload)
                .containsSubsequence(
                        org.assertj.core.groups.Tuple.tuple(RunEventType.TOOL_STARTED,
                                "{\"toolName\":\"SystemToolPack.runCommand\"}"),
                        org.assertj.core.groups.Tuple.tuple(RunEventType.TOOL_SUCCEEDED,
                                "{\"durationMs\":42,\"toolName\":\"SystemToolPack.runCommand\"}"),
                        org.assertj.core.groups.Tuple.tuple(RunEventType.TOOL_FAILED,
                                "{\"errorCode\":\"TIMEOUT\",\"toolName\":\"WebToolPack.fetch\"}")
                );
    }

    @Test
    void turnAndStepBoundariesAreObservationsSurvivingTerminalState() {
        acceptRun();
        coordinator.turnStarted(RUN_ID, "blocking", T0);
        coordinator.stepStarted(RUN_ID, 0, "react", T0);
        coordinator.stepCompleted(RUN_ID, 0, "react", "ok", 17L, T0);
        coordinator.completed(RUN_ID, completedDecision(), result(), T0);
        // 终态后追加 turn.completed(observation 允许,不改状态机)
        coordinator.turnCompleted(RUN_ID, "COMPLETE", 120L, T0);

        assertThat(store.requireByRunId(RUN_ID).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .containsSequence(
                        RunEventType.TURN_STARTED,
                        RunEventType.STEP_STARTED,
                        RunEventType.STEP_COMPLETED,
                        RunEventType.RUN_COMPLETED,
                        RunEventType.TURN_COMPLETED
                );
        assertThat(store.findEventsByRunId(RUN_ID).get(1).payload())
                .isEqualTo("{\"responseMode\":\"blocking\"}");
        assertThat(store.findEventsByRunId(RUN_ID).get(2).payload())
                .isEqualTo("{\"stepIndex\":0,\"stepKind\":\"react\"}");
        assertThat(store.findEventsByRunId(RUN_ID).get(3).payload())
                .isEqualTo("{\"durationMs\":17,\"outcome\":\"ok\",\"stepIndex\":0,\"stepKind\":\"react\"}");
        assertThat(store.findEventsByRunId(RUN_ID).get(5).durationMs()).isEqualTo(120L);
    }

    @Test
    void legacyNoPayloadOverloadsKeepLifecycleSchema() {
        acceptRun();
        coordinator.modelCalled(RUN_ID, T0);
        coordinator.toolStarted(RUN_ID, T0);

        assertThat(store.findEventsByRunId(RUN_ID))
                .allSatisfy(event -> {
                    assertThat(event.payload()).isEqualTo("{}");
                    assertThat(event.payloadSchema())
                            .isEqualTo("springclaw.runtime.lifecycle.v1");
                });
    }
```

辅助（若文件里没有 `lastEvent()`/`completedDecision()`/`result()` 就按现有夹具补）：

```java
    private RunEvent lastEvent() {
        java.util.List<RunEvent> events = store.findEventsByRunId(RUN_ID);
        return events.get(events.size() - 1);
    }
```

`completedDecision()`/`result()` 若不存在，从该文件现有 terminal 测试中复制构造（CompletionDecision COMPLETE + RunResult COMPLETED 的最小参数）。

- [ ] **Step 2: 运行确认失败**

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test -Dtest=RunCoordinatorTest
```
预期：编译失败（新方法签名不存在）。

- [ ] **Step 3: 实现**（RunCoordinator.java）

新增 import 与常量：

```java
import com.fasterxml.jackson.databind.ObjectMapper;

    private static final String OBSERVATION_SCHEMA = "springclaw.runtime.observation.v1";
    private static final ObjectMapper PAYLOAD_JSON = new ObjectMapper();
```

新方法（放在既有 observation 方法旁）：

```java
    /** 记录一次模型调用(含 failover 尝试),payload 携带 provider/model。 */
    public RunEvent modelCalled(String runId, String providerId, String model, Instant at) {
        return appendStructuredObservation(runId, RunEventType.MODEL_CALLED, at, 0,
                java.util.Map.ofEntries(
                        java.util.Map.entry("providerId", nullToEmpty(providerId)),
                        java.util.Map.entry("model", nullToEmpty(model))));
    }

    public RunEvent toolStarted(String runId, String toolName, Instant at) {
        return appendStructuredObservation(runId, RunEventType.TOOL_STARTED, at, 0,
                java.util.Map.of("toolName", nullToEmpty(toolName)));
    }

    public RunEvent toolSucceeded(String runId, String toolName, long durationMs, Instant at) {
        return appendStructuredObservation(runId, RunEventType.TOOL_SUCCEEDED, at, durationMs,
                java.util.Map.of("toolName", nullToEmpty(toolName), "durationMs", durationMs));
    }

    public RunEvent toolFailed(String runId, String toolName, String errorCode, Instant at) {
        return appendStructuredObservation(runId, RunEventType.TOOL_FAILED, at, 0,
                java.util.Map.of("toolName", nullToEmpty(toolName),
                        "errorCode", nullToEmpty(errorCode)));
    }

    public RunEvent turnStarted(String runId, String responseMode, Instant at) {
        return appendStructuredObservation(runId, RunEventType.TURN_STARTED, at, 0,
                java.util.Map.of("responseMode", nullToEmpty(responseMode)));
    }

    public RunEvent turnCompleted(String runId, String outcome, long durationMs, Instant at) {
        return appendStructuredObservation(runId, RunEventType.TURN_COMPLETED, at, durationMs,
                java.util.Map.of("outcome", nullToEmpty(outcome), "durationMs", durationMs));
    }

    public RunEvent stepStarted(String runId, int stepIndex, String stepKind, Instant at) {
        return appendStructuredObservation(runId, RunEventType.STEP_STARTED, at, 0,
                java.util.Map.of("stepIndex", stepIndex, "stepKind", nullToEmpty(stepKind)));
    }

    public RunEvent stepCompleted(String runId, int stepIndex, String stepKind,
                                   String outcome, long durationMs, Instant at) {
        return appendStructuredObservation(runId, RunEventType.STEP_COMPLETED, at, durationMs,
                java.util.Map.of("stepIndex", stepIndex, "stepKind", nullToEmpty(stepKind),
                        "outcome", nullToEmpty(outcome), "durationMs", durationMs));
    }
```

旧 2 参签名改为委托（保持 ToolProposalCleanupTask 等既有调用点不破）：

```java
    public RunEvent toolStarted(String runId, Instant at) {
        return appendObservation(runId, RunEventType.TOOL_STARTED, at);
    }

    public RunEvent toolSucceeded(String runId, Instant at) {
        return appendObservation(runId, RunEventType.TOOL_SUCCEEDED, at);
    }

    public RunEvent toolFailed(String runId, Instant at) {
        return appendObservation(runId, RunEventType.TOOL_FAILED, at);
    }
```

（`modelCalled(runId, at)`、`memoryExtracted`、`reflected` 保持原样不动。）

私有辅助：

```java
    private RunEvent appendStructuredObservation(
            String runId, RunEventType eventType, Instant at,
            long durationMs, java.util.Map<String, Object> payload
    ) {
        RunState current = requireForObservation(runId);
        return store.append(current.revision(),
                new RunEvent.Draft(runId, eventType, "lifecycle", current.status(), at,
                        durationMs, OBSERVATION_SCHEMA, writePayload(payload), null,
                        current.requestId(), current.paradigm()));
    }

    private static String writePayload(java.util.Map<String, Object> payload) {
        try {
            return PAYLOAD_JSON.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
```

payload JSON 键序由 Jackson Map 序列化决定（TreeMap/Map.of 无保证）——断言用完整字符串比对时若键序不同，改断言为 `contains("\"providerId\":\"primary\"")` 形式。

- [ ] **Step 4: 运行确认通过**

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test -Dtest=RunCoordinatorTest
```
预期：PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/springclaw/runtime/lifecycle/RunCoordinator.java src/test/java/com/springclaw/runtime/lifecycle/RunCoordinatorTest.java
git commit -m "feat(runtime): observation 事件 payload 富化 API(model/tool/turn/step)"
```

---

### Task 3: CompletionVerifier SPI + resultReturned 走 completed/degraded/failed 分流

**Files:**
- Create: `src/main/java/com/springclaw/runtime/bridge/CompletionVerifier.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/CompletionVerdict.java`
- Create: `src/main/java/com/springclaw/runtime/bridge/TrustedModelVerifier.java`
- Modify: `src/main/java/com/springclaw/runtime/bridge/RunResultProjector.java`
- Modify: `src/main/java/com/springclaw/runtime/bridge/RunLifecycleObserver.java`
- Create: `src/test/java/com/springclaw/runtime/bridge/TrustedModelVerifierTest.java`
- Modify: `src/test/java/com/springclaw/runtime/bridge/RunLifecycleObserverIntegrationTest.java`
- Modify: `src/test/java/com/springclaw/runtime/bridge/RuntimeProjectionAdaptersTest.java`（若其断言 adaptDegraded）

- [ ] **Step 1: 定义 SPI（三个新文件）**

`CompletionVerifier.java`：

```java
package com.springclaw.runtime.bridge;

import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;

/**
 * 完成判定器:回答"这次 run 的产出是否有资格宣告成功"。
 * <p>判定链由 Spring 注入的 List&lt;CompletionVerifier&gt; 组成,首个 supports 的判定生效。
 * 原则(dsh 参照系):验证世界而非自我报告——判据基于可观察事实(modelEnabled/answer 内容),
 * 不基于模型自称"已完成"。</p>
 */
public interface CompletionVerifier {

    boolean supports(ChatContext context, ChatExecutionResult result, String answer);

    CompletionVerdict verify(ChatContext context, ChatExecutionResult result, String answer);
}
```

`CompletionVerdict.java`：

```java
package com.springclaw.runtime.bridge;

import com.springclaw.runtime.contract.CompletionDecision;

/**
 * 一次完成判定的结论。outcome 语义:
 * COMPLETE→run.completed;DEGRADE→run.degraded(降级但产出可用);FAIL→run.failed。
 */
public record CompletionVerdict(
        CompletionDecision.Outcome outcome,
        String reasonCode,
        String summary
) {
    public CompletionVerdict {
        java.util.Objects.requireNonNull(outcome, "outcome");
        reasonCode = reasonCode == null ? "" : reasonCode;
        summary = summary == null ? "" : summary;
    }
}
```

`TrustedModelVerifier.java`：

```java
package com.springclaw.runtime.bridge;

import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ChatExecutionResult;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * MVP 判据:模型产出非空回答=COMPLETE;走本地技能 fallback=DEGRADE;无产出=FAIL。
 */
@Component
public class TrustedModelVerifier implements CompletionVerifier {

    @Override
    public boolean supports(ChatContext context, ChatExecutionResult result, String answer) {
        return true;
    }

    @Override
    public CompletionVerdict verify(ChatContext context, ChatExecutionResult result, String answer) {
        if (result == null || !result.modelEnabled()) {
            return new CompletionVerdict(
                    com.springclaw.runtime.contract.CompletionDecision.Outcome.DEGRADE,
                    "MODEL_UNAVAILABLE_LOCAL_FALLBACK",
                    "模型不可用,回答由本地技能降级路径产出,未经模型验证。"
            );
        }
        if (!StringUtils.hasText(answer)) {
            return new CompletionVerdict(
                    com.springclaw.runtime.contract.CompletionDecision.Outcome.FAIL,
                    "EMPTY_ANSWER",
                    "模型路径未产出任何回答内容。"
            );
        }
        return new CompletionVerdict(
                com.springclaw.runtime.contract.CompletionDecision.Outcome.COMPLETE,
                "MODEL_VERIFIED_ANSWER",
                "模型产出非空回答,判定为可信完成。"
        );
    }
}
```

- [ ] **Step 2: 写 TrustedModelVerifier 失败测试**

```java
package com.springclaw.runtime.bridge;

import com.springclaw.service.chat.impl.ChatExecutionResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TrustedModelVerifierTest {

    private final TrustedModelVerifier verifier = new TrustedModelVerifier();

    @Test
    void modelEnabledWithAnswerIsComplete() {
        var verdict = verifier.verify(null,
                new ChatExecutionResult("o", "p", "a", "r", true), "最终回答");
        assertThat(verdict.outcome()).isEqualTo(
                com.springclaw.runtime.contract.CompletionDecision.Outcome.COMPLETE);
        assertThat(verdict.reasonCode()).isEqualTo("MODEL_VERIFIED_ANSWER");
    }

    @Test
    void localFallbackIsDegrade() {
        var verdict = verifier.verify(null,
                new ChatExecutionResult("o", "p", "a", "r", false), "本地兜底回答");
        assertThat(verdict.outcome()).isEqualTo(
                com.springclaw.runtime.contract.CompletionDecision.Outcome.DEGRADE);
        assertThat(verdict.reasonCode()).isEqualTo("MODEL_UNAVAILABLE_LOCAL_FALLBACK");
    }

    @Test
    void blankAnswerIsFail() {
        var verdict = verifier.verify(null,
                new ChatExecutionResult("o", "p", "a", "r", true), "  ");
        assertThat(verdict.outcome()).isEqualTo(
                com.springclaw.runtime.contract.CompletionDecision.Outcome.FAIL);
        assertThat(verdict.reasonCode()).isEqualTo("EMPTY_ANSWER");
    }
}
```

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test -Dtest=TrustedModelVerifierTest
```
预期：PASS（SPI 与实现同 commit，测试先行验证判据）。

- [ ] **Step 3: RunResultProjector 增 adaptTerminal**

在 `RunResultProjector.java` 中：保留 `evidenceRefs()` 私有方法，删除 `adaptDegraded`（唯一调用方在本任务同步改掉），新增：

```java
    public TerminalObservation adaptTerminal(
            ChatContext context,
            ChatExecutionResult executionResult,
            String answer,
            CompletionVerdict verdict,
            Instant completedAt
    ) {
        List<String> evidenceRefs = evidenceRefs(executionResult);
        boolean complete =
                verdict.outcome() == CompletionDecision.Outcome.COMPLETE;
        CompletionDecision decision = new CompletionDecision(
                context.requestId(),
                verdict.outcome(),
                verdict.reasonCode(),
                verdict.summary(),
                evidenceRefs,
                complete ? List.of() : List.of("canonical-completion-verification"),
                false,
                0,
                0.0,
                completedAt
        );
        AiProviderService.ActiveChatClient activeClient = context.activeClient();
        RunResult result = new RunResult(
                context.requestId(),
                complete ? RunStatus.COMPLETED : RunStatus.DEGRADED,
                answer,
                complete ? RunResult.AnswerKind.FINAL : RunResult.AnswerKind.DEGRADED,
                activeClient == null ? "" : activeClient.providerId(),
                activeClient == null ? "" : activeClient.model(),
                evidenceRefs,
                List.of(),
                0.0,
                Map.of(),
                "",
                "",
                completedAt
        );
        return new TerminalObservation(decision, result);
    }
```

- [ ] **Step 4: 重写 RunLifecycleObserver.resultReturned**

`RunLifecycleObserver.java`：加 `@Slf4j`、import `java.util.List` / `java.util.Map` / `java.util.concurrent.ConcurrentHashMap`。字段与构造器：

```java
    private final List<CompletionVerifier> verifiers;
    private final Map<String, Instant> turnStartedAt = new ConcurrentHashMap<>();
```

`@Autowired` 主构造器追加参数 `List<CompletionVerifier> verifiers`（`Objects.requireNonNull`）；两个遗留构造器委托时传 `List.of(new TrustedModelVerifier())`。新增方法（全部吞异常，emit 失败不进主路径）：

```java
    public void turnStarted(String runId, String responseMode, Instant at) {
        if (turnStartedAt.size() > 8192) {
            log.warn("turnStartedAt 超过 8192 条,清空重置(疑似泄漏)");
            turnStartedAt.clear();
        }
        turnStartedAt.put(runId, at);
        try {
            bridge.turnStarted(runId, responseMode, at);
        } catch (RuntimeException ex) {
            log.debug("turn.started emit 失败, runId={}, reason={}", runId, ex.getMessage());
        }
    }

    private void turnCompleted(String runId, String outcome, Instant at) {
        Instant startedAt = turnStartedAt.remove(runId);
        long durationMs = startedAt == null ? 0L
                : Math.max(0L, at.toEpochMilli() - startedAt.toEpochMilli());
        try {
            bridge.turnCompleted(runId, outcome, durationMs, at);
        } catch (RuntimeException ex) {
            log.debug("turn.completed emit 失败, runId={}, reason={}", runId, ex.getMessage());
        }
    }

    public RunLifecycleObserver.StepScope beginStep(String runId, int stepIndex, String stepKind) {
        try {
            bridge.stepStarted(runId, stepIndex, stepKind, Instant.now());
            return new StepScope(runId, stepIndex, stepKind, System.nanoTime());
        } catch (RuntimeException ex) {
            log.debug("step.started emit 失败, runId={}, reason={}", runId, ex.getMessage());
            return StepScope.NOOP;
        }
    }

    /**
     * 步边界作用域:close() 发射 step.completed。outcome 默认 "ok";
     * 正常迭代结束、break/continue 提前退出均按 "ok" 记——失败事实由
     * run.failed/tool.failed 等事件承载,step 事件只标边界。
     */
    public static final class StepScope implements AutoCloseable {

        static final StepScope NOOP = new StepScope(null, 0, "", 0L);

        private final String runId;
        private final int stepIndex;
        private final String stepKind;
        private final long startedNano;
        private String outcome = "ok";

        private StepScope(String runId, int stepIndex, String stepKind, long startedNano) {
            this.runId = runId;
            this.stepIndex = stepIndex;
            this.stepKind = stepKind;
            this.startedNano = startedNano;
        }

        public void outcome(String value) {
            if (value != null) {
                this.outcome = value;
            }
        }

        @Override
        public void close() {
            if (runId == null) {
                return;
            }
            // close 由 try-with-resources 调用;发射失败只记日志
            // (需要 observer 实例才能调 bridge,故 NOOP 之外通过静态回调持有)
        }
    }
```

等等——`StepScope.close()` 需要访问 bridge，静态类拿不到 this。把 StepScope 改为**非静态内部类**（持有外部 observer 引用）：

```java
    public final class StepScope implements AutoCloseable {
        // 字段同上(runId/stepIndex/stepKind/startedNano/outcome)
        // 构造器 private,beginStep 里 new StepScope(...)
        @Override
        public void close() {
            if (runId == null) {
                return;
            }
            long durationMs = Math.max(0L, (System.nanoTime() - startedNano) / 1_000_000L);
            try {
                bridge.stepCompleted(runId, stepIndex, stepKind, outcome, durationMs, Instant.now());
            } catch (RuntimeException ex) {
                log.debug("step.completed emit 失败, runId={}, reason={}", runId, ex.getMessage());
            }
        }
        // outcome(String) 同上
    }
```

`beginStep` 返回 `this.new StepScope(...)`（内部类直接 `new StepScope(...)`）；NOOP 用 `new StepScope(null, 0, "", 0L)`（runId==null → close 直接 return）。

`resultReturned` 重写：

```java
    public void resultReturned(
            ChatContext context,
            ChatExecutionResult executionResult,
            String answer,
            Instant at
    ) {
        CompletionVerdict verdict = resolveVerdict(context, executionResult, answer);
        turnCompleted(context.requestId(), verdict.outcome().name(), at);
        bridge.verificationStarted(context.requestId(), at);
        if (verdict.outcome() == CompletionDecision.Outcome.FAIL) {
            bridge.failed(
                    context.requestId(),
                    new RunState.Failure(verdict.reasonCode(), verdict.summary(), false),
                    at
            );
            return;
        }
        RunResultProjector.TerminalObservation observation =
                resultAdapter.adaptTerminal(context, executionResult, answer, verdict, at);
        if (verdict.outcome() == CompletionDecision.Outcome.COMPLETE) {
            bridge.completed(
                    context.requestId(),
                    observation.decision(),
                    observation.result(),
                    at
            );
        } else {
            bridge.degraded(
                    context.requestId(),
                    observation.decision(),
                    observation.result(),
                    at
            );
        }
    }

    private CompletionVerdict resolveVerdict(
            ChatContext context,
            ChatExecutionResult executionResult,
            String answer
    ) {
        for (CompletionVerifier verifier : verifiers) {
            if (verifier.supports(context, executionResult, answer)) {
                return verifier.verify(context, executionResult, answer);
            }
        }
        return new CompletionVerdict(
                CompletionDecision.Outcome.DEGRADE,
                "NO_COMPLETION_VERIFIER",
                "无可用完成判定器,按降级处理。"
        );
    }
```

`failed(...)` 方法开头补一行（在 bridge.failed 之前）：`turnCompleted(runId, "FAILED", at);`

**同时** `RunLifecycleBridge` 接口 + `DefaultRunLifecycleBridge` 增 4 个委托方法：

```java
    // RunLifecycleBridge 接口新增:
    void turnStarted(String runId, String responseMode, Instant at);
    void turnCompleted(String runId, String outcome, long durationMs, Instant at);
    void stepStarted(String runId, int stepIndex, String stepKind, Instant at);
    void stepCompleted(String runId, int stepIndex, String stepKind, String outcome,
                       long durationMs, Instant at);

    // DefaultRunLifecycleBridge 实现(各一行委托):
    @Override public void turnStarted(String runId, String responseMode, Instant at) {
        coordinator.turnStarted(runId, responseMode, at);
    }
    @Override public void turnCompleted(String runId, String outcome, long durationMs, Instant at) {
        coordinator.turnCompleted(runId, outcome, durationMs, at);
    }
    @Override public void stepStarted(String runId, int stepIndex, String stepKind, Instant at) {
        coordinator.stepStarted(runId, stepIndex, stepKind, at);
    }
    @Override public void stepCompleted(String runId, int stepIndex, String stepKind,
                                        String outcome, long durationMs, Instant at) {
        coordinator.stepCompleted(runId, stepIndex, stepKind, outcome, durationMs, at);
    }
```

若有其他 `RunLifecycleBridge` 直接实现类（非 Mockito mock），执行时用 `grep -rn "implements RunLifecycleBridge" src/` 找出并补 4 个空实现。

- [ ] **Step 5: 更新 RunLifecycleObserverIntegrationTest**

`observesLegacyFactsInCanonicalOrderWithoutClaimingCompletion` 改名 `classifiesSuccessfulTurnAsCompletedWithTurnBoundaries`，断言更新（夹具 result 的 modelEnabled 已是 true，answer 非空）：

```java
        observer.turnStarted(RUN_ID, "agent", T0.plusSeconds(2));
        // ...原有 contextAndDecisionObserved/executionStarted/resultReturned 调用不变...
        assertThat(store.requireByRunId(RUN_ID).status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(store.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .containsExactly(
                        RunEventType.RUN_CREATED,
                        RunEventType.CONTEXT_READY,
                        RunEventType.DECISION_MADE,
                        RunEventType.STRATEGY_STARTED,
                        RunEventType.TURN_STARTED,
                        RunEventType.TURN_COMPLETED,
                        RunEventType.VERIFICATION_STARTED,
                        RunEventType.RUN_COMPLETED
                );
```

（turnStarted 在该测试里手动调——ChatServiceImpl 接线是 Task 5。）同文件补一个降级用例：

```java
    @Test
    void localFallbackResultIsDegraded() {
        // 复用第一个测试的夹具,把 result 换成 modelEnabled=false:
        // new ChatExecutionResult("o", "p", "a", "r", false)
        // 断言终态 DEGRADED + 事件尾 ... TURN_COMPLETED, VERIFICATION_STARTED, RUN_DEGRADED
    }
```

（写全：按第一个测试的结构复制，改 result 与断言。）

- [ ] **Step 6: 运行**

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test -Dtest="TrustedModelVerifierTest,RunLifecycleObserverIntegrationTest,RunLifecycleObserverTest,RunLifecycleObserverCanonicalModeTest,RuntimeProjectionAdaptersTest"
```
预期：PASS。`RuntimeProjectionAdaptersTest` 若引用 `adaptDegraded`，把断言改为 `adaptTerminal(..., new CompletionVerdict(DEGRADE, "MODEL_UNAVAILABLE_LOCAL_FALLBACK", "..."), ...)` 的等价断言。

- [ ] **Step 7: 全量回归 + Commit**

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test
```
预期：除 Task 8 处理的 smoke 断言外全绿——**若有 smoke 因 RUN_DEGRADED→RUN_COMPLETED 断言失败，在本任务内顺手把那几行 `RUN_DEGRADED` 断言同步为 `RUN_COMPLETED`/`containsSubsequence`（见 Task 8 Step 1 的目标形态），保证全绿再提交。**

```bash
git add -A src/main/java/com/springclaw/runtime/bridge src/test/java/com/springclaw/runtime/bridge
git commit -m "feat(runtime): CompletionVerifier SPI,run.completed 发射闭环 + turn 边界进 observer"
```

---

### Task 4: model/tool emit 调用点升级（payload 落地）

**Files:**
- Modify: `src/main/java/com/springclaw/service/chat/impl/ModelCallExecutor.java:40-57,96-101`
- Modify: `src/main/java/com/springclaw/tool/runtime/ToolRuntimeAspect.java:113-137`
- Modify: `src/main/java/com/springclaw/service/proposal/DefaultToolGateway.java:80-108`

- [ ] **Step 1: ModelCallExecutor 传 provider/model**

`emitModelCalled(ChatRequestContext)` 签名改为 `emitModelCalled(ChatRequestContext, AiProviderService.ActiveChatClient client)`，emit 行改为：

```java
            coordinator.modelCalled(
                    runId,
                    client == null ? null : client.providerId(),
                    client == null ? null : client.model(),
                    Instant.now()
            );
```

调用点（`executeChat` L101）改为 `emitModelCalled(requestContext, activeClient);`——语义：记录**请求的初始 client**（failover 目标在 executeInternal 内部才解析，MVP 不回填）。

- [ ] **Step 2: ToolRuntimeAspect 传 toolName**

L117 附近：`coordinator.toolStarted(runId, runtimeToolName, Instant.now());`；L130/132 的 succeeded/failed 改为带 `runtimeToolName`（failed 的 errorCode 传 `ex == null ? "UNKNOWN" : ex.getClass().getSimpleName()`，执行时看该处变量名对齐）。

- [ ] **Step 3: DefaultToolGateway 传 proposal.toolName()**

L82/94/106 三个调用分别改为 `lifecycleObserver.toolStarted(proposal.runId(), proposal.toolName(), Instant.now())` 等——**注意**：`RunLifecycleObserver.toolStarted(String, Instant)` 是旧签名，需要给 observer 加 3 个重载委托 bridge（bridge 已有 4 方法但那是 turn/step；tool 的带参方法在 bridge 上没有）。补齐：`RunLifecycleBridge`/`DefaultRunLifecycleBridge`/`RunLifecycleObserver` 各加

```java
    void toolStarted(String runId, String toolName, Instant at);
    void toolSucceeded(String runId, String toolName, long durationMs, Instant at);
    void toolFailed(String runId, String toolName, String errorCode, Instant at);
```

（RunCoordinator 侧 Task 2 已就绪；DefaultToolGateway 的 succeeded 处用 `System.currentTimeMillis()` 差值或 0L，执行时看现有代码上下文取耗时来源，没有就传 0L。）

- [ ] **Step 4: 运行相关单测**

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test -Dtest="ModelCallExecutor*Test,ToolRuntimeAspect*Test,DefaultToolGateway*Test"
```
预期：PASS（mock 协调器不校验参数；若有 verify 参数断言失败则同步断言）。

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java
git commit -m "feat(runtime): model/tool observation emit 调用点携带真实载荷"
```

---

### Task 5: ChatServiceImpl turnStarted 接线 + OparLoop observer 依赖

**Files:**
- Modify: `src/main/java/com/springclaw/service/chat/impl/ChatServiceImpl.java:264-266,675-678`
- Modify: `src/main/java/com/springclaw/service/chat/impl/OparLoopEngine.java:34-61`（构造器）、`:216`（循环头）
- Modify: `src/test/java/com/springclaw/service/chat/impl/RuntimeEngineTestFactory.java:19-35`

- [ ] **Step 1: ChatServiceImpl 两处 executionStarted 后插 turnStarted**

两处（executeStream L264 块、executeInternal L675 块）都在 `lifecycleObserver.executionStarted(...)` 之后加：

```java
            if (lifecycleObserver != null) {
                lifecycleObserver.turnStarted(
                        context.requestId(), context.responseMode(), Instant.now()
                );
            }
```

- [ ] **Step 2: OparLoopEngine 加 observer**

构造器在 `LocalExecutionSupport localExecutionSupport` 之后、`@Value` 参数之前插入 `RunLifecycleObserver lifecycleObserver`；加字段 `private final RunLifecycleObserver lifecycleObserver;` 与赋值；import `com.springclaw.runtime.bridge.RunLifecycleObserver`。

`RuntimeEngineTestFactory.oparLoopEngine` 对应位置插 `mock(com.springclaw.runtime.bridge.RunLifecycleObserver.class)`。

若有其他 `new OparLoopEngine(` 调用点（`grep -rn "new OparLoopEngine(" src/`），同步补 mock。

- [ ] **Step 3: 运行**

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test -Dtest="RuntimeEngineTestFactory*,OparLoop*Test,ChatServiceImpl*Test"
```
预期：PASS。

- [ ] **Step 4: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(runtime): ChatServiceImpl turn.started 接线 + OparLoop 挂 observer"
```

---

### Task 6: 5 引擎 step 边界埋点（StepScope）

**Files（循环头行号为 2026-08-14 快照,执行时先 grep 定位）:**
- Modify: `src/main/java/com/springclaw/service/chat/impl/ReActEngine.java:244`
- Modify: `src/main/java/com/springclaw/service/chat/impl/ReflexionEngine.java:256`
- Modify: `src/main/java/com/springclaw/service/chat/impl/PlanExecuteEngine.java:256`
- Modify: `src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java:239`
- Modify: `src/main/java/com/springclaw/service/chat/impl/OparLoopEngine.java:216`
- Test: 对应 `*EngineTest.java` 各加 1 个验证

- [ ] **Step 1: 引擎循环体包 try-with-resources（每引擎同构,kind 不同）**

以 ReAct 为例（L244 `for (int stepNo = 1; ...) {` 的循环体整体包进去）：

```java
            for (int stepNo = 1; stepNo <= maxReactSteps; stepNo++) {
                try (RunLifecycleObserver.StepScope step =
                             lifecycleObserver.beginStep(requestId, stepNo - 1, "react")) {
                    // ...既有循环体原样不动(break/continue 在 try-with-resources 里合法,
                    // close() 都会发射 step.completed)...
                }
            }
```

各引擎参数：

| 引擎 | 循环头 | stepKind | runId 变量（执行时确认） |
|---|---|---|---|
| ReAct | L244 `stepNo=1..maxReactSteps` | `"react"` | requestId |
| Reflexion | L256 `attempt=1..maxReflections` | `"reflect"` | requestId |
| PlanExecute | L256 `replanno=0..maxReplan`（只包外层） | `"replan"` | requestId |
| AutonomousLoop | L239 `stepNo=1..maxAutonomousSteps` | `"autonomous"` | requestId |
| OparLoop | L216 `stepNo=1..maxAgentSteps` | `"opar"` | requestId |

注意：循环体内的**局部变量声明**移入 try 块即可（循环外不再引用）；若循环体声明了循环外后续要用的变量（如 ReAct 的 `steps` 列表在循环前声明，不动它）。mock 的 lifecycleObserver 使 `beginStep` 返回 null——try(null) 合法（close 跳过），单测不受影响。

- [ ] **Step 2: 每引擎加验证测试**

在 `ReActEngineTest` 中新增（其余引擎测试同构，改 kind）：

```java
    @Test
    void emitsStepBoundariesPerLoopIteration() {
        RunLifecycleObserver observer = mock(RunLifecycleObserver.class);
        // 用本文件既有的可注入 factory(4 参 newReActEngine)风格,把 observer 传进去;
        // 若现有 factory 不含 observer 参数,新增一个重载:
        // newReActEngine(ModelCallExecutor, ToolOrchestrator, ModelTransportGuardService, int, RunLifecycleObserver)
        ReActEngine engine = newReActEngine(stubExecutor, stubTools, stubGuard, 6, observer);
        engine.execute(ctx(), fallback);   // 对齐本文件既有测试的 ctx()/fallback 构造

        verify(observer, atLeast(1))
                .beginStep(anyString(), anyInt(), eq("react"));
    }
```

（`stubExecutor` 等对齐该测试文件既有 stub 的名字；关键是 verify beginStep 被循环次数次调用。）

- [ ] **Step 3: 运行 + 全量回归**

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test -Dtest="ReActEngineTest,ReflexionEngineTest,PlanExecuteEngineTest,AutonomousLoopEngineTest"
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test
```
预期：全绿。

- [ ] **Step 4: Commit**

```bash
git add -A src/main src/test
git commit -m "feat(runtime): 5 范式引擎 step 边界埋点(Phase 2 内核就位后沉入内核)"
```

---

### Task 7: 双开关一致性 fail-fast

**Files:**
- Create: `src/main/java/com/springclaw/config/RuntimeStoreConsistencyGuard.java`
- Modify: `src/main/java/com/springclaw/config/RuntimeLifecycleStoreConfig.java`
- Modify: `src/test/java/com/springclaw/config/RuntimeLifecycleStoreConfigTest.java`

- [ ] **Step 1: 写失败测试**（追加到 RuntimeLifecycleStoreConfigTest）

```java
    @Test
    void rejectsDbEnabledWithNonMysqlLifecycleStore() {
        RuntimeStoreConsistencyGuard guard = new RuntimeStoreConsistencyGuard(true, "memory");
        assertThatThrownBy(guard::afterPropertiesSet)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("springclaw.runtime.lifecycle.store=mysql");
    }

    @Test
    void acceptsLegalCombinations() {
        new RuntimeStoreConsistencyGuard(true, "mysql").afterPropertiesSet();
        new RuntimeStoreConsistencyGuard(false, "memory").afterPropertiesSet();
        new RuntimeStoreConsistencyGuard(false, "mysql").afterPropertiesSet();
    }
```

- [ ] **Step 2: 运行确认失败**（类不存在，编译失败）

- [ ] **Step 3: 实现**

`RuntimeStoreConsistencyGuard.java`：

```java
package com.springclaw.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.StringUtils;

/**
 * db-enabled 与 runtime.lifecycle.store 组合一致性校验:
 * 业务表落 MySQL 而 run 事件仅存内存的半持久状态是配置事故,fail-fast。
 */
public class RuntimeStoreConsistencyGuard implements InitializingBean {

    private final boolean dbEnabled;
    private final String lifecycleStore;

    public RuntimeStoreConsistencyGuard(boolean dbEnabled, String lifecycleStore) {
        this.dbEnabled = dbEnabled;
        this.lifecycleStore = lifecycleStore;
    }

    @Override
    public void afterPropertiesSet() {
        if (dbEnabled && !"mysql".equals(StringUtils.hasText(lifecycleStore)
                ? lifecycleStore.trim().toLowerCase() : "")) {
            throw new IllegalStateException(
                    "springclaw.persistence.db-enabled=true 时必须设置 "
                            + "springclaw.runtime.lifecycle.store=mysql(当前 store="
                            + lifecycleStore + "),否则 run 事件只存内存、"
                            + "重启即失,与业务持久化形成半持久状态。"
            );
        }
    }
}
```

`RuntimeLifecycleStoreConfig` 追加：

```java
    @Bean
    public RuntimeStoreConsistencyGuard runtimeStoreConsistencyGuard(
            @org.springframework.beans.factory.annotation.Value(
                    "${springclaw.persistence.db-enabled:false}") boolean dbEnabled,
            @org.springframework.beans.factory.annotation.Value(
                    "${springclaw.runtime.lifecycle.store:memory}") String lifecycleStore
    ) {
        return new RuntimeStoreConsistencyGuard(dbEnabled, lifecycleStore);
    }
```

- [ ] **Step 4: 运行 + 全量回归**

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test -Dtest=RuntimeLifecycleStoreConfigTest
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test
```
预期：全绿（所有既有测试的开关组合均合法；若某个 @SpringBootTest 恰好用了非法组合，按报错把该测试属性补 `springclaw.runtime.lifecycle.store=mysql`）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/springclaw/config src/test/java/com/springclaw/config
git commit -m "feat(config): db-enabled/lifecycle.store 非法组合启动 fail-fast"
```

---

### Task 8: smoke 断言更新 + Phase 1 收尾回归

**Files:**
- Modify: `src/test/java/com/springclaw/controller/ChatControllerSpringBootCanonicalSmokeIT.java:228-236`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ChatControllerCanonicalHttpSmokeTest.java:125-140`
- Modify: `src/test/java/com/springclaw/service/chat/impl/AcceptedRunCanonicalSmokeTest.java:124-140`

- [ ] **Step 1: 断言改为子序列 + 终态校验**

三个 smoke 的 `containsExactly(RUN_CREATED, ..., VERIFICATION_STARTED, RUN_DEGRADED)` 改为：

```java
        assertThat(runEventStore.findEventsByRunId(RUN_ID))
                .extracting(RunEvent::eventType)
                .containsSubsequence(
                        RunEventType.RUN_CREATED,
                        RunEventType.CONTEXT_READY,
                        RunEventType.DECISION_MADE,
                        RunEventType.STRATEGY_STARTED,
                        RunEventType.TURN_STARTED,
                        RunEventType.TURN_COMPLETED,
                        RunEventType.VERIFICATION_STARTED
                )
                .endsWith(RunEventType.RUN_COMPLETED);
```

（这些 smoke 走简化引擎路径、无 step 事件、无 model.called——若实际事件流有差异，以"TURN_STARTED 在 STRATEGY_STARTED 之后、RUN_COMPLETED 结尾"为不变量，按实际输出微调子序列元素。）

- [ ] **Step 2: 全量回归**

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test
```
预期：**全绿（≥1091）**。记录 `Tests run` 数字到 commit 信息。

- [ ] **Step 3: Commit**

```bash
git add -A src/test
git commit -m "test(runtime): smoke 断言对齐 turn 边界与 run.completed 终态(Phase 1 收尾)"
```

---

# Phase 2 — 共享循环核 AgentLoopKernel

> Phase 2 每个任务开始前先 `grep -n` 重新定位锚点（行号会随 Phase 1 偏移）。内核 API 在 Task 9 定稿；Task 10-14 逐引擎迁移，**每引擎迁移后既有引擎单测不改断言全绿 = 行为不变的证明**。

### Task 9: AgentLoopKernel + LoopSpec 契约

**Files:**
- Create: `src/main/java/com/springclaw/service/agent/kernel/KernelCall.java`
- Create: `src/main/java/com/springclaw/service/agent/kernel/KernelResult.java`
- Create: `src/main/java/com/springclaw/service/agent/kernel/LoopSpec.java`
- Create: `src/main/java/com/springclaw/service/agent/kernel/AgentLoopKernel.java`
- Create: `src/test/java/com/springclaw/service/agent/kernel/AgentLoopKernelTest.java`

- [ ] **Step 1: 契约与内核实现**

`KernelCall.java`：

```java
package com.springclaw.service.agent.kernel;

import com.springclaw.service.ai.AiProviderService;
import com.springclaw.service.chat.impl.ModelCallExecutor;

/**
 * 一次内核模型调用的参数。operation 封装范式特定的 prompt 渲染与响应解析
 * (对应引擎重叠段 D/D' 的差异部分),骨架(failover/事件/守护)由内核承担。
 */
public record KernelCall<T>(
        String source,
        AiProviderService.ActiveChatClient activeClient,
        ModelCallExecutor.ChatRequestContext requestContext,
        boolean allowFailover,
        ModelCallExecutor.ChatOperation<T> operation
) {
}
```

`KernelResult.java`：

```java
package com.springclaw.service.agent.kernel;

import com.springclaw.service.chat.impl.ModelCallExecutor;

/** 内核单步产出:模型调用结果 + 是否判定为终局(假完成守护/范式终止条件)。 */
public record KernelResult<T>(
        ModelCallExecutor.ModelCallResult<T> call,
        boolean terminal,
        String terminalReason
) {
    public static <T> KernelResult<T> continuing(ModelCallExecutor.ModelCallResult<T> call) {
        return new KernelResult<>(call, false, "");
    }

    public static <T> KernelResult<T> terminal(
            ModelCallExecutor.ModelCallResult<T> call, String reason
    ) {
        return new KernelResult<>(call, true, reason);
    }
}
```

`LoopSpec.java`：

```java
package com.springclaw.service.agent.kernel;

import com.springclaw.service.chat.impl.ChatContext;

import java.util.List;

/**
 * 范式策略:只表达差异点(步语义/终止条件/答案组装),
 * 步进、边界事件、max-steps 兜底、假完成守护由 AgentLoopKernel 承担。
 */
public interface LoopSpec<S, T> {

    int maxSteps();

    String stepKind();

    S initState(ChatContext ctx);

    /** 单步语义:实现内部经 ctx.kernel().callModel(...) 发起模型调用。 */
    S nextStep(LoopContext ctx, S state, int stepIndex);

    /** 步内产出转 KernelResult(含范式终止判定,如 ReAct 无 Action 行=terminal)。 */
    KernelResult<T> evaluate(S state, int stepIndex);

    /** N 段:终局答案组装(如 OparLoop 的 reflect 文本、Reflexion 的 memory 综合)。 */
    String composeAnswer(ChatContext ctx, S state, List<KernelResult<T>> steps);
}
```

`AgentLoopKernel.java`：

```java
package com.springclaw.service.agent.kernel;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 共享循环核:步进骨架 + step 边界事件(唯一发射点) + max-steps 兜底 + 假完成守护。
 * 引擎重叠段 A/C/D/G/H/K 的下沉目标(spec §3.2)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoopKernel {

    private final ModelCallExecutor modelCallExecutor;
    private final RunLifecycleObserver lifecycleObserver;

    /** D 段:模型调用骨架(failover/事件富化已由 ModelCallExecutor 承担)。 */
    public <T> ModelCallExecutor.ModelCallResult<T> callModel(KernelCall<T> call)
            throws Exception {
        return modelCallExecutor.executeChat(
                call.activeClient(),
                call.source(),
                call.requestContext(),
                call.allowFailover(),
                call.operation()
        );
    }

    /** 步进循环骨架。step 事件唯一发射点——引擎不再自己 beginStep。 */
    public <S, T> LoopOutcome<T> runLoop(LoopSpec<S, T> spec, LoopContext ctx) {
        S state = spec.initState(ctx.chatContext());
        List<KernelResult<T>> steps = new ArrayList<>();
        for (int stepIndex = 0; stepIndex < spec.maxSteps(); stepIndex++) {
            try (RunLifecycleObserver.StepScope scope =
                         lifecycleObserver.beginStep(
                                 ctx.chatContext().requestId(), stepIndex, spec.stepKind())) {
                state = spec.nextStep(ctx, state, stepIndex);
                KernelResult<T> stepResult = spec.evaluate(state, stepIndex);
                // G 段假完成守护:空产出即终止并标记
                if (stepResult.call() != null
                        && stepResult.call().value() instanceof String text
                        && !StringUtils.hasText(text)) {
                    scope.outcome("empty_model_output");
                    steps.add(stepResult);
                    return new LoopOutcome<>(steps, true, "EMPTY_MODEL_OUTPUT", false);
                }
                steps.add(stepResult);
                if (stepResult.terminal()) {
                    scope.outcome("terminal");
                    return new LoopOutcome<>(steps, false,
                            stepResult.terminalReason(), true);
                }
            } catch (RuntimeException ex) {
                scopeOf(steps, stepIndex).outcome("failed"); // 见下方说明
                throw ex;
            }
        }
        // H 段 max-steps 兜底:非异常退出,按已达上限返回
        return new LoopOutcome<>(steps, false, "MAX_STEPS_REACHED", false);
    }

    /** N 段:终局答案组装。 */
    public <S, T> String composeAnswer(LoopSpec<S, T> spec, ChatContext ctx,
                                       LoopOutcome<T> outcome) {
        return spec.composeAnswer(ctx, outcome.steps().isEmpty() ? null
                : specStateFor(spec, ctx), outcome.steps());
    }

    private <S, T> S specStateFor(LoopSpec<S, T> spec, ChatContext ctx) {
        return spec.initState(ctx); // 默认实现占位——见 Step 2 说明,引擎可直接持有 state
    }

    public record LoopOutcome<T>(List<KernelResult<T>> steps,
                                 boolean degraded,
                                 String endReason,
                                 boolean terminalBySpec) {
    }
}
```

**说明（执行时定稿）**：`composeAnswer` 需要 `S state`，而 state 在 runLoop 内部演化——把 `LoopOutcome` 增加字段 `Object finalState`（runLoop return 前 `new LoopOutcome<>(steps, ..., state)`），`composeAnswer` 签名改 `(LoopSpec<S,T> spec, ChatContext ctx, LoopOutcome<T> outcome)` 并在实现里 `(S) outcome.finalState()`。删掉 `specStateFor` 占位。`scopeOf` 是错误的伪代码——catch 块里拿不到 scope 引用，改为在 try 内声明 `final RunLifecycleObserver.StepScope[] holder` 或直接把 scope 声明提到 try 外（`StepScope scope = lifecycleObserver.beginStep(...); try (scope) {...}`）以便 catch 中 `scope.outcome("failed")`。

`LoopContext`（同包新文件）：

```java
package com.springclaw.service.agent.kernel;

import com.springclaw.service.chat.impl.ChatContext;

/** 引擎侧上下文包:内核自身 + 引擎私有协作对象(由引擎构造,类型不透明)。 */
public record LoopContext(ChatContext chatContext, AgentLoopKernel kernel) {
}
```

- [ ] **Step 2: 内核单测（写全后再实现修正项）**

`AgentLoopKernelTest.java`（纯 JUnit + Mockito）覆盖四条骨架行为：

```java
package com.springclaw.service.agent.kernel;

import com.springclaw.runtime.bridge.RunLifecycleObserver;
import com.springclaw.service.chat.impl.ChatContext;
import com.springclaw.service.chat.impl.ModelCallExecutor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentLoopKernelTest {

    private final ModelCallExecutor executor = mock(ModelCallExecutor.class);
    private final RunLifecycleObserver observer = mock(RunLifecycleObserver.class);
    private final AgentLoopKernel kernel = new AgentLoopKernel(executor, observer);

    // 最小策略:state=List<String> 收集每步产出;evaluate 按 state 最后元素判 terminal
    private final LoopSpec<List<String>, String> spec = new LoopSpec<>() {
        @Override public int maxSteps() { return 3; }
        @Override public String stepKind() { return "test"; }
        @Override public List<String> initState(ChatContext ctx) { return new ArrayList<>(); }
        @Override public List<String> nextStep(LoopContext ctx, List<String> s, int i) {
            s.add("step-" + i);
            return s;
        }
        @Override public KernelResult<String> evaluate(List<String> s, int i) {
            return KernelResult.continuing(new ModelCallExecutor.ModelCallResult<>(
                    s.get(s.size() - 1), null, List.of(), false));
        }
        @Override public String composeAnswer(ChatContext ctx, List<String> s,
                                              List<KernelResult<String>> steps) {
            return String.join("|", s);
        }
    };

    @Test
    void stopsAtMaxStepsAndEmitsStepBoundaries() {
        AgentLoopKernel.LoopOutcome<String> outcome =
                kernel.runLoop(spec, new LoopContext(null, kernel));

        assertThat(outcome.endReason()).isEqualTo("MAX_STEPS_REACHED");
        assertThat(outcome.steps()).hasSize(3);
        verify(observer, times(3))
                .beginStep(isNull(), anyInt(), eq("test"));
    }

    @Test
    void terminalBySpecStopsEarly() {
        LoopSpec<List<String>, String> terminalSpec = new LoopSpec<>() {
            @Override public int maxSteps() { return 5; }
            @Override public String stepKind() { return "test"; }
            @Override public List<String> initState(ChatContext ctx) { return new ArrayList<>(); }
            @Override public List<String> nextStep(LoopContext ctx, List<String> s, int i) {
                s.add("x");
                return s;
            }
            @Override public KernelResult<String> evaluate(List<String> s, int i) {
                return KernelResult.terminal(new ModelCallExecutor.ModelCallResult<>(
                        "final", null, List.of(), false), "NO_ACTION_LINE");
            }
            @Override public String composeAnswer(ChatContext ctx, List<String> s,
                                                  List<KernelResult<String>> steps) {
                return "final";
            }
        };

        AgentLoopKernel.LoopOutcome<String> outcome =
                kernel.runLoop(terminalSpec, new LoopContext(null, kernel));

        assertThat(outcome.terminalBySpec()).isTrue();
        assertThat(outcome.steps()).hasSize(1);
    }

    @Test
    void emptyModelOutputTriggersFakeCompletionGuard() {
        LoopSpec<List<String>, String> blankSpec = /* 同 spec,但 evaluate 返回 value="" */ null;
        // 按 spec 结构复制,evaluate 的 ModelCallResult value 为 ""
        AgentLoopKernel.LoopOutcome<String> outcome =
                kernel.runLoop(blankSpec, new LoopContext(null, kernel));

        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.endReason()).isEqualTo("EMPTY_MODEL_OUTPUT");
    }

    @Test
    void stepFailurePropagates() {
        LoopSpec<List<String>, String> failing = /* 同 spec,nextStep 抛 IllegalStateException */ null;
        assertThatThrownBy(() -> kernel.runLoop(failing, new LoopContext(null, kernel)))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

（`/* 同 spec... */ null` 处按注释写全——复制第一个 spec 的匿名类，只改注释指明的差异。`runLoop(null ctx)` 传 chatContext=null 需 kernel 对 `ctx.chatContext().requestId()` 做 null 容忍：`String rid = ctx.chatContext() == null ? null : ctx.chatContext().requestId();`——在实现里加上，或测试里传 mock ChatContext。选后者更干净：`ChatContext chatContext = mock(ChatContext.class); when(chatContext.requestId()).thenReturn("rid-1");`——ChatContext 是 record，用 `new ChatContext(...)` 完整构造太长，直接 mock record 在 Mockito 4+ 可行。）

- [ ] **Step 3: 运行 + Commit**

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test -Dtest=AgentLoopKernelTest
```
预期：PASS。

```bash
git add src/main/java/com/springclaw/service/agent/kernel src/test/java/com/springclaw/service/agent/kernel
git commit -m "feat(kernel): AgentLoopKernel 循环核 + LoopSpec 契约(步进/边界事件/守护/兜底)"
```

---

### Task 10: ReActEngine 迁移（首个试点）

**Files:**
- Modify: `src/main/java/com/springclaw/service/chat/impl/ReActEngine.java`
- Modify: `src/test/java/com/springclaw/service/chat/impl/ReActEngineTest.java`

- [ ] **Step 1: 通读现状**——读 ReActEngine.java 全文，对照重叠报告识别：A stream 壳、C 工具前奏（L230 附近 tracker/toolContext）、D 模型调用（L261 executeChat）、G 假完成（L286 空输出 break）、H max-steps（L346）、Task 6 的 beginStep 埋点。

- [ ] **Step 2: 写迁移验证测试（先失败或不变绿不提交）**

在 ReActEngineTest 增：

```java
    @Test
    void migratedEngineStillProducesSameStepSemantics() {
        // 迁移不变量:stepKind 仍为 "react",步数与模型调用次数一致
        ReActEngine engine = newReActEngine(stubExecutor, stubTools, stubGuard, 6, observer);
        engine.execute(ctx(), fallback);
        verify(observer, atLeast(1)).beginStep(anyString(), anyInt(), eq("react"));
        // 既有全部测试不改断言全绿 = 行为不变
    }
```

- [ ] **Step 3: 迁移**

- 构造器注入 `AgentLoopKernel kernel`（+ 字段）；ReActEngineTest 的 factory 传 `new AgentLoopKernel(executor, observer)` 或 mock。
- 定义私有 `ReActSpec implements LoopSpec<ReActState, String>`：`maxSteps()=maxReactSteps`、`stepKind()="react"`、`nextStep` 内经 `ctx.kernel().callModel(new KernelCall<>("react-step-"+(i+1), activeClient, reqCtx, allowFailover, client -> {...原 L261-282 的 prompt/call lambda...}))`、`evaluate` 按 `hasActionLine(thought)` 判 terminal（无 Action 行 → `KernelResult.terminal(call, "FINAL_ANSWER")`）、`composeAnswer` 用原最终答案逻辑。
- `execute()` 主体改为 `kernel.runLoop(spec, new LoopContext(ctx, kernel))` + 原有 M 段收尾（persist/resultReturned/SSE 不动）。
- **删除** Task 6 的 beginStep 埋点与被内核替代的骨架段（G/H 守护、executeChat 直调）。
- 引擎单测**不改断言**；只允许改 factory 的构造参数。

- [ ] **Step 4: 运行 + 全量回归 + Commit**

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test -Dtest=ReActEngineTest
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test
```
预期：全绿。记录迁移前后行数（`wc -l ReActEngine.java`）进 commit 信息。

```bash
git add -A src/main src/test
git commit -m "refactor(kernel): ReActEngine 迁移至 AgentLoopKernel(556→约 2xx 行)"
```

---

### Task 11-14: 其余 4 引擎迁移（同 playbook,差异点如下）

每引擎执行同 Task 10 的 5 步（读→测试→迁移→回归→commit）。语义要点与锚点：

- [ ] **Task 11 ReflexionEngine**：`LoopSpec` state = 反思 memory 累积结构（原 L256 循环内累积的变量）；`stepKind="reflect"`；`evaluate`：达到质量阈值或 maxReflections 内最后一次反思完成 → terminal("REFLECTED_ENOUGH")；`composeAnswer` 综合首轮答案+反思修正。锚点：L256 `for (int attempt = 1; attempt <= maxReflections; attempt++)`。Reflector 的**二次模型调用**在 `nextStep` 内经第二次 `ctx.kernel().callModel(...)` 完成（内核不限制每步调用次数）。
- [ ] **Task 12 PlanExecuteEngine**：只迁**外层** replan 循环（L256），`stepKind="replan"`；内层 plan 项执行（L283）留在 `nextStep` 内部（作为一步的内部子序列，不单独发 step 事件——保持与 Phase 1 埋点一致）；`evaluate`：replan 判定不再需要 replan → terminal("PLAN_COMPLETE")；`composeAnswer` 用原 L512 附近的答案组装。
- [ ] **Task 13 AutonomousLoopEngine**：`stepKind="autonomous"`；`evaluate`：产出含 TASK_COMPLETE 标记 → terminal("TASK_COMPLETE")；母体引擎的子任务分发在 `nextStep`（一次 `callModel` 生成子任务+一次执行可合并为一步）。
- [ ] **Task 14 OparLoopEngine**：`stepKind="opar"`；每步 Plan+Act 双 `callModel`（`nextStep` 内两次内核调用）；本地短路四件套（localFallbackFirst 等布尔）经 `KernelCall` 相邻的引擎侧分支处理，不进内核；**若双调用+短路结构参数化受阻，按 spec §3.6 逃生舱保留 OparLoop 独立实现**（保留 Task 6 埋点），在 commit 信息与 spec 附录记录决策。

每任务验收同 Task 10 Step 4（引擎单测断言不动 + 全量回归绿 + 行数统计进 commit）。

---

### Task 15: Phase 2 收尾

**Files:**
- Modify: `docs/superpowers/specs/2026-08-14-event-foundation-and-loop-kernel-design.md`（附录：迁移前后事件序列对照 + 行数统计）
- 视情况清理死代码。

- [ ] **Step 1: 事件序列超集校验**——对每个迁移引擎，跑其单测中覆盖完整 run 的用例，确认 canonical 事件序列 = 迁移前序列 + turn/step 边界（I3 不变量）；把对照表写进 spec 附录。

- [ ] **Step 2: API 定稿回灌**——前 5 个迁移的经验回灌 `LoopSpec`/`KernelCall` javadoc；删除 5 引擎中已被内核完全替代的私有方法（`isSafeToRetry` 等 K 段逐字拷贝——若仍被未迁移的 BasicStream/ModelLedStream/Simplified 引擎共用，提取到 `service/chat/impl/EngineSupport` 共享类而非删净）。

- [ ] **Step 3: 终验**

```bash
MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=test mvn test
```
+ 手工冒烟（可选）：

```bash
OPENCLAW_PRIMARY_API_KEY=test-key mvn spring-boot:run
curl -s http://127.0.0.1:18080/actuator/health
```

- [ ] **Step 4: 更新 spec 验收清单勾选 + Commit**

```bash
git add -A
git commit -m "refactor(kernel): Phase 2 收尾——API 定稿/死代码清理/事件序列对照附录"
```

---

## Self-Review 记录

- **Spec 覆盖**：§2.1（Task 1/2/5/6）、§2.2（Task 3）、§2.3（Task 1 别名 + Task 2/4 payload + durationMs 规则）、§2.4（Task 6 StepScope——以 try-with-resources 替代 spec 草图的 Supplier，规避 lambda 捕获限制，语义等价且 failure 由 run.failed 承载）、§2.5（Task 7）、§2.6（零 DDL 已核实：runtime_run_event.event_type 无 CHECK 约束）、§2.7（各任务内嵌 + Task 8）、§3 全部（Task 9-15）、§4 C1-C7'（各任务"不动"约束 + C4 别名）。
- **类型一致性**：`beginStep(String,int,String)` / `StepScope.outcome(String)` / `turnStarted(String,String,Instant)` / `modelCalled(String,String,String,Instant)` 在 Task 2/3/4/5/6/9 间签名一致；`adaptTerminal(...,CompletionVerdict,...)` 与 `CompletionVerdict(Outcome,String,String)` 一致。
- **已知取舍**：① spec §2.2 说"完全无 answer 走 failed"→落地为 verifier FAIL 分支经 bridge.failed（等价）；② causationId 维持 null（eventId 由 store 后分配，无法预填——记为后续演进）；③ Task 3 Step 4 中 StepScope 静态/内部类的修正已在文中就地说明，实现以非静态内部类为准。
