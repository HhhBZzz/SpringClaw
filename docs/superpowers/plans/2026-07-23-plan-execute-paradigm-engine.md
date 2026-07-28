# Plan-Execute 范式引擎 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 `PlanExecuteEngine`(先 Plan 全部多步 → 逐步 Execute 手动工具循环 → 失败 Replan),接入范式地基,用户可选 `PLAN_EXECUTE`。

**Architecture:** `PlanExecuteEngine implements AgentEngine.StreamableAgentEngine`,`paradigm()=PLAN_EXECUTE`。Plan 阶段 `BeanOutputConverter` 生成 `List<PlanStep>`;Execute 阶段逐步手动工具循环(**复用 ReAct 的手动循环——Task 1 先提取为共享 `ExplicitToolExecutioner` + `ToolReflectionSupport`,ReAct 重构 + Plan-Execute 复用**);失败 Replan。流式 SSE + 假完成守护复用 AutonomousLoop 模式。

**Tech Stack:** Java 17, Spring Boot 3.5, Spring AI(BeanOutputConverter 结构化输出), JUnit 5 + Mockito + AssertJ。

**Spec:** `docs/superpowers/specs/2026-07-23-plan-execute-paradigm-engine-design.md`。范式地基 PR1-3 + ReAct(#55) + DeepSeek 迁就删除(#56)已在 main。

**分支:** `feat/plan-execute`(已从 main `e5a37714` 创建)。每 Task 末 commit。

---

## 复用参考

| 复用项 | 来源 | Plan-Execute 用法 |
|---|---|---|
| 手动循环(executeExplicitAction/findActionLine/splitAction/findToolMethod/bindArguments 等) | `ReActEngine.java`(Task 1 提取) | Task 1 提取为 `ExplicitToolExecutioner`,Task 4 Execute 复用 |
| 反射(getTargetClass/renderToolList @Tool scan) | `ReActEngine.java`/`AutonomousLoopEngine.java` | Task 1 提取为 `ToolReflectionSupport` |
| BeanOutputConverter 结构化 plan | `OparLoopEngine.runPlan` L298-353(`PlanResult`) | Task 3 Plan 阶段(`List<PlanStep>`) |
| SSE 骨架(started → 循环 → answerChunks → persist → releaseLockOnce → completeEmitter;catch → fallback) | `ReActEngine.stream`/`AutonomousLoopEngine` L131-167 | Task 4 stream |
| AutonomousExecutionTracker 假完成守护 | `AutonomousLoopEngine` L221/235/317-345 | Task 4 Execute 守护 |
| selectAutonomousTools + isSafeToRetry + ModelCallExecutor + ToolExecutionContextHolder scope | `ReActEngine`/`AutonomousLoopEngine` | Task 4 Execute |
| 11 bean 构造 + max config | `ReActEngine`/`AutonomousLoopEngine` L67-93 | Task 2 骨架 |

---

## File Structure

- **Create:** `src/main/java/com/springclaw/service/chat/impl/ExplicitToolExecutioner.java`(Task 1,共享手动工具执行)
- **Create:** `src/main/java/com/springclaw/service/chat/impl/ToolReflectionSupport.java`(Task 1,共享反射 @Tool scan)
- **Modify:** `src/main/java/com/springclaw/service/chat/impl/ReActEngine.java`(Task 1,重构用共享;后续 Task 不动)
- **Modify:** `src/main/java/com/springclaw/runtime/contract/AgentParadigm.java`(Task 2,isImplemented 加 PLAN_EXECUTE)
- **Modify:** `src/main/java/com/springclaw/service/agent/EngineSelector.java`(Task 2,LEGACY_RANK 注册 plan-execute-loop)
- **Create:** `src/main/java/com/springclaw/service/chat/impl/PlanExecuteEngine.java`(Task 2-5)
- **Create:** `src/test/java/com/springclaw/service/chat/impl/PlanExecuteEngineTest.java`(Task 2-5)
- **Modify:** ReAct 测试(Task 1,用共享类后调整)

---

## Task 1: 提取共享 `ExplicitToolExecutioner` + `ToolReflectionSupport`(+ ReAct 重构)

**Files:**
- Create: `ExplicitToolExecutioner.java`、`ToolReflectionSupport.java`
- Modify: `ReActEngine.java`(重构用共享)
- Modify: `ReActEngineTest.java`(调整)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/springclaw/service/chat/impl/ExplicitToolExecutionerTest.java`:把 ReAct 的手动循环测试(findActionLine/splitAction/findToolMethod/bindArguments/glob 参数保留/markdown bold/未知工具)**搬到 ExplicitToolExecutionerTest**(测共享类)。例:
```java
@Test
void executesActionFromTextViaReflection() {
    ExplicitToolExecutioner exec = new ExplicitToolExecutioner();
    Object[] tools = { new FixtureTool() };  // @Tool search(query)
    String thought = "Thought: 需搜索\nAction: search(query=\"q\")";
    String observation = exec.execute(thought, tools, "req-test");
    assertThat(observation).contains("q");  // 工具执行结果
}
```
(从 ReActEngineTest 的 reactLoop*/actionParse* 测试搬——这些逻辑现在在共享类。)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ExplicitToolExecutionerTest`
Expected: 编译失败——`ExplicitToolExecutioner` 不存在。

- [ ] **Step 3: 提取 `ToolReflectionSupport`(反射 @Tool scan)**

Create `ToolReflectionSupport.java`(package `com.springclaw.service.chat.impl`):从 ReActEngine 复制 `getTargetClass`(L926-934 ish)+ `renderToolList` 的 @Tool scan 逻辑(L895-924 ish)。public 方法:`static Class<?> getTargetClass(Object bean)`、`static String renderToolList(Object[] tools)`、`static Optional<Method> findToolMethod(Object[] tools, String name)`(供 ExplicitToolExecutioner 用)。

**先读 ReActEngine 的 `getTargetClass`/`renderToolList`/`findToolMethod` 实际签名 + 行号**,提取到 ToolReflectionSupport。

- [ ] **Step 4: 提取 `ExplicitToolExecutioner`(手动循环)**

Create `ExplicitToolExecutioner.java`:从 ReActEngine 复制手动循环方法为 public/shareable:
- `String execute(String thought, Object[] tools, String requestId)`:主入口(原 `executeExplicitAction`)——findActionLine → splitAction → findToolMethod → bindArguments → 反射 invoke(via Aspect)。返回 Observation 字符串。
- 内部:`findActionLine`/`rawActionContent`/`isPrefixMarkdownCloser`/`stripMarkdownLinePrefix`/`splitAction`/`bindArguments`/`parseValue`/`splitArgs` + `ParsedAction`/`ToolMethod` record(从 ReActEngine 搬)。
- 用 `ToolReflectionSupport.getTargetClass`/`findToolMethod`。

**先读 ReActEngine 这些方法的实际签名 + 行号**,原样搬(行为不变)。

- [ ] **Step 5: ReAct 重构用共享**

`ReActEngine.java`:
- 删除搬走的手动循环方法(`executeExplicitAction`/`findActionLine`/`splitAction`/`findToolMethod`/`bindArguments`/`rawActionContent`/`stripMarkdownLinePrefix`/`parseValue`/`splitArgs`/`ParsedAction`/`ToolMethod`/`getTargetClass`/`renderToolList` 等)。
- 改用 `ExplicitToolExecutioner.execute(thought, tools, requestId)` + `ToolReflectionSupport.renderToolList(tools)`。
- 注入 `ExplicitToolExecutioner`(或 `new`,无状态)。
- `runReActLoop` 的 `String observation = hasToolCall ? executeExplicitAction(...) : ""` → `hasToolCall ? explicitToolExecutioner.execute(...) : ""`。
- `renderReActPrompt` 的 `renderToolList(tools)` → `ToolReflectionSupport.renderToolList(tools)`。

- [ ] **Step 6: ReAct 测试调整 + 全量**

ReActEngineTest:删搬走的测试(findActionLine/splitAction 等单元测试,已在 ExplicitToolExecutionerTest),保留 ReAct 循环测试(用共享类,行为不变)。

Run: `mvn test -Dtest=ExplicitToolExecutionerTest,ReActEngineTest`(应 PASS)
Run 全量(带 env):Expected BUILD SUCCESS。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/springclaw/service/chat/impl/ExplicitToolExecutioner.java \
        src/main/java/com/springclaw/service/chat/impl/ToolReflectionSupport.java \
        src/main/java/com/springclaw/service/chat/impl/ReActEngine.java \
        src/test/java/com/springclaw/service/chat/impl/ExplicitToolExecutionerTest.java \
        src/test/java/com/springclaw/service/chat/impl/ReActEngineTest.java
git commit -m "refactor(agent): 提取 ExplicitToolExecutioner + ToolReflectionSupport 共享(ReAct 重构用,Plan-Execute 将复用)"
```

---

## Task 2: `PlanExecuteEngine` 骨架 + 接入

**Files:**
- Modify: `AgentParadigm.java`、`EngineSelector.java`
- Create: `PlanExecuteEngine.java`(骨架)、`PlanExecuteEngineTest.java`

- [ ] **Step 1: Write the failing test**

Create `PlanExecuteEngineTest.java`(参考 `ReActEngineTest` mock 模式):
```java
@Test
void declaresPlanExecuteParadigmAndName() {
    assertThat(newPlanExecuteEngine().paradigm()).isEqualTo(AgentParadigm.PLAN_EXECUTE);
    assertThat(newPlanExecuteEngine().name()).isEqualTo("plan-execute-loop");
}
@Test
void supportsWhenParadigmIsPlanExecute() {
    assertThat(newPlanExecuteEngine().supports(ctxWithParadigm(AgentParadigm.PLAN_EXECUTE))).isTrue();
}
```

- [ ] **Step 2: Run test to verify it fails**(PlanExecuteEngine 不存在)

- [ ] **Step 3: `isImplemented` 加 PLAN_EXECUTE**

`AgentParadigm.java`:`isImplemented()` 加 `|| this == PLAN_EXECUTE`。更新 `AgentParadigmTest`(PLAN_EXECUTE=true,占位测试改用 REFLECTION)。

- [ ] **Step 4: `LEGACY_RANK` 注册 plan-execute-loop**

`EngineSelector.java`:`"plan-execute-loop", 80`(react-loop=70 之后)。

- [ ] **Step 5: `PlanExecuteEngine` 骨架**

Create `PlanExecuteEngine.java`(`implements AgentEngine.StreamableAgentEngine`):
- `name()="plan-execute-loop"`、`paradigm()=PLAN_EXECUTE`、`priority()=6`、`supports(ctx)=ctx.paradigm()==PLAN_EXECUTE`
- 构造:11 bean(参考 ReActEngine)+ `@Value("${springclaw.chat.max-replan:2}") maxReplan`
- `execute()`/`stream()` 占位(execute 返回降级 ChatExecutionResult;stream 返回 null)。**Plan/Execute 留 Task 3/4。**

- [ ] **Step 6: Run test + 全量 + Commit**

Run: `mvn test -Dtest=PlanExecuteEngineTest,AgentParadigmTest,EngineSelectorTest`(PASS)
Run 全量(带 env):Expected BUILD SUCCESS。
Commit: `feat(agent): PlanExecuteEngine 骨架 + isImplemented 加 PLAN_EXECUTE + LEGACY_RANK 注册`

---

## Task 3: Plan 阶段(`runPlan` + `PlanStep` + BeanOutputConverter)

**Files:**
- Modify: `PlanExecuteEngine.java`
- Test: `PlanExecuteEngineTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void runPlanGeneratesStructuredPlanSteps() {
    // mock ModelCallExecutor.executeChat 返回 JSON List<PlanStep>(如 [{"stepText":"搜索 X"},{"stepText":"综合"}])
    // 调 runPlan,断言 List<PlanStep> size=2,stepText 含 "搜索"/"综合"
}
```

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: 实现 `PlanStep` + `runPlan`**

在 `PlanExecuteEngine.java`:
```java
public record PlanStep(String stepText) {}
public record Plan(List<PlanStep> steps) {}  // BeanOutputConverter wrapper(List 泛型需 wrapper)

List<PlanStep> runPlan(ChatContext ctx, Object[] tools, String history) {
    String systemPrompt = renderPlanPrompt(ctx, tools, history);  // 要求分解为有序步骤
    ModelCallExecutor.ModelCallResult<Plan> result = modelCallExecutor.executeChat(
            activeClient, "plan-execute-plan",
            new ModelCallExecutor.ChatRequestContext(requestId, sessionKey, channel, userId),
            true,
            client -> {
                // BeanOutputConverter<Plan>(参考 OparLoopEngine.runPlan L298-353)
                var req = client.chatClient().prompt().system(systemPrompt).user(question);
                // 结构化输出:参考 OparLoop 用 BeanOutputConverter 注入 format + .responseEntity(Plan.class)
                Plan plan = ...;  // 参考 OparLoopEngine L320-344 的 BeanOutputConverter 模式
                return new ModelCallExecutor.ChatOperationResult<>(plan, chatResponse);
            });
    return result.value() == null ? List.of() : result.value().steps();
}
```
(`renderPlanPrompt`:要求 LLM 分解问题为有序可执行步骤,输出 Plan JSON。参考 OparLoopEngine 的 plan prompt + BeanOutputConverter format 注入。**先读 OparLoopEngine.runPlan L298-353 确认 BeanOutputConverter 用法**。)

- [ ] **Step 4: Run test + Commit**

Commit: `feat(agent): PlanExecuteEngine Plan 阶段(BeanOutputConverter 生成 List<PlanStep>)`

---

## Task 4: Execute 阶段 + Replan + 假完成守护 + 流式 SSE

**Files:**
- Modify: `PlanExecuteEngine.java`(填充 stream + runPlanExecute + runExecute + Replan)
- Test: `PlanExecuteEngineTest.java`

- [ ] **Step 1: Write the failing tests**

```java
@Test
void executeRunsEachPlanStepWithManualToolLoop() {
    // mock runPlan 返回 [step1, step2]
    // mock ExplicitToolExecutioner.execute(每步 LLM 据 stepText + history → Action → 执行 → Observation)
    // 断言:逐步执行,Observation 反馈,plan 推进,最终答案
}
@Test
void replanOnStepFailureAndRetry() {
    // mock step1 工具失败 → Replan(runPlan 再调)→ 新 plan → 重 Execute;max-replan=2 限制
}
```

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: 实现 `stream` + `runPlanExecute` + `runExecute` + Replan**

模仿 `ReActEngine.stream`/`runReActLoop`:

```java
@Override
public Disposable stream(ChatContext context, SseEmitter emitter, String lockToken,
                         AtomicBoolean lockReleased, AtomicReference<Disposable> disposableRef,
                         OnStreamFailure fallbackHandler) {
    // 模仿 ReActEngine.stream(SSE 生命周期:start → runPlanExecute → answerChunks → persist → releaseLockOnce → completeEmitter;catch → fallback)
}

private ChatExecutionResult runPlanExecute(ChatContext ctx, SseEmitter emitter, String requestId) {
    Object[] tools = toolOrchestrator.selectAutonomousTools(channel, userId, decision);
    AutonomousExecutionTracker tracker = new AutonomousExecutionTracker();
    try (ToolExecutionContextHolder.Scope scope = ToolExecutionContextHolder.open(toolContext)) {
        ToolExecutionContextHolder.setTracker(tracker);
        List<PlanStep> plan = runPlan(ctx, tools, "");
        for (int replan = 0; replan <= maxReplan; replan++) {
            String history = "";
            boolean failed = false;
            for (int i = 0; i < plan.size(); i++) {
                PlanStep step = plan.get(i);
                // Execute 该步:LLM 据 stepText + history → Thought+Action 文本
                String thought = callLlmForStep(ctx, step, history, tools, ...);
                boolean hasAction = explicitToolExecutioner.hasActionLine(thought);  // 或 findActionLine
                String observation = hasAction ? explicitToolExecutioner.execute(thought, tools, requestId) : "";
                history += buildStepHistory(step, thought, observation);  // plan 步 + Thought + Observation
                if (stepFailed(observation, thought)) { failed = true; break; }  // 失败判定
            }
            if (!failed) {
                // 假完成守护(写/副作用验证)
                if ("read".equals(riskLevel) || tracker.satisfiesCompletionCondition(riskLevel)) {
                    return finalResult(ctx, plan, history, true);
                }
                // 假完成 → 进 Replan(带 rejection)
                history += tracker.renderFakeCompletionRejection(riskLevel);
            }
            // Replan:带失败/假完成反馈重新 plan
            plan = runPlan(ctx, tools, history + "\n上次失败/未完成,请调整 plan");
        }
        return finalResult(ctx, plan, "已达 max-replan,返回当前最佳结果", true);  // max-replan 兜底
    } finally {
        ToolExecutionContextHolder.clearTracker();
    }
}
```
(helper:`callLlmForStep` Execute 每步 LLM + renderExecutePrompt(stepText + history);`buildStepHistory`;`stepFailed` 判定;`finalResult`;`releaseLockOnce`/`isSafeToRetry`/`resolveFinalAnswer` 复制自 ReAct/AutonomousLoop。**先读 ReActEngine.stream/runReActLoop + AutonomousLoopEngine 假完成守护 L317-345 确认模式**。)

- [ ] **Step 4: Run test + 全量 + Commit**

Run: `mvn test -Dtest=PlanExecuteEngineTest`(PASS)
Run 全量(带 env):Expected BUILD SUCCESS。
Commit: `feat(agent): PlanExecuteEngine Execute + Replan + 假完成守护 + 流式 SSE`

---

## Task 5: 结果 + trace + 全量

**Files:**
- Modify: `PlanExecuteEngine.java`(`finalResult` + `resolveFinalAnswer` + trace)
- Test: `PlanExecuteEngineTest.java`

- [ ] **Step 1: Write the failing test**

```java
@Test
void buildsChatExecutionResultWithPlanExecuteTrace() {
    // 驱动 Plan + Execute,断言 ChatExecutionResult:
    //   plan = "Plan-Execute: Plan N 步,Execute M 步"
    //   action = plan + Execute 轨迹(每步 stepText + Action + Observation)
    //   reflect = 最终答案 + 步骤概要
    //   modelEnabled = true
}
```

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: `finalResult` + `resolveFinalAnswer` + trace**

模仿 `ReActEngine.finalResult`/`resolveFinalAnswer`:
- `finalResult(ctx, plan, history, modelEnabled)`:observe=observePrompt / plan="Plan-Execute: Plan N 步,Execute ..." / action=plan + Execute 轨迹 / reflect=最终答案 + 步骤概要 / modelEnabled
- `resolveFinalAnswer`:优先 reflect + 兜底
- SSE trace:Plan 阶段 trace + Execute 每步 trace(Thought/Action/Observation)

- [ ] **Step 4: Run full test suite + Commit**

Run 全量(带 env):Expected BUILD SUCCESS,0 failures。
Commit: `feat(agent): PlanExecuteEngine ChatExecutionResult + resolveFinalAnswer + trace`

---

## Self-Review

**1. Spec coverage(§4 + §7):**
- PlanExecuteEngine implements StreamableAgentEngine,paradigm=PLAN_EXECUTE,name=plan-execute-loop → Task 2 ✓
- isImplemented PLAN_EXECUTE + LEGACY_RANK → Task 2 ✓
- Plan 阶段(BeanOutputConverter List<PlanStep>)→ Task 3 ✓
- Execute 阶段(逐步手动循环复用 ExplicitToolExecutioner)→ Task 4 ✓
- Replan(失败/假完成 → 重新 plan,max-replan)→ Task 4 ✓
- 假完成守护(AutonomousLoop 模式)→ Task 4 ✓
- 流式 SSE → Task 4 ✓
- 提取共享(ExplicitToolExecutioner + ToolReflectionSupport,ReAct 重构)→ Task 1 ✓
- timeline(MODEL_CALLED/TOOL_* + paradigm=PLAN_EXECUTE)→ Task 4/5 ✓
- 向后兼容(不选 PLAN_EXECUTE 零变更,ReAct 重构行为不变)→ Task 1/2 全量绿 ✓

**2. Placeholder scan:** Task 3/4 含"参考 OparLoopEngine L298-353 / ReActEngine / AutonomousLoop L317-345,以实际源为准"(implementer 读源),关键代码骨架给了。非 placeholder。命令带全套 env。✓

**3. Type consistency:** `PlanStep{stepText}`、`Plan{steps}`、`ExplicitToolExecutioner.execute(thought, tools, requestId)`、`paradigm()=PLAN_EXECUTE`、`name()="plan-execute-loop"` 跨 Task 一致。✓

---

## 风险与注意

- **Task 1 提取共享重构 ReAct**:ReAct 手动循环搬共享类,ReAct 行为须不变(逻辑相同)。ReAct 测试调整 + 全量绿。这是前置(Plan-Execute Task 4 复用)。
- **BeanOutputConverter List 泛型(Task 3)**:`List<PlanStep>` 可能需 wrapper record(`Plan{List<PlanStep> steps}`,因 Java 泛型擦除)。参考 OparLoopEngine PlanResult(单对象)。writing-plans/implementer 确认。
- **Replan 从头(简化)**:失败/假完成 → 重新 plan 从头(非从失败步)。简单,plan 重新生成。
- **Execute 每步手动循环(Task 4)**:复用 ExplicitToolExecutioner(Task 1 提取)。每步 LLM 据 stepText + history → Action → executeExplicitAction。
- **碰引擎核心 + 流式 SSE**:PlanExecuteEngine 新引擎,implements StreamableAgentEngine(SSE 生命周期参考 ReAct/AutonomousLoop)。每 Task 全量测试(带全套 env)。
- **shell 空 MYSQL_* env**:全量测试命令必须显式传全套 env。
