# ReAct 范式引擎 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 `ReActEngine`(Thought-Action-Observation 循环,流式 SSE),接入范式地基,使用户可选 `REACT` 范式。

**Architecture:** `ReActEngine implements AgentEngine.StreamableAgentEngine`,`paradigm()=REACT`。复用 `AutonomousLoopEngine` 的流式 SSE 骨架(阻塞循环 + 手动 push + `releaseLockOnce`)+ `AutonomousExecutionTracker`(假完成守护)+ `ToolOrchestrator.selectAutonomousTools`(工具范围)+ `ModelCallExecutor`(LLM + failover)。新写:`renderReActPrompt`(Thought/Action/Observation 协议)、结构化 history 三段式、DeepSeek V4 显式 prompt 回退、Thought 显式 trace。接入:`AgentParadigm.isImplemented` 加 REACT + `EngineSelector.LEGACY_RANK` 注册 `react-loop`。

**Tech Stack:** Java 17, Spring Boot 3.5, Spring AI(`.tools()` 原生工具调用), JUnit 5 + Mockito + AssertJ。

**Spec:** `docs/superpowers/specs/2026-07-23-react-paradigm-engine-design.md`。范式地基 PR1-3 已在 main。

**分支:** `feat/react-paradigm`(已从 main `e57b09e8` 创建)。每 Task 末 commit。

---

## 复用参考(AutonomousLoopEngine,`src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java`)

| 复用项 | 来源行号 | ReAct 用法 |
|---|---|---|
| SSE stream 骨架(started trace → 循环 → answerChunks → persist → releaseLockOnce → completeEmitter;catch → fallback;return null) | L131-167 | 模仿 |
| `releaseLockOnce` CAS | L423-427 | 照搬 |
| 构造函数 11 bean + max-steps @Value | L67-93 | 照搬,换 `max-react-steps` |
| `AutonomousExecutionTracker` + `ToolExecutionContextHolder.setTracker/clearTracker` | AutonomousExecutionTracker.java + L221/235/387/390 | 照搬(假完成守护) |
| `satisfiesCompletionCondition` / `renderFakeCompletionRejection` | AutonomousExecutionTracker L165/L180 | 守护判定 + 拒绝注入 |
| `ToolOrchestrator.selectAutonomousTools` | ToolOrchestrator L106-123 | 工具范围 |
| `ModelCallExecutor.executeChat` + `ChatOperationResult` + `extractText` | ModelCallExecutor L96-125/211-220 | 每步 LLM |
| `isSafeToRetry(tools)` | L635-642 | 决定 allowFailover |
| `renderToolList` 反射 | L505-533 | prompt 列工具(若需) |
| DeepSeek 守卫骨架 | `OparLoopEngine.java` L401 | `.tools()` 前加 `supportsNativeToolCalling` 判断 |

---

## File Structure

- **Modify:** `src/main/java/com/springclaw/runtime/contract/AgentParadigm.java` — `isImplemented()` 加 REACT
- **Modify:** `src/main/java/com/springclaw/service/agent/EngineSelector.java` — `LEGACY_RANK` 注册 `react-loop`
- **Create:** `src/main/java/com/springclaw/service/chat/impl/ReActEngine.java` — 引擎主体
- **Create:** `src/test/java/com/springclaw/service/chat/impl/ReActEngineTest.java` — 单测

---

## Task 1: 接入地基 + ReActEngine 骨架(空循环)

