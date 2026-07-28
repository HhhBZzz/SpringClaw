# Multi-Agent 范式引擎 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 `MultiAgentEngine`(Coordinator-Worker 并行协作:分解 → N worker 并行 → 聚合),接入范式地基,用户可选 `MULTI_AGENT`。

**Architecture:** `MultiAgentEngine implements AgentEngine.StreamableAgentEngine`,`paradigm()=MULTI_AGENT`。Coordinator 两次 LLM(分解 `TaskDecomposition{List<SubTask>}` BeanOutputConverter + 聚合)+ N worker 并行(`CompletableFuture`,每个单次 `ExplicitToolExecutioner`)+ 假完成守护(worker 各自 tracker + 合并证据)。流式 SSE 复用。

**Tech Stack:** Java 17, Spring Boot 5.5, Spring AI(BeanOutputConverter), JUnit 5 + Mockito + AssertJ。

**Spec:** `docs/superpowers/specs/2026-07-28-multi-agent-paradigm-engine-design.md`。范式地基 PR1-3 + ReAct + Plan-Execute + Reflexion 已在 main。

**分支:** `feat/multi-agent`(已从 main `e252b8b3` 创建)。每 Task 末 commit。

---

## 复用参考

| 复用项 | 来源 | Multi-Agent 用法 |
|---|---|---|
| ExplicitToolExecutioner(execute/hasActionLine/describeAction) | Plan-Execute Task 1 | 每个 worker 工具执行 |
| ToolReflectionSupport(getTargetClass/renderToolList) | Plan-Execute Task 1 | prompt 列工具 |
| BeanOutputConverter 结构化 | PlanExecuteEngine runPlan(Plan)/ReflexionEngine(ReflectionResult) | decompose(TaskDecomposition) |
| AutonomousExecutionTracker + satisfiesCompletionCondition | AutonomousLoop/ReAct/Plan-Execute/Reflexion | 假完成守护(worker 各自 + 合并) |
| ToolExecutionContextHolder.open/setTracker/clearTracker | ReAct/Plan-Execute/Reflexion | scope(每 worker 线程) |
| SSE 骨架 + selectAutonomousTools + isSafeToRetry + ModelCallExecutor + releaseLockOnce | ReAct/Plan-Execute/Reflexion | stream/循环 |
| 11 bean 构造 + max config | Reflexion/Plan-Execute | 骨架 |

---

## File Structure

- **Modify:** `src/main/java/com/springclaw/runtime/contract/AgentParadigm.java`(Task 1,isImplemented 加 MULTI_AGENT——最后一个范式,无占位剩余)
- **Modify:** `src/main/java/com/springclaw/service/agent/EngineSelector.java`(Task 1,LEGACY_RANK 注册 multi-agent-loop)
- **Create:** `src/main/java/com/springclaw/service/chat/impl/MultiAgentEngine.java`(Task 1-4)
- **Create:** `src/test/java/com/springclaw/service/chat/impl/MultiAgentEngineTest.java`(Task 1-4)
- **Modify:** `AgentParadigmTest` + `EngineSelectorTest`(Task 1 级联——MULTI_AGENT=true,占位测试需调整,无占位范式剩余则改测试断言"全部实现")

---

## Task 1: `MultiAgentEngine` 骨架 + 接入

**Files:**
- Modify: `AgentParadigm.java`、`EngineSelector.java`
- Create: `MultiAgentEngine.java`(骨架)、`MultiAgentEngineTest.java`

- [ ] **Step 1: Write the failing test**

