# Reflexion 范式引擎 — 设计

**Date:** 2026-07-28
**Status:** Design(brainstorming 产出,待 writing-plans)
**Parent:** 范式级切换地基(spec `2026-07-21-agent-paradigm-switching-foundation-design.md`,PR1-3 + ReAct + Plan-Execute 已在 main)

## 1. 目标

实现 **Reflexion 范式引擎**——试错 + 学习:执行任务尝试 → LLM 自评反思(批评 + 经验教训)→ 带反思 memory 改进重试,跨多次尝试迭代逼近目标。接入范式地基,使用户可选 `REFLECTION` 范式。

这是范式可切换产品愿景的**第三个增量新范式引擎**(地基 spec §8.3,前两个是 ReAct/Plan-Execute)。区别:Reflexion **跨多次尝试 + 反思改进**(verbal RL,语言反馈 memory 累积)——ReAct 是单次循环内每步推理,Plan-Execute 是单次 plan+execute,Reflexion 是"试错 + 学习"。

## 2. 现状

**范式地基 + ReAct + Plan-Execute 已在 main**:
- `AgentParadigm` 枚举,`REFLECTION("反思改进")` 占位,`isImplemented()` 目前 `SINGLE_TURN/OPAR/AUTONOMOUS_LOOP/REACT/PLAN_EXECUTE` 才 true。
- `EngineSelector.select(ctx, paradigm)`、`ChatRequest/ChatContext.paradigm`、`RunState/RunEvent` 记 paradigm、timeline 透出。
- ReAct(#55)+ DeepSeek 迁就删除(#56,ReAct 手动循环为主)+ Plan-Execute(#57)已合。
- **共享手动循环**(Plan-Execute Task 1 提取):`ExplicitToolExecutioner`(@Component,`execute`/`hasActionLine`/`describeAction` + Action parse/反射执行 via Aspect)+ `ToolReflectionSupport`(static,`getTargetClass`/`renderToolList`/`findToolMethod`)。Reflexion 执行阶段复用。
- `AutonomousExecutionTracker`(假完成守护,`satisfiesCompletionCondition`/`renderFakeCompletionRejection`)+ `ToolExecutionContextHolder` scope + `selectAutonomousTools`/`isSafeToRetry` + `ModelCallExecutor`(LLM + failover + emit MODEL_CALLED)+ SSE 骨架(ReAct/Plan-Execute/AutonomousLoop)。
- DeepSeek 迁就已删(#56),所有引擎直接 `.tools()` 或手动循环(模型无关)。

## 3. 方案

新建 `ReflexionEngine implements StreamableAgentEngine`,`paradigm()=REFLECTION`。循环:执行(单次手动工具循环,复用 `ExplicitToolExecutioner`)→ 反思(单次 LLM 自评 + 经验教训 memory,BeanOutputConverter 结构化)→ 终止判定(success + 假完成守护)→ memory 累积重试。流式 SSE + 假完成守护复用 AutonomousLoop 模式。

## 4. 设计

### 4.1 `ReflexionEngine` 类

- `implements StreamableAgentEngine`(SSE 流式)。
- `name()="reflexion-loop"`、`paradigm()=REFLECTION`、`priority()=8`(plan-execute-loop=6 之后,simplified=10 之前)。
- `supports(ChatContext ctx)`:返回 `ctx.paradigm() == AgentParadigm.REFLECTION`。
- 构造函数复用 ReAct/Plan-Execute 的 11 bean 依赖 + `ExplicitToolExecutioner`(Plan-Execute Task 1 提取)+ `@Value("${springclaw.chat.max-reflections:3}") maxReflections`(clamp [1,5])。

### 4.2 Reflexion 循环(`stream`/`execute`)

`runReflexionLoop(ctx, emitter, requestId)`,循环 `1..maxReflections`,每次(一次"尝试 + 反思"):

1. **执行**(单次手动工具循环):
   - LLM 据 问题 + memory(累积反思 lessons)→ 输出 `Thought + Action` 文本(系统 prompt `renderAttemptPrompt` 要求基于 memory 尝试解决问题)。
   - `ExplicitToolExecutioner.execute(thought, tools, requestId)`(复用共享:Action parse → 反射执行 via Aspect → Observation)。
   - 经 `ToolExecutionContextHolder.open(toolContext)` scope + `AutonomousExecutionTracker`(假完成守护证据上报)。
2. **反思**(单次 LLM,BeanOutputConverter 结构化):
   - LLM 自评尝试(问题 + 尝试的 Thought/Action/Observation + 历史 memory)→ `ReflectionResult{boolean success, String critique, String lesson}`(success=是否达成目标,critique=自我批评/哪里不足,lesson=经验教训/下次如何改进)。
   - 经 `ModelCallExecutor.executeChat`(emit MODEL_CALLED + failover)。
3. **终止判定**:
   - 若 `reflection.success` && 假完成守护通过(`read` 或 `tracker.satisfiesCompletionCondition(riskLevel)`)→ 返回 `finalResult`(真完成,带验证证据)。
   - 否则 `memory += "\n尝试 N 反思:" + reflection.lesson`,进下一次尝试。
   - 假完成:success=true 但无工具证据(write 类)→ 不终止,memory 加 `renderFakeCompletionRejection` + 继续(复用 AutonomousLoop 模式)。
4. **max-reflections 兜底**:达上限返回当前最佳尝试的 `finalResult`(标注"达 max-reflections,返回当前最佳")。

### 4.3 反思 memory(verbal RL 核心)

- `String memory`(累积):每次反思的 lesson 拼接("尝试 1 反思:... 尝试 2 反思:...")。
- 进下一次执行的 prompt(`renderAttemptPrompt` 的 `{{MEMORY}}` 占位符)——LLM 看到前几次的反思,据此改进。
- 进反思的 prompt(`renderReflectionPrompt` 的 `{{HISTORY}}`)——反思看到全部历史尝试 + memory。

### 4.4 安全(AutonomousLoop 模式,复用)

- 工具范围:`selectAutonomousTools` 按 intent/riskLevel。
- 假完成守护:`AutonomousExecutionTracker` + `satisfiesCompletionCondition`/`renderFakeCompletionRejection`(写/副作用任务 success 时验证工具证据)。Tracker 跨尝试复用(累积真实证据,与 AutonomousLoop/ReAct/Plan-Execute 一致)。

### 4.5 流式 SSE(复用 ReAct/Plan-Execute)

`stream(...)` 模仿 `ReActEngine`/`PlanExecuteEngine.stream`:start trace → runReflexionLoop → answerChunks → persist → releaseLockOnce → completeEmitter;catch → fallback。每次尝试 + 反思 emit Thought/Action/Observation/reflection trace(教学透明)。

### 4.6 timeline

- emit `MODEL_CALLED`(执行 + 反思 LLM)+ `TOOL_*`(执行,经 Aspect 自动)。`RunEvent` 带 `paradigm=REFLECTION`(PR3 event() 焊接)。
- 前端(codex):按 paradigm=REFLECTION 语义化(attempt/reflect/improve 阶段)。

### 4.7 接入地基

1. `AgentParadigm.isImplemented()` 加 `REFLECTION`。
2. `EngineSelector.LEGACY_RANK` 注册 `"reflexion-loop"`(如 90,plan-execute-loop=80 之后;`Map.of` 上限内)。
3. `ChatServiceImpl`:Reflexion 走 `StreamableAgentEngine.stream()` 分发(已有)。
4. `ChatRoutingPolicyService.paradigmToExecutionMode`:REFLECTION→`"opar"`(已由"其余→opar"覆盖;supports 不依赖 executionMode)。

## 5. 不做(YAGNI)

- 并排对比 UI(后续子项目)。
- Evaluator + Reflector 分离(经典 Reflexion 双 LLM)——用单次 LLM 自评+反思(简化)。
- 外部验证器(规则判成败)——用 LLM 自评 + 假完成守护(工具证据)。
- 跨 session memory 持久化(Reflexion memory 仅 run 内)。
- 框架级切换 / 范式矩阵(愿景 2/3 阶段)。
- 多智能体(独立 spec)。

## 6. 向后兼容

- REFLECTION 之前是占位(`isImplemented()` false,选时抛"未实现")。加 `isImplemented` 后,用户显式选 REFLECTION 才触发 ReflexionEngine;不选时现有引擎/路由零变更。
- ReflexionEngine 是新类,不影响现有引擎。
- 复用 `ExplicitToolExecutioner`/`AutonomousExecutionTracker`/`ToolReflectionSupport`(已提取共享,不改)。

## 7. 验收

- 用户可选 REFLECTION(API `paradigm=REFLECTION` / 前端选择器),run 由 ReflexionEngine 执行。
- 执行(单次手动工具循环,ExplicitToolExecutioner)+ 反思(LLM 自评 + lesson memory)+ 重试(带 memory)循环至 success 或 max-reflections。
- 写/副作用任务假完成守护(success 需工具证据,否则不终止)。
- memory 累积(每次反思 lesson 进下次执行 prompt)。
- timeline 流式 emit MODEL_CALLED/TOOL_*,带 paradigm=REFLECTION。
- 不选 REFLECTION 时,现有行为不变(全量测试绿)。

## 8. 实现切片建议(writing-plans 细化)

- **切片 1**:ReflexionEngine 骨架(implements StreamableAgentEngine,name/paradigm/priority/supports)+ isImplemented REFLECTION + LEGACY_RANK 注册 + 单测。
- **切片 2**:renderAttemptPrompt + renderReflectionPrompt + ReflectionResult record(BeanOutputConverter)+ 单测。
- **切片 3**:runReflexionLoop 循环(执行 ExplicitToolExecutioner + 反思 BeanOutputConverter + memory 累积 + 终止判定 + 假完成守护)+ 流式 SSE + max-reflections 兜底 + 单测。
- **切片 4**:finalResult + resolveFinalAnswer + trace + 全量。

---

## 风险与注意

- **BeanOutputConverter ReflectionResult**:`ReflectionResult{success, critique, lesson}` 结构化(参考 PlanExecuteEngine Plan wrapper + OparLoopEngine PlanResult 模式)。boolean success + String critique/lesson。
- **memory 累积 + prompt 长度**:多次反思 memory 拼接,prompt 可能变长。truncate lesson(如 400 字)+ max-reflections clamp [1,5] 控制。
- **复用 ExplicitToolExecutioner**:执行单次(1 轮 LLM + 1 工具调用)。ExplicitToolExecutioner.execute(thought, tools, requestId) 返回 Observation。
- **假完成守护 tracker 跨尝试复用**:AutonomousExecutionTracker 累积所有尝试的工具证据(不每尝试重置),与 AutonomousLoop/Plan-Execute 一致。
- **碰引擎核心 + 流式 SSE**:ReflexionEngine 新引擎,implements StreamableAgentEngine(SSE 生命周期参考 ReAct/Plan-Execute)。每切片全量测试(带全套 env)。
- **shell 空 MYSQL_* env**:全量测试命令必须显式传全套 env。