**Files:**
- Modify: `AgentParadigm.java`、`EngineSelector.java`
- Create: `ReActEngine.java`(骨架)、`ReActEngineTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/springclaw/service/chat/impl/ReActEngineTest.java`:
```java
package com.springclaw.service.chat.impl;

import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.chat.impl.ChatContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReActEngineTest {

    @Test
    void declaresReactParadigmAndSelectorName() {
        ReActEngine engine = newReActEngine();
        assertThat(engine.paradigm()).isEqualTo(AgentParadigm.REACT);
        assertThat(engine.name()).isEqualTo("react-loop");
    }

    @Test
    void supportsWhenParadigmIsReact() {
        ReActEngine engine = newReActEngine();
        ChatContext ctx = ReactTestSupport.contextWithParadigm(AgentParadigm.REACT);
        assertThat(engine.supports(ctx)).isTrue();
    }

    @Test
    void doesNotSupportOtherParadigms() {
        ReActEngine engine = newReActEngine();
        ChatContext ctx = ReactTestSupport.contextWithParadigm(AgentParadigm.OPAR);
        assertThat(engine.supports(ctx)).isFalse();
    }

    private ReActEngine newReActEngine() {
        return ReactTestSupport.newReActEngine(); // mock 11 依赖
    }
}
```
(`ReactTestSupport` 测试 helper:构造 ReActEngine(mock 11 bean)+ `contextWithParadigm` 构造带 paradigm 的 ChatContext。先读 `AutonomousLoopEngine`/`ChatServiceImplModeTest` 的测试 helper 模式确认如何 mock 11 bean + 构造 ChatContext。)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ReActEngineTest`
Expected: 编译失败 —— `ReActEngine` 不存在。

- [ ] **Step 3: `AgentParadigm.isImplemented()` 加 REACT**

`AgentParadigm.java`(`isImplemented` L35-37):
```java
public boolean isImplemented() {
    return this == SINGLE_TURN || this == OPAR || this == AUTONOMOUS_LOOP || this == REACT;
}
```
(同步更新 `AgentParadigmTest` 若有 isImplemented 断言——加 REACT=true。)

- [ ] **Step 4: `EngineSelector.LEGACY_RANK` 注册 react-loop**

`EngineSelector.java` LEGACY_RANK map(L32-39)加 `"react-loop", 70`(在 model-led-stream=50、simplified=60 之后;具体值确保不与现有冲突)。

- [ ] **Step 5: ReActEngine 骨架**

Create `src/main/java/com/springclaw/service/chat/impl/ReActEngine.java`:
```java
package com.springclaw.service.chat.impl;

import com.springclaw.runtime.contract.AgentParadigm;
import com.springclaw.service.agent.AgentEngine;
import com.springclaw.service.ai.AiProviderService;
import com.springclaw.tool.runtime.ToolOrchestrator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ReActEngine implements AgentEngine.StreamableAgentEngine {

    private final AiProviderService aiProviderService;
    private final ToolOrchestrator toolOrchestrator;
    private final ModelTransportGuardService modelTransportGuardService;
    private final ModelCallExecutor modelCallExecutor;
    private final ConversationAdvisorSupport conversationAdvisorSupport;
    private final ChatResponsePolicyService chatResponsePolicyService;
    private final SseEventBridge sseEventBridge;
    private final ChatResultPersister chatResultPersister;
    private final ChatGuardService chatGuardService;
    private final RunLifecycleObserver lifecycleObserver;
    private final int maxReactSteps;

    public ReActEngine(AiProviderService aiProviderService,
                       ToolOrchestrator toolOrchestrator,
                       ModelTransportGuardService modelTransportGuardService,
                       ModelCallExecutor modelCallExecutor,
                       ConversationAdvisorSupport conversationAdvisorSupport,
                       ChatResponsePolicyService chatResponsePolicyService,
                       SseEventBridge sseEventBridge,
                       ChatResultPersister chatResultPersister,
                       ChatGuardService chatGuardService,
                       RunLifecycleObserver lifecycleObserver,
                       @Value("${springclaw.chat.max-react-steps:6}") int maxReactSteps) {
        this.aiProviderService = aiProviderService;
        this.toolOrchestrator = toolOrchestrator;
        this.modelTransportGuardService = modelTransportGuardService;
        this.modelCallExecutor = modelCallExecutor;
        this.conversationAdvisorSupport = conversationAdvisorSupport;
        this.chatResponsePolicyService = chatResponsePolicyService;
        this.sseEventBridge = sseEventBridge;
        this.chatResultPersister = chatResultPersister;
        this.chatGuardService = chatGuardService;
        this.lifecycleObserver = lifecycleObserver;
        this.maxReactSteps = Math.max(1, Math.min(maxReactSteps, 15));
    }

    @Override public String name() { return "react-loop"; }
    @Override public AgentParadigm paradigm() { return AgentParadigm.REACT; }
    @Override public int priority() { return 4; }
    @Override
    public boolean supports(ChatContext ctx) {
        return ctx != null && ctx.paradigm() == AgentParadigm.REACT;
    }
    @Override
    public ChatExecutionResult execute(ChatContext ctx, AgentEngine.FallbackResponder fallbackResponder) {
        // 阻塞入口:Task 3/6 填充(先返回 fallback 占位)
        return new ChatExecutionResult(
                ctx.assembled() == null ? "" : ctx.assembled().observePrompt(),
                "ReAct 阻塞入口(待实现)", "", fallbackResponder.respond("ReAct", ctx), false);
    }
    @Override
    public reactor.core.Disposable stream(ChatContext context,
                                          org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter,
                                          String lockToken,
                                          java.util.concurrent.atomic.AtomicBoolean lockReleased,
                                          java.util.concurrent.atomic.AtomicReference<reactor.core.Disposable> disposableRef,
                                          OnStreamFailure fallbackHandler) {
        // Task 3 填充(先返回 null 占位,不炸)
        return null;
    }
}
```
(读 `AutonomousLoopEngine` 构造函数 L67-93 + import 确认依赖类型;ChatContext/ChatExecutionResult 等同包。`LocalExecutionSupport` 可选,若不需本地 fallback 可省——以实际依赖为准。)

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn test -Dtest=ReActEngineTest,AgentParadigmTest,EngineSelectorTest`
Expected: PASS(paradigm/name/supports 断言;AgentParadigmTest 的 isImplemented REACT=true;EngineSelector 初始化含 react-loop)。