Create `MultiAgentEngineTest.java`(参考 `ReflexionEngineTest`/`PlanExecuteEngineTest` mock 模式):
```java
@Test void declaresMultiAgentParadigmAndName() {
    assertThat(newMultiAgentEngine().paradigm()).isEqualTo(AgentParadigm.MULTI_AGENT);
    assertThat(newMultiAgentEngine().name()).isEqualTo("multi-agent-loop");
}
@Test void supportsWhenParadigmIsMultiAgent() {
    assertThat(newMultiAgentEngine().supports(ctxWithParadigm(AgentParadigm.MULTI_AGENT))).isTrue();
}
@Test void doesNotSupportOtherParadigms() {
    assertThat(newMultiAgentEngine().supports(ctxWithParadigm(AgentParadigm.REFLECTION))).isFalse();
}
@Test void doesNotSupportNullContext() {
    assertThat(newMultiAgentEngine().supports(null)).isFalse();
}
```

- [ ] **Step 2: Run test to verify it fails**(MultiAgentEngine 不存在)

- [ ] **Step 3: `isImplemented` 加 MULTI_AGENT**

`AgentParadigm.java`:`isImplemented()` 加 `|| this == MULTI_AGENT`(所有 7 范式实现,无占位剩余)。更新 `AgentParadigmTest`(MULTI_AGENT=true;占位测试——MULTI_AGENT 是最后占位,实现后无占位剩余,改测试断言"全部范式实现"或删除占位断言)。

- [ ] **Step 4: `LEGACY_RANK` 注册 multi-agent-loop**

`EngineSelector.java`:`"multi-agent-loop", 100`(reflexion-loop=90 之后,`Map.of` 10 对上限内)。更新 `EngineSelectorTest` 占位测试(无占位范式剩余,`selectByPlaceholderParadigmThrowsNotImplemented` 测试需调整——所有 AgentParadigm.values() 都 isImplemented,无占位可测;改测试断言所有 paradigm 不抛"未实现",或删除该测试)。

- [ ] **Step 5: `MultiAgentEngine` 骨架**

Create `MultiAgentEngine.java`(`implements AgentEngine.StreamableAgentEngine`,**参考 ReflexionEngine/PlanExecuteEngine 骨架**):
- `name()="multi-agent-loop"`、`paradigm()=MULTI_AGENT`、`priority()=10`、`supports(ctx)=ctx!=null && ctx.paradigm()==MULTI_AGENT`
- 构造:11 bean(参考 ReflexionEngine,含 `ExplicitToolExecutioner`)+ `@Value("${springclaw.chat.max-agents:5}") maxAgents`(clamp [1,8])
- `execute()`/`stream()` 占位(execute 返回降级 ChatExecutionResult;stream return null)。**循环留 Task 3。**

- [ ] **Step 6: Run test + 相关 + 全量 + Commit**

Run: `mvn test -Dtest=MultiAgentEngineTest,AgentParadigmTest,EngineSelectorTest`(PASS)
Run 全量(带 env):Expected BUILD SUCCESS。
Commit: `feat(agent): MultiAgentEngine 骨架 + isImplemented 加 MULTI_AGENT + LEGACY_RANK 注册`

---

## Task 2: `SubTask`/`TaskDecomposition`/`WorkerResult` + `decompose`/`aggregate` + prompt

**Files:**
- Modify: `MultiAgentEngine.java`
- Test: `MultiAgentEngineTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test void decomposeGeneratesSubTasks() {
    // mock ModelCallExecutor.executeChat 返回 TaskDecomposition JSON(如 {"tasks":[{"description":"搜索"},{"description":"分析"}]})
    // 调 decompose,断言 List<SubTask> size=2,description 含"搜索"/"分析"
}
@Test void aggregateCombinesWorkerResults() {
    // mock executeChat,调 aggregate(ctx, [workerResult1, workerResult2])
    // 断言 LLM 综合(含两个 worker 的 observation)
}
@Test void taskDecompositionParsesFromJson() {
    // 直连 BeanOutputConverter<TaskDecomposition> round-trip(参考 PlanExecuteEngineTest)
}
```

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: 实现 record + decompose/aggregate + prompt**

