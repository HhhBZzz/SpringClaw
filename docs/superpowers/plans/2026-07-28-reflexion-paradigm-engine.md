# Reflexion 范式引擎 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 `ReflexionEngine`(执行→反思→改进重试,跨多次尝试 + memory 累积),接入范式地基,用户可选 `REFLECTION`。

**Architecture:** `ReflexionEngine implements AgentEngine.StreamableAgentEngine`,`paradigm()=REFLECTION`。循环(1..max-reflections):执行(单次手动工具循环复用 `ExplicitToolExecutioner`)+ 反思(单次 LLM 自评 `ReflectionResult{success,critique,lesson}` BeanOutputConverter)+ memory 累积 + 终止判定(success + 假完成守护)+ 重试。流式 SSE + 假完成守护复用 AutonomousLoop 模式。

**Tech Stack:** Java 17, Spring Boot 3.5, Spring AI(BeanOutputConverter), JUnit 5 + Mockito + AssertJ。

**Spec:** `docs/superpowers/specs/2026-07-28-reflexion-paradigm-engine-design.md`。范式地基 PR1-3 + ReAct(#55/#56)+ Plan-Execute(#57)已在 main。

**分支:** `feat/reflexion`(已从 main `795c1c4e` 创建)。每 Task 末 commit。

---

## 复用参考

| 复用项 | 来源 | Reflexion 用法 |
|---|---|---|
| ExplicitToolExecutioner(execute/hasActionLine/describeAction) | Plan-Execute Task 1 提取 | 执行阶段(单次手动工具) |
| ToolReflectionSupport(getTargetClass/renderToolList) | Plan-Execute Task 1 | renderAttemptPrompt 列工具 |
| BeanOutputConverter 结构化 | PlanExecuteEngine runPlan(Plan wrapper) | 反思阶段 ReflectionResult |
| AutonomousExecutionTracker + satisfiesCompletionCondition/renderFakeCompletionRejection | AutonomousLoop/ReAct/Plan-Execute | 假完成守护 |
| ToolExecutionContextHolder.open/setTracker/clearTracker | ReAct/Plan-Execute | scope |
| SSE 骨架 + selectAutonomousTools + isSafeToRetry + ModelCallExecutor + releaseLockOnce | ReAct/Plan-Execute | stream/循环 |
| 11 bean 构造 + max config | ReAct/Plan-Execute | 骨架 |

---

## File Structure

- **Modify:** `src/main/java/com/springclaw/runtime/contract/AgentParadigm.java`(Task 1,isImplemented 加 REFLECTION)
- **Modify:** `src/main/java/com/springclaw/service/agent/EngineSelector.java`(Task 1,LEGACY_RANK 注册 reflexion-loop)
- **Create:** `src/main/java/com/springclaw/service/chat/impl/ReflexionEngine.java`(Task 1-4)
- **Create:** `src/test/java/com/springclaw/service/chat/impl/ReflexionEngineTest.java`(Task 1-4)
- **Modify:** `AgentParadigmTest` + `EngineSelectorTest`(Task 1 级联)

---

## Task 1: `ReflexionEngine` 骨架 + 接入

**Files:**
- Modify: `AgentParadigm.java`、`EngineSelector.java`
- Create: `ReflexionEngine.java`(骨架)、`ReflexionEngineTest.java`

- [ ] **Step 1: Write the failing test**

Create `ReflexionEngineTest.java`(参考 `PlanExecuteEngineTest`/`ReActEngineTest` mock 模式):
```java
@Test void declaresReflexionParadigmAndName() {
    assertThat(newReflexionEngine().paradigm()).isEqualTo(AgentParadigm.REFLECTION);
    assertThat(newReflexionEngine().name()).isEqualTo("reflexion-loop");
}
@Test void supportsWhenParadigmIsReflection() {
    assertThat(newReflexionEngine().supports(ctxWithParadigm(AgentParadigm.REFLECTION))).isTrue();
}
@Test void doesNotSupportOtherParadigms() {
    assertThat(newReflexionEngine().supports(ctxWithParadigm(AgentParadigm.REACT))).isFalse();
}
```

- [ ] **Step 2: Run test to verify it fails**(ReflexionEngine 不存在)

- [ ] **Step 3: `isImplemented` 加 REFLECTION**

`AgentParadigm.java`:`isImplemented()` 加 `|| this == REFLECTION`。更新 `AgentParadigmTest`(REFLECTION=true,占位测试改用 MULTI_AGENT——确认 MULTI_AGENT 仍占位)。

- [ ] **Step 4: `LEGACY_RANK` 注册 reflexion-loop**

`EngineSelector.java`:`"reflexion-loop", 90`(plan-execute-loop=80 之后,`Map.of` 上限内)。更新 `EngineSelectorTest` 占位测试改用 MULTI_AGENT。

- [ ] **Step 5: `ReflexionEngine` 骨架**

Create `ReflexionEngine.java`(`implements AgentEngine.StreamableAgentEngine`,**参考 PlanExecuteEngine/ReActEngine 骨架**):
- `name()="reflexion-loop"`、`paradigm()=REFLECTION`、`priority()=8`、`supports(ctx)=ctx.paradigm()==REFLECTION`
- 构造:11 bean(参考 PlanExecuteEngine,含 `ExplicitToolExecutioner`)+ `@Value("${springclaw.chat.max-reflections:3}") maxReflections`(clamp [1,5])
- `execute()`/`stream()` 占位(execute 返回降级 ChatExecutionResult;stream return null)。**循环留 Task 3。**

- [ ] **Step 6: Run test + 相关 + 全量 + Commit**

Run: `mvn test -Dtest=ReflexionEngineTest,AgentParadigmTest,EngineSelectorTest`(PASS)
Run 全量(带 env):Expected BUILD SUCCESS。
Commit: `feat(agent): ReflexionEngine 骨架 + isImplemented 加 REFLECTION + LEGACY_RANK 注册`

---

## Task 2: `renderAttemptPrompt` + `renderReflectionPrompt` + `ReflectionResult`(BeanOutputConverter)

**Files:**
- Modify: `ReflexionEngine.java`
- Test: `ReflexionEngineTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test void rendersAttemptPromptWithMemoryAndTools() {
    ReflexionEngine engine = newReflexionEngine();
    Object[] tools = ...;  // @Tool fixture
    String prompt = engine.renderAttemptPrompt(ctx, tools, "尝试1反思: 需更精确");
    assertThat(prompt).contains("Thought", "Action", "尝试1反思: 需更精确");  // 含 memory
    assertThat(prompt).contains(/* 工具名 */);
}
@Test void reflectionResultParsesFromJson() {
    // 直连 BeanOutputConverter<ReflectionResult> round-trip(参考 PlanExecuteEngineTest beanOutputConverterParsesPlanFromJson)
    BeanOutputConverter<ReflectionResult> conv = new BeanOutputConverter<>(ReflectionResult.class);
    ReflectionResult r = conv.convert("{\"success\":true,\"critique\":\"...\",\"lesson\":\"...\"}");
    assertThat(r.success()).isTrue();
}
```

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: 实现 `ReflectionResult` + 两个 prompt**

在 `ReflexionEngine.java`:
```java
public record ReflectionResult(boolean success, String critique, String lesson) {}

String renderAttemptPrompt(ChatContext ctx, Object[] tools, String memory) {
    // {{INJECTION}}/{{QUESTION}}/{{TOOLS}}(ToolReflectionSupport)/{{MEMORY}}
    // 要求 LLM 基于问题 + memory(前几次反思)输出 Thought+Action(单次尝试)
}
String renderReflectionPrompt(ChatContext ctx, String attempt, String memory) {
    // {{INJECTION}}/{{QUESTION}}/{{ATTEMPT}}(本次尝试 Thought/Action/Observation)/{{HISTORY}}(memory)
    // 要求 LLM 自评 attempt 是否达成 + 输出 ReflectionResult(success/critique/lesson)
    // 含 BeanOutputConverter format 注入(参考 PlanExecuteEngine runPlan)
}
```

- [ ] **Step 4: Run test + Commit**

Commit: `feat(agent): ReflexionEngine renderAttemptPrompt + renderReflectionPrompt + ReflectionResult(BeanOutputConverter)`

---

## Task 3: `runReflexionLoop` 循环 + 假完成守护 + 流式 SSE

**Files:**
- Modify: `ReflexionEngine.java`(填充 stream + execute + runReflexionLoop + memory)
- Test: `ReflexionEngineTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test void reflexionLoopsUntilSuccessWithMemoryAccumulation() {
    // mock ExplicitToolExecutioner.execute 返回 Observation
    // mock 反思 LLM:尝试1 success=false lesson="需改";尝试2 success=true → 终止
    // 断言:2 次尝试,第2次执行 prompt 含第1次 lesson(memory),终止
}
@Test void reflexionStopsAtMaxReflections() {
    // mock 反思总 success=false → 达 maxReflections 兜底
}
@Test void writeTaskFakeCompletionGuardRejects() {
    // decision riskLevel=write,反思 success=true 但无工具证据 → 假完成守护 → 继续
}
```

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: 实现 stream + execute + runReflexionLoop**

模仿 `PlanExecuteEngine`/`ReActEngine` stream + runReActLoop:
```java
@Override public Disposable stream(...) { /* SSE 生命周期,参考 PlanExecuteEngine.stream */ }
@Override public ChatExecutionResult execute(ctx, fallback) { /* 阻塞入口 → runReflexionLoop(ctx, null, requestId) */ }

private ChatExecutionResult runReflexionLoop(ChatContext ctx, SseEmitter emitter, String requestId) {
    ActiveChatClient activeClient = ctx.activeClient();
    if (activeClient == null || !activeClient.available()) { /* 降级 */ }
    Object[] tools = toolOrchestrator.selectAutonomousTools(ctx.channel(), ctx.userId(), ctx.decision());
    AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
    String memory = "";  // 累积反思 lessons
    String lastAttempt = "";  // 最后一次尝试 Thought/Action/Observation(用于 finalResult)
    try (ToolExecutionContextHolder.Scope scope = ToolExecutionContextHolder.open(toolContext)) {
        ToolExecutionContextHolder.setTracker(tracker);
        for (int attempt = 1; attempt <= maxReflections; attempt++) {
            sseEventBridge.sendStatus(emitter, "Reflexion 尝试 " + attempt + "/" + maxReflections);
            // 执行(单次):LLM 据 memory → Thought+Action 文本 → ExplicitToolExecutioner.execute → Observation
            String thought = callLlmForAttempt(ctx, memory, tools, activeClient, requestId);  // renderAttemptPrompt + executeChat
            boolean hasAction = explicitToolExecutioner.hasActionLine(thought);
            String observation = hasAction ? explicitToolExecutioner.execute(thought, tools, requestId) : "";
            String attemptTrace = "Thought: " + truncate(thought, 400) + "\nAction: " + explicitToolExecutioner.describeAction(thought, hasAction) + "\nObservation: " + truncate(observation, 400);
            lastAttempt = attemptTrace;
            sseEventBridge.sendTrace(emitter, ctx, "尝试 " + attempt + ": " + truncate(attemptTrace, 300), "reflexion", "attempt", attempt);
            // 反思(单次 LLM,BeanOutputConverter<ReflectionResult>)
            ReflectionResult reflection = callReflection(ctx, attemptTrace, memory, activeClient, requestId);  // renderReflectionPrompt + executeChat + BeanOutputConverter
            sseEventBridge.sendTrace(emitter, ctx, "反思 " + attempt + ": success=" + reflection.success() + " lesson=" + truncate(reflection.lesson(), 200), "reflexion", "reflect", attempt);
            // 终止判定
            if (reflection.success()) {
                String riskLevel = ctx.decision() == null ? "read" : ctx.decision().riskLevel();
                if ("read".equals(riskLevel) || tracker.satisfiesCompletionCondition(riskLevel)) {
                    return finalResult(ctx, attemptTrace, memory, "Reflexion: " + attempt + " 次尝试后成功", true);  // Task 4 细化
                }
                // 假完成:success 但无证据
                memory += "\n尝试 " + attempt + " 反思: 声称完成但无工具证据。" + tracker.renderFakeCompletionRejection(riskLevel);
                sseEventBridge.sendTrace(emitter, ctx, "假完成拦截(尝试 " + attempt + ")", "reflexion", "warning", attempt);
            } else {
                memory += "\n尝试 " + attempt + " 反思: " + reflection.lesson();  // 累积 lesson
            }
        }
        return finalResult(ctx, lastAttempt, memory, "已达 max-reflections(" + maxReflections + "),返回当前最佳尝试", true);  // 兜底
    } finally {
        ToolExecutionContextHolder.clearTracker();
    }
}

// helper:callLlmForAttempt(renderAttemptPrompt + executeChat)、callReflection(renderReflectionPrompt + executeChat + BeanOutputConverter<ReflectionResult>)、finalResult(基础版 Task 4 细化)、releaseLockOnce/isSafeToRetry(复制自 ReAct/Plan-Execute)
```
**先读 `PlanExecuteEngine.runPlanExecute`/`stream` + `ReActEngine.runReActLoop`** 确认模式。以实际为准。

- [ ] **Step 4: Run test + 全量 + Commit**

Run: `mvn test -Dtest=ReflexionEngineTest`(PASS)
Run 全量(带 env):Expected BUILD SUCCESS。
Commit: `feat(agent): ReflexionEngine runReflexionLoop 循环(执行+反思+memory+守护+SSE)`

---

## Task 4: `finalResult` + `resolveFinalAnswer` + trace + 全量

**Files:**
- Modify: `ReflexionEngine.java`
- Test: `ReflexionEngineTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test void buildsChatExecutionResultWithReflexionTrace() {
    // 驱动 Reflexion(2 次尝试成功),断言 ChatExecutionResult:
    //   plan = "Reflexion: N 次尝试"
    //   action = 每次尝试 Thought/Action/Observation 轨迹
    //   reflect = 最终答案 + memory(累积反思)
    //   modelEnabled = true
}
```

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: 完善 `finalResult` + `resolveFinalAnswer`**

模仿 `PlanExecuteEngine.finalResult`/`resolveFinalAnswer`:
- `finalResult(ctx, lastAttempt, memory, summary, modelEnabled)`:observe / plan=summary(如"Reflexion: N 次尝试后成功")/ action=lastAttempt 轨迹 / reflect=最终答案 + "\n反思 memory:\n" + memory / modelEnabled
- `resolveFinalAnswer`:优先 reflect + 兜底(参考 ReAct/Plan-Execute)

- [ ] **Step 4: Run full test suite + Commit**

Run 全量(带 env):Expected BUILD SUCCESS,0 failures。
Commit: `feat(agent): ReflexionEngine finalResult + resolveFinalAnswer + trace 完善`

---

## Self-Review

**1. Spec coverage(§4 + §7):**
- ReflexionEngine implements StreamableAgentEngine,paradigm=REFLECTION,name=reflexion-loop → Task 1 ✓
- isImplemented REFLECTION + LEGACY_RANK → Task 1 ✓
- 执行(单次 ExplicitToolExecutioner)+ 反思(ReflectionResult BeanOutputConverter)+ memory 累积 → Task 2/3 ✓
- 终止(success + 假完成守护 + max-reflections)→ Task 3 ✓
- 流式 SSE → Task 3 ✓
- timeline paradigm=REFLECTION → Task 3/4 ✓
- 向后兼容(不选 REFLECTION 零变更)→ Task 1 全量绿 ✓

**2. Placeholder scan:** Task 3 含"参考 PlanExecuteEngine/ReActEngine,以实际源为准"(implementer 读源),关键代码骨架给了。非 placeholder。命令带全套 env。✓

**3. Type consistency:** `ReflectionResult{success,critique,lesson}`、`ExplicitToolExecutioner.execute/hasActionLine/describeAction`、`paradigm()=REFLECTION`、`name()="reflexion-loop"` 跨 Task 一致。✓

---

## 风险与注意

- **复用 ExplicitToolExecutioner**:执行单次(1 轮 LLM + 1 工具)。Reflexion 跨多次尝试,每次单次执行(非 ReAct 多步)。
- **BeanOutputConverter ReflectionResult**:参考 PlanExecuteEngine Plan wrapper 模式(ReflectionResult 是单对象,不需 wrapper)。
- **memory 累积 + prompt 长度**:truncate lesson(400)+ max-reflections clamp [1,5]。
- **tracker 跨尝试复用**:AutonomousExecutionTracker 累积所有尝试证据(不每尝试重置)。
- **shell 空 MYSQL_* env**:全量测试命令必须显式传全套 env。