- [ ] **Step 7: Run full test suite(带全套 env)**

```bash
MYSQL_HOST=127.0.0.1 MYSQL_PORT=3307 MYSQL_DB=springclaw MYSQL_USER=root MYSQL_PASSWORD=root \
REDIS_HOST=127.0.0.1 REDIS_PORT=6379 OPENCLAW_PRIMARY_API_KEY=test-key mvn test
```
Expected: BUILD SUCCESS,0 failures(ReActEngine 空骨架不影响现有;选 REACT 走空 execute)。

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/springclaw/runtime/contract/AgentParadigm.java \
        src/main/java/com/springclaw/service/agent/EngineSelector.java \
        src/main/java/com/springclaw/service/chat/impl/ReActEngine.java \
        src/test/java/com/springclaw/service/chat/impl/ReActEngineTest.java \
        <AgentParadigmTest 等若改>
git commit -m "feat(agent): ReActEngine 骨架 + isImplemented 加 REACT + LEGACY_RANK 注册 react-loop"
```

---

## Task 2: `renderReActPrompt` + 结构化 history 三段式

**Files:**
- Modify: `ReActEngine.java`(加 prompt + history 渲染)
- Test: `ReActEngineTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void rendersReActPromptWithToolsHistoryAndCompletionRule() {
    ReActEngine engine = newReActEngine();
    Object[] tools = ReactTestSupport.mockTools("search", "writeFile");
    String prompt = engine.renderReActPromptForTest(ctx, tools,
            "Thought: ... Action: search\nObservation: result", "read");
    assertThat(prompt).contains("Thought", "Action", "Observation");
    assertThat(prompt).contains("search");   // 工具列表
    assertThat(prompt).contains("Thought: ... Action: search\nObservation: result"); // history 注入
}

@Test
void buildsStructuredHistoryTriple() {
    ReActEngine engine = newReActEngine();
    List<ReActStep> steps = List.of(
            new ReActStep("推理1", "search(\"q\")", "结果1"),
            new ReActStep("推理2", "writeFile(...)", "结果2"));
    String history = engine.buildReActHistoryForTest(steps);
    assertThat(history).contains("Thought: 推理1", "Action: search(\"q\")", "Observation: 结果1",
                                  "Thought: 推理2", "Action: writeFile(...)", "Observation: 结果2");
}
```
(`renderReActPromptForTest`/`buildReActHistoryForTest` 是 package-private 测试入口,或直接测 package-private 方法。`ReActStep` record(thought/action/observation)。)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ReActEngineTest`

- [ ] **Step 3: 实现 `renderReActPrompt` + `ReActStep` + `buildReActHistory`**