在 `MultiAgentEngine.java`:
```java
public record SubTask(String description) {}
public record TaskDecomposition(List<SubTask> tasks) {}  // BeanOutputConverter wrapper(List 泛型)
public record WorkerResult(SubTask task, String thought, String observation, boolean failed) {}

private final BeanOutputConverter<TaskDecomposition> decomposeOutputConverter;  // new BeanOutputConverter<>(TaskDecomposition.class)

List<SubTask> decompose(ChatContext ctx, Object[] tools, ActiveChatClient client) {
    // LLM(BeanOutputConverter<TaskDecomposition> + format 注入 + .responseEntity,参考 PlanExecuteEngine runPlan)
    // 要求分解为 ≤ maxAgents 个可并行子任务
}
String aggregate(ChatContext ctx, List<WorkerResult> results, ActiveChatClient client) {
    // LLM 综合所有 worker observation → 最终答案(参考 Reflexion callReflection 的 LLM 调用,但返回 String)
}
String renderDecomposePrompt(...) { /* {{INJECTION}}/{{QUESTION}}/{{TOOLS}}/{{FORMAT}} */ }
String renderAggregatePrompt(...) { /* {{INJECTION}}/{{QUESTION}}/{{WORKER_RESULTS}}(每 worker task+observation) */ }
```

- [ ] **Step 4: Run test + Commit**

Commit: `feat(agent): MultiAgentEngine SubTask/TaskDecomposition/WorkerResult + decompose/aggregate(BeanOutputConverter)`

---

## Task 3: `runMultiAgentLoop` 并行 worker + tracker 线程安全 + 守护 + SSE

**Files:**
- Modify: `MultiAgentEngine.java`
- Test: `MultiAgentEngineTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test void runMultiAgentLoopParallelsWorkersAndAggregates() {
    // mock decompose 返回 [task1, task2]
    // mock ExplicitToolExecutioner.execute(每 worker)+ aggregate
    // 断言:2 worker 执行(CompletableFuture),Observation 进 aggregate,最终答案
}
@Test void writeTaskFakeCompletionGuardAggregatedEvidence() {
    // decision riskLevel=write,workers 工具调用(合并证据)→ satisfiesCompletionCondition 通过
    // 或无证据 → 假完成降级
}
```

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: 实现 stream + execute + runMultiAgentLoop + 并行 worker + tracker 线程安全**

模仿 `ReflexionEngine`/`PlanExecuteEngine` stream + runLoop + 并行:

```java
@Override public Disposable stream(...) { /* SSE 生命周期,参考 ReflexionEngine.stream */ }
@Override public ChatExecutionResult execute(ctx, fallback) { /* 阻塞入口 → runMultiAgentLoop(ctx, null, requestId) */ }

private ChatExecutionResult runMultiAgentLoop(ChatContext ctx, SseEmitter emitter, String requestId) {
    ActiveChatClient activeClient = ctx.activeClient();
    if (activeClient == null || !activeClient.available()) { /* 降级 */ }
    Object[] tools = toolOrchestrator.selectAutonomousTools(ctx.channel(), ctx.userId(), ctx.decision());
    AutonomousExecutionTracker mainTracker = new AutonomousExecutionTracker();  // 主线程 tracker(合并 worker 证据)
    try {
        // 1. 分解
        List<SubTask> subTasks = decompose(ctx, tools, activeClient);
        if (subTasks.isEmpty()) { /* 降级 */ }
        sseEventBridge.sendTrace(emitter, ctx, "分解为 " + subTasks.size() + " 子任务", "multi-agent", "decompose", 0);
        // 2. 并行 workers
        List<CompletableFuture<WorkerResult>> futures = subTasks.stream()
            .map(task -> CompletableFuture.supplyAsync(() -> runWorker(ctx, task, tools, requestId)))
            .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<WorkerResult> results = futures.stream().map(CompletableFuture::join).toList();
        // 合并 worker tracker 证据到 mainTracker(关键线程安全——见下)
        mergeWorkerTrackers(mainTracker, results);  // 收集所有 worker 的工具证据
        // emit worker trace
        for (int i = 0; i < results.size(); i++) { sseEventBridge.sendTrace(emitter, ctx, "Worker " + (i+1) + ": " + results.get(i).task().description() + " → " + truncate(results.get(i).observation(), 200), "multi-agent", "worker", i+1); }
        // 3. 聚合
        String finalAnswer = aggregate(ctx, results, activeClient);
        sseEventBridge.sendTrace(emitter, ctx, "聚合: " + truncate(finalAnswer, 200), "multi-agent", "aggregate", 0);
        // 假完成守护(主线程,基于合并证据)
        String riskLevel = ctx.decision() == null ? "read" : ctx.decision().riskLevel();
        if ("read".equals(riskLevel) || mainTracker.satisfiesCompletionCondition(riskLevel)) {
            return finalResult(ctx, results, finalAnswer, "Multi-Agent: " + results.size() + " worker 并行后聚合", true);  // Task 4 细化
        }
        return finalResult(ctx, results, finalAnswer, "Multi-Agent: 假完成拦截(写任务无足够工具证据)", true);  // 假完成降级
    } finally {
        // worker 各自 clearTracker(在 runWorker finally)
    }
}

private WorkerResult runWorker(ChatContext ctx, SubTask task, Object[] tools, String requestId) {
    AutonomousExecutionTracker workerTracker = new AutonomousExecutionTracker();
    try (ToolExecutionContextHolder.Scope scope = ToolExecutionContextHolder.open(toolContext)) {  // worker 线程各自 scope
        ToolExecutionContextHolder.setTracker(workerTracker);
        // worker:1 轮 LLM 据 task.description → Thought+Action 文本 → ExplicitToolExecutioner.execute → Observation
        String thought = callLlmForWorker(ctx, task, tools, ctx.activeClient(), requestId);  // renderWorkerPrompt + executeChat
        boolean hasAction = explicitToolExecutioner.hasActionLine(thought);
        String observation = hasAction ? explicitToolExecutioner.execute(thought, tools, requestId) : "";
        return new WorkerResult(task, thought, observation, false);
    } catch (Exception ex) {
        return new WorkerResult(task, "", "worker 失败: " + ex.getMessage(), true);
    } finally {
        ToolExecutionContextHolder.clearTracker();
        // workerTracker 证据需保留供 mergeWorkerTrackers(存入 WorkerResult 或返回)
    }
}

private void mergeWorkerTrackers(AutonomousExecutionTracker main, List<WorkerResult> results) {
    // 关键:合并 worker tracker 证据到 main。AutonomousExecutionTracker 的证据(写/命令调用)需跨 worker 累积。
    // 方案:WorkerResult 携带 workerTracker(或其证据快照),merge 到 main。
    // 以 AutonomousExecutionTracker 实际 API 为准(可能需新增 merge 方法,或读 worker tracker 状态 + 重组 main)。
    // **先读 AutonomousExecutionTracker.java** 确认 merge 可行性(字段/方法)。若不可合并,方案 B:主线程基于 worker Observation 文本 + 一个共享 tracker(worker 线程通过 InheritableThreadLocal 或传递共享 tracker)。
}
```
**先读 `AutonomousExecutionTracker.java`(satisfiesCompletionCondition/renderFakeCompletionRejection/字段)+ `ToolExecutionContextHolder.java`(ThreadLocal,跨线程)确认 tracker 合并/共享方案**。以实际为准。

### 关键:tracker 线程安全方案(writing-plans/implementer 定)

并行 worker + ThreadLocal tracker 的矛盾。方案:
- **A(推荐)**:worker 各自 tracker + WorkerResult 携带 workerTracker + mergeWorkerTrackers 合并到 main(需 AutonomousExecutionTracker 支持 merge 或读证据重组)。
- **B**:worker 共享 main tracker(InheritableThreadLocal 或 worker 线程传递 main tracker + 同步)。
- **C**:主线程聚合后,基于 worker Observation 文本 + 单独验证(不依赖 tracker,简化)。

implementer 读 AutonomousExecutionTracker 后选可行方案。

- [ ] **Step 4: Run test + 全量 + Commit**