在 `ReActEngine.java` 加(模仿 `AutonomousLoopEngine.renderAutonomousPrompt` L431-474 + `renderToolList` L505-533):

```java
public record ReActStep(String thought, String action, String observation) {}

String renderReActPrompt(ChatContext ctx, Object[] tools, String history, String riskLevel) {
    String prompt = """
            {{INJECTION}}
            用户问题:{{QUESTION}}

            你是一个 ReAct Agent,按 Thought-Action-Observation 循环推理。
            每步先输出 Thought(推理),再决定 Action(调用一个工具)或给出最终答案。
            可用工具:
            {{TOOLS}}

            完成规则:
            {{COMPLETION_RULE}}

            历史步骤:
            {{HISTORY}}

            现在输出下一步的 Thought 与 Action(或最终答案):
            """;
    return prompt
            .replace("{{INJECTION}}", TypedContextPromptRenderer.promptPrefix(ctx))
            .replace("{{QUESTION}}", TypedContextPromptRenderer.question(ctx))
            .replace("{{TOOLS}}", renderToolList(tools))
            .replace("{{COMPLETION_RULE}}", renderReActCompletionRule(riskLevel))
            .replace("{{HISTORY}}", history == null || history.isBlank() ? "(第一轮,暂无历史)" : history);
}

String buildReActHistory(List<ReActStep> steps) {
    if (steps.isEmpty()) return "(第一轮,暂无历史)";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < steps.size(); i++) {
        ReActStep s = steps.get(i);
        sb.append("Step ").append(i + 1).append(":\n")
          .append("Thought: ").append(truncate(s.thought(), 400)).append("\n")
          .append("Action: ").append(truncate(s.action(), 400)).append("\n")
          .append("Observation: ").append(truncate(s.observation(), 400)).append("\n\n");
    }
    return sb.toString();
}
```
(`renderToolList` 从 `AutonomousLoopEngine` L505-533 复制或提取共享;`renderReActCompletionRule` 模仿 `renderCompletionRule` L482-503 给 ReAct 的"调用工具或给最终答案"规则 + write/side_effect 的实际操作要求;`truncate` helper。)

- [ ] **Step 4: Run test + Commit**

Run: `mvn test -Dtest=ReActEngineTest`(PASS)
Commit: `feat(agent): ReActEngine renderReActPrompt + 结构化 history(Thought/Action/Observation 三段式)`

---

## Task 3: `runReActLoop` 循环(主路径原生工具调用 + 终止 + max-steps)

**Files:**
- Modify: `ReActEngine.java`(填充 stream + runReActLoop + execute)
- Test: `ReActEngineTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void reactLoopRunsUntilLlmGivesFinalAnswerWithoutToolCall() {
    // mock ModelCallExecutor:第1步返回 "Thought... Action" + 工具调用;第2步返回纯文本(最终答案,无工具调用)
    // mock ToolOrchestrator.selectAutonomousTools 返回 mock 工具
    // 驱动 stream 或 runReActLoop,断言:循环 2 步,最终答案 = 第2步文本,history 含 1 个 ReActStep
}

@Test
void reactLoopStopsAtMaxReactSteps() {
    // mock LLM 每步都调工具(不终止),断言达 maxReactSteps 后降级返回
}
```
(以 ChatServiceImplModeTest/AutonomousLoop 测试的 mock 模式为准。可能需 spy ModelCallExecutor + 捕获 ChatOperation。)

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: 实现 stream + runReActLoop(模仿 AutonomousLoopEngine L131-167 + L194-411)**

```java
@Override
public reactor.core.Disposable stream(ChatContext context, SseEmitter emitter, String lockToken,
                                      AtomicBoolean lockReleased, AtomicReference<Disposable> disposableRef,
                                      OnStreamFailure fallbackHandler) {
    sseEventBridge.sendTrace(emitter, context, "ReAct 循环启动", "react", "started", 0);
    try {
        ChatExecutionResult result = runReActLoop(context, emitter, context.requestId());
        sseEventBridge.sendAnswerChunks(emitter, context, resolveFinalAnswer(result));
        chatResultPersister.persist(context, result, ...TERMINAL_RESULT...);
        lifecycleObserver.reportResult(...);  // 以 AutonomousLoop L151-154 为准
        releaseLockOnce(context, lockToken, lockReleased);
        sseEventBridge.completeEmitter(emitter, ...);
    } catch (RuntimeException ex) {
        String reason = chatResponsePolicyService.simplifyFailureReason(ex.getMessage());
        sseEventBridge.sendTrace(emitter, context, "ReAct 循环失败: " + reason, "react", "error", 0);
        fallbackHandler.handle(context, ex, emitter, lockToken, lockReleased);
    }
    return null;
}

private ChatExecutionResult runReActLoop(ChatContext ctx, SseEmitter emitter, String requestId) {
    AiProviderService.ActiveChatClient activeClient = ctx.activeClient();
    if (activeClient == null || !activeClient.available()) { /* 降级,模仿 L204-210 */ }
    Object[] tools = toolOrchestrator.selectAutonomousTools(ctx.channel(), ctx.userId(), ctx.decision());
    boolean allowFailover = isSafeToRetry(tools);  // 复用 L635-642
    List<ReActStep> steps = new ArrayList<>();
    String history = "";
    for (int stepNo = 1; stepNo <= maxReactSteps; stepNo++) {
        sseEventBridge.sendStatus(emitter, "ReAct 步骤 " + stepNo + "/" + maxReactSteps);
        String systemPrompt = renderReActPrompt(ctx, tools, history, ctx.decision().riskLevel());
        // LLM 调用(主路径:原生工具调用;Task 5 加 DeepSeek 回退)
        ModelCallExecutor.ModelCallResult<String> callResult = modelCallExecutor.executeChat(
                activeClient, "react-step-" + stepNo,
                new ModelCallExecutor.ChatRequestContext(requestId, ctx.assembled().sessionKey(), ctx.channel(), ctx.userId()),
                allowFailover,
                client -> {
                    var req = client.chatClient().prompt().system(systemPrompt).user(TypedContextPromptRenderer.question(ctx));
                    if (DeepSeekChatCompatibility.supportsNativeToolCalling(client) && tools != null && tools.length > 0) {
                        req = req.tools(tools);  // 主路径
                    }
                    var resp = conversationAdvisorSupport.apply(req, ctx.assembled().sessionKey(), ctx.userId()).call().chatResponse();
                    return new ModelCallExecutor.ChatOperationResult<>(ModelCallExecutor.extractText(resp), resp);
                });
        String thought = callResult.value();
        activeClient = callResult.client();
        boolean hasToolCall = hasNativeToolCall(callResult);  // 从 ChatResponse 判定有无工具调用(Task 5 显式路径另算)
        if (thought.isBlank()) break;
        sseEventBridge.sendTrace(emitter, ctx, "Thought: " + truncate(thought, 200), "react", "thought", stepNo);
        // Action + Observation:原生工具调用时 Spring AI 已执行,Observation 从工具结果取
        ReActStep step = extractStep(thought, callResult);  // Task 5 显式路径手动执行
        steps.add(step);
        history = buildReActHistory(steps);
        // 终止:LLM 这轮无工具调用(纯最终答案)= 完成;Task 4 加假完成守护
        if (!hasToolCall) {
            return finalResult(ctx, steps, thought, true);  // reflect = 最终答案
        }
    }
    return finalResult(ctx, steps, "已达 max-react-steps,返回当前最佳答案", true);  // max-steps 兜底
}
```
(`hasNativeToolCall`/`extractStep`/`finalResult`/`resolveFinalAnswer`/`releaseLockOnce`/`isSafeToRetry` 等 helper,模仿 AutonomousLoopEngine。以实际 AutonomousLoopEngine 源为准填充细节。)

- [ ] **Step 4: Run test + 全量 + Commit**

Run: `mvn test -Dtest=ReActEngineTest`(PASS)
Run 全量(带 env):Expected BUILD SUCCESS
Commit: `feat(agent): ReActEngine runReActLoop 循环(原生工具调用 + Observation + 终止 + max-steps)`

---

## Task 4: 假完成守护(复用 AutonomousExecutionTracker)