Run: `mvn test -Dtest=MultiAgentEngineTest`(PASS)
Run 全量(带 env):Expected BUILD SUCCESS。
Commit: `feat(agent): MultiAgentEngine runMultiAgentLoop(并行 worker + tracker 合并 + 守护 + SSE)`

---

## Task 4: `finalResult` + `resolveFinalAnswer` + trace + 全量

**Files:**
- Modify: `MultiAgentEngine.java`
- Test: `MultiAgentEngineTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test void buildsChatExecutionResultWithMultiAgentTrace() {
    // 驱动 Multi-Agent(分解 + 2 worker + 聚合),断言 ChatExecutionResult:
    //   plan = "Multi-Agent: N worker 并行后聚合"
    //   action = 每个 worker task + Thought + Observation 轨迹
    //   reflect = 最终答案(聚合)
    //   modelEnabled = true
}
```

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: 完善 `finalResult` + `resolveFinalAnswer`**

模仿 `ReflexionEngine.finalResult`/`resolveFinalAnswer`:
- `finalResult(ctx, results, finalAnswer, summary, modelEnabled)`:observe / plan=summary / action=每个 worker(task+thought+observation 轨迹,buildWorkerTrace) / reflect=finalAnswer(聚合,raw) / modelEnabled
- `resolveFinalAnswer`:优先 reflect + 兜底

- [ ] **Step 4: Run full test suite + Commit**

Run 全量(带 env):Expected BUILD SUCCESS,0 failures。
Commit: `feat(agent): MultiAgentEngine finalResult + resolveFinalAnswer + trace 完善`

---

## Self-Review

**1. Spec coverage(§4 + §7):**
- MultiAgentEngine implements StreamableAgentEngine,paradigm=MULTI_AGENT,name=multi-agent-loop → Task 1 ✓
- isImplemented MULTI_AGENT + LEGACY_RANK → Task 1 ✓
- 分解(BeanOutputConverter TaskDecomposition)+ 并行 worker(CompletableFuture + ExplicitToolExecutioner)+ 聚合 → Task 2/3 ✓
- tracker 线程安全(worker 各自 + 合并)→ Task 3 ✓
- 假完成守护(主线程,合并证据)→ Task 3 ✓
- 流式 SSE → Task 3 ✓
- timeline paradigm=MULTI_AGENT → Task 3/4 ✓
- 向后兼容(不选 MULTI_AGENT 零变更)→ Task 1 全量绿 ✓

**2. Placeholder scan:** Task 3 含"以 AutonomousExecutionTracker 实际 API 为准 + tracker 线程安全方案 implementer 定"(implementer 读源),关键代码骨架给了。非 placeholder。命令带全套 env。✓

**3. Type consistency:** `SubTask{description}`、`TaskDecomposition{tasks}`、`WorkerResult{task,thought,observation,failed}`、`paradigm()=MULTI_AGENT`、`name()="multi-agent-loop"` 跨 Task 一致。✓

---

## 风险与注意

- **Task 3 tracker 线程安全(核心复杂点)**:并行 worker + ThreadLocal tracker。implementer 必须读 `AutonomousExecutionTracker.java` + `ToolExecutionContextHolder.java` 确认 merge/共享方案(方案 A/B/C)。这是 Multi-Agent 最难点。
- **CompletableFuture 并发**:`ModelCallExecutor.executeChat` 多线程并发(确认线程安全——可能每 worker 独立 client 或同步)。
- **isImplemented MULTI_AGENT 是最后范式**:无占位范式剩余。`AgentParadigmTest`/`EngineSelectorTest` 占位测试需调整(无占位可测)。
- **max-agents clamp [1,8]**:避免过多并发 worker。
- **碰引擎核心 + 流式 SSE + 并发**:MultiAgentEngine 新引擎,implements StreamableAgentEngine + CompletableFuture。每 Task 全量测试(带全套 env)。
- **shell 空 MYSQL_* env**:全量测试命令必须显式传全套 env。