**Files:**
- Modify: `ReActEngine.java`(循环加 tracker + 守护)
- Test: `ReActEngineTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void writeTaskRejectsFakeCompletionWithoutToolEvidence() {
    // decision riskLevel=write,mock LLM 第1步纯文本"任务完成"(无工具调用)
    // + mock AutonomousExecutionTracker 无写证据
    // 断言:不终止,注入假完成拒绝提示,继续循环(或达 max-steps 降级)
}
```

- [ ] **Step 2: Run test to verify it fails**(当前无工具调用即完成,不校验证据)

- [ ] **Step 3: 循环加 tracker + 守护(模仿 AutonomousLoopEngine L221/235/317-345)**

在 `runReActLoop` 循环外加:
```java
AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
```
循环体用 `ToolExecutionContextHolder.Scope` 包裹(让工具包上报到 tracker):
```java
try (ToolExecutionContextHolder.Scope scope = ToolExecutionContextHolder.open()) {
    ToolExecutionContextHolder.setTracker(tracker);
    // ... LLM 调用 + 工具执行 ...
} finally {
    ToolExecutionContextHolder.clearTracker();
}
```
终止判定处(`!hasToolCall` 完成分支)加守护:
```java
if (!hasToolCall) {
    String riskLevel = ctx.decision().riskLevel();
    if ("read".equals(riskLevel) || tracker.satisfiesCompletionCondition(riskLevel)) {
        return finalResult(ctx, steps, thought, true);  // 验证通过,真完成
    }
    // 假完成:拒绝,注入提示,继续
    String rejection = tracker.renderFakeCompletionRejection(riskLevel);
    history = buildReActHistory(steps) + "\n\n" + rejection;
    sseEventBridge.sendTrace(emitter, ctx, "假完成拦截", "react", "warning", stepNo);
    continue;
}
```
(确认 `AutonomousExecutionTracker` + `ToolExecutionContextHolder` 的可见性/package,ReActEngine 在 `service.chat.impl` 同包可访问。)

- [ ] **Step 4: Run test + 全量 + Commit**

Commit: `feat(agent): ReActEngine 假完成守护(复用 AutonomousExecutionTracker,写任务验证工具证据)`

---

## Task 5: DeepSeek V4 回退(显式 prompt + Action parse + 手动工具执行)

**Files:**
- Modify: `ReActEngine.java`(非工具调用模型回退路径)
- Test: `ReActEngineTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void fallsBackToExplicitPromptWhenModelDoesNotSupportToolCalling() {
    // mock DeepSeekChatCompatibility.supportsNativeToolCalling 返回 false
    // mock LLM 输出 "Thought... Action: search(query)" 文本
    // 断言:不调 .tools(),手动执行 search 工具,Observation 进 history
}
```

- [ ] **Step 2: Run test to verify it fails**(当前 .tools() 无条件,DeepSeek V4 会失败)

- [ ] **Step 3: 实现显式 prompt 回退**

在 LLM 调用块,`!DeepSeekChatCompatibility.supportsNativeToolCalling(client)` 分支:
- 不 `.tools()`,改用 `renderReActPrompt` 的显式工具描述(工具名 + 参数 schema 内嵌 prompt)。
- LLM 输出文本含 `Action: <tool>(<args>)`,代码 parse(参考 `AgentRuntimeEngine.parseReflectionResult` L342-374 的 JSON/文本 parse 容错)。
- 手动执行工具(通过 `ToolOrchestrator` 或工具 bean 直接调)+ 手动 emit `TOOL_*`(经 `RunCoordinator` 或 SseEventBridge)。
- Observation = 工具结果,进 ReActStep。

(这是 Task 中最复杂的新写部分。可参考 `AgentRuntimeEngine` 的显式 parse 模式 + `OparLoopEngine` 的 DeepSeek 守卫 if 骨架。)

- [ ] **Step 4: Run test + 全量 + Commit**

Commit: `feat(agent): ReActEngine DeepSeek V4 显式 prompt 回退(Action parse + 手动工具执行)`

---

## Task 6: `ChatExecutionResult` + `resolveFinalAnswer` + AgentStep trace + 全量

**Files:**
- Modify: `ReActEngine.java`(结果构造 + trace 完善)
- Test: `ReActEngineTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void buildsChatExecutionResultWithReActTrace() {
    // 驱动循环,断言 ChatExecutionResult:
    //   plan = "ReAct 执行 N 步",action = Action 轨迹,reflect = 最终答案/步骤概要,modelEnabled = true
}
```

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: 完善 finalResult + resolveFinalAnswer(模仿 AutonomousLoopEngine L402-421)**

```java
private ChatExecutionResult finalResult(ChatContext ctx, List<ReActStep> steps, String finalAnswer, boolean modelEnabled) {
    String actionTrace = steps.stream()
            .map(s -> "[Step] Thought: " + truncate(s.thought(), 200) + " | Action: " + s.action())
            .collect(Collectors.joining("\n"));
    String reflect = finalAnswer + "\n步骤概要:\n" + buildReActHistory(steps);
    return new ChatExecutionResult(
            ctx.assembled() == null ? "" : ctx.assembled().observePrompt(),
            "ReAct 执行 " + steps.size() + " 步",
            actionTrace,
            reflect,
            modelEnabled);
}

private String resolveFinalAnswer(ChatExecutionResult result) {
    if (result != null && result.reflect() != null && !result.reflect().isBlank()) return result.reflect();
    // 兜底文案
}
```

- [ ] **Step 4: Run full test suite + Commit**

Run 全量(带 env):Expected BUILD SUCCESS,0 failures。
Commit: `feat(agent): ReActEngine ChatExecutionResult + resolveFinalAnswer + trace 完善`

---

## Self-Review

**1. Spec coverage(§4 + §7):**
- ReActEngine implements StreamableAgentEngine,paradigm=REACT,name=react-loop → Task 1 ✓
- isImplemented 加 REACT + LEGACY_RANK 注册 → Task 1 ✓
- ReAct 循环(Thought+Action 原生工具调用 + Observation + 终止 + max-react-steps)→ Task 3 ✓
- DeepSeek V4 显式 prompt 回退 → Task 5 ✓
- 假完成守护(AutonomousLoop 模式)→ Task 4 ✓
- timeline(MODEL_CALLED/TOOL_* 自动 emit + paradigm=REACT)→ Task 3/5 复用 ModelCallExecutor + 原生工具调用自动 emit ✓
- ChatExecutionResult → Task 6 ✓
- 向后兼容(不选 REACT 零变更)→ Task 1 全量绿 ✓

**2. Placeholder scan:** Task 3/5 含"模仿 AutonomousLoopEngine Lxxx,以实际为准"指引(implementer 读源),关键代码骨架给了。非 placeholder(给了具体方法签名 + 复用行号 + 代码片段)。命令带全套 env。✓

**3. Type consistency:** `ReActStep(thought, action, observation)`、`renderReActPrompt`/`buildReActHistory` 签名、`paradigm()=REACT`、`name()="react-loop"` 跨 Task 一致。✓

---

## 风险与注意

- **碰流式 SSE 核心**:Task 3 的 stream 模仿 AutonomousLoopEngine(阻塞循环 + 手动 SSE + return null Disposable,与 ChatServiceImpl L252-256 兼容)。每 Task 全量测试(带全套 env)。
- **AutonomousLoopEngine 复用**:Task 3/4 大量模仿 AutonomousLoopEngine 源(stream/循环/tracker/守护)。implementer 必须读 AutonomousLoopEngine 实际源(行号给到)确认细节。
- **DeepSeek V4 回退(Task 5)是新写复杂部分**:显式 prompt + Action parse + 手动工具执行 + 手动 emit TOOL_*。参考 AgentRuntimeEngine 的 parse 容错 + OparLoop 的守卫骨架。
- **工具执行审计**:原生工具调用经 ToolRuntimeAspect 自动审计;回退路径手动执行需确保也经审计(ToolExecutionContextHolder tracker 上报)。
- **测试 mock 复杂**:ReAct 涉及 ModelCallExecutor + ChatResponse + 工具调用,mock 较重。参考 ChatServiceImplModeTest/AutonomousLoop 测试模式。
- **max-react-steps**:新配置键 `springclaw.chat.max-react-steps`(默认 6),独立于 opar 的 max-steps。
- **shell 空 MYSQL_* env**:全量测试命令必须显式传全套 env。
