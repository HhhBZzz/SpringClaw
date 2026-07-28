# Multi-Agent 范式引擎 — 设计

**Date:** 2026-07-28
**Status:** Design(brainstorming 产出,待 writing-plans)
**Parent:** 范式级切换地基(spec `2026-07-21-agent-paradigm-switching-foundation-design.md`,PR1-3 + ReAct + Plan-Execute + Reflexion 已在 main)

## 1. 目标

实现 **Multi-Agent 范式引擎**——Coordinator-Worker 多 agent 并行协作:Coordinator 分解任务 → N 个 worker agent 并行执行子任务 → Coordinator 聚合结果。接入范式地基,使用户可选 `MULTI_AGENT` 范式。

这是范式可切换产品愿景的**第四个也是最后一个增量新范式引擎**(地基 spec §8.4,前三个 ReAct/Plan-Execute/Reflexion 已合)。区别单 agent 范式:Multi-Agent **多 agent 并行协作**(分解 + 并行 workers + 聚合)。

## 2. 现状

**范式地基 + 3 增量范式已合 main**:
- `AgentParadigm` 枚举,`MULTI_AGENT("多智能体")` **最后一个占位**,`isImplemented()` 目前 `SINGLE_TURN/OPAR/AUTONOMOUS_LOOP/REACT/PLAN_EXECUTE/REFLECTION` 才 true。
- `EngineSelector.select(ctx, paradigm)`、paradigm 全链透传、`RunState/RunEvent` 记 paradigm、timeline 透出。
- ReAct(#55/#56)+ Plan-Execute(#57,提取共享 `ExplicitToolExecutioner`/`ToolReflectionSupport`)+ Reflexion(#58)已合。
- **共享**:`ExplicitToolExecutioner`(@Component,手动工具循环)+ `ToolReflectionSupport`(static)+ `AutonomousExecutionTracker`(假完成守护)+ `ToolExecutionContextHolder`(scope,ThreadLocal tracker)+ `selectAutonomousTools`/`isSafeToRetry`/`ModelCallExecutor` + SSE 骨架。
- 3 引擎模式:`implements StreamableAgentEngine`,复用 AutonomousLoop SSE + 守护 + 11 bean。

## 3. 方案

新建 `MultiAgentEngine implements StreamableAgentEngine`,`paradigm()=MULTI_AGENT`。Coordinator 两次 LLM(分解 `List<SubTask>` + 聚合)+ N worker 并行(`CompletableFuture`,每个单次 `ExplicitToolExecutioner`)+ 假完成守护。流式 SSE 复用。

## 4. 设计

### 4.1 `MultiAgentEngine` 类

- `implements StreamableAgentEngine`。
- `name()="multi-agent-loop"`、`paradigm()=MULTI_AGENT`、`priority()=10`(reflexion-loop=8 之后)。
- `supports(ChatContext ctx)`:返回 `ctx.paradigm() == AgentParadigm.MULTI_AGENT`。
- 构造函数复用 11 bean(含 `ExplicitToolExecutioner`)+ `@Value("${springclaw.chat.max-agents:5}") maxAgents`(clamp [1,8],worker 数量上限)。

### 4.2 Coordinator-Worker 流程(3 阶段)

`runMultiAgentLoop(ctx, emitter, requestId)`:

1. **分解**(Coordinator,LLM,BeanOutputConverter):
   - `List<SubTask{description}> decompose(ctx, tools)`:`BeanOutputConverter<List<SubTask>>`(或 wrapper `TaskDecomposition{List<SubTask> tasks}` 解泛型擦除,参考 Plan-Execute Plan wrapper)。要求 LLM 分解为可并行子任务,数量 ≤ maxAgents。
   - emit `MODEL_CALLED`(经 ModelCallExecutor)。
2. **并行 workers**:
   - 每个 SubTask → `CompletableFuture<WorkerResult>`(提交线程池/`CompletableFuture.supplyAsync`):
     - worker:1 轮 LLM 据 subTask.description(+ 共享上下文)→ Thought+Action 文本 → `ExplicitToolExecutioner.execute`(手动工具,复用共享)→ Observation。
     - worker 各自经 `ToolExecutionContextHolder.open(toolContext)` scope(每线程 ThreadLocal)+ 各自 `AutonomousExecutionTracker`(setTracker)。
   - `CompletableFuture.allOf(...)` 等所有 worker 完成。
   - emit 每个 worker 的 Thought/Action/Observation trace(SSE,worker 完成时发)。
3. **聚合**(Coordinator,LLM):
   - `String aggregate(ctx, List<WorkerResult>)`:LLM 综合所有 worker 的 Observation → 最终答案。
   - emit `MODEL_CALLED`。

### 4.3 假完成守护 + tracker 线程安全(关键复杂点)

`AutonomousExecutionTracker` 经 `ToolExecutionContextHolder`(ThreadLocal)。并行 worker 多线程:

- **方案**:每个 worker 线程各自 `ToolExecutionContextHolder.open + setTracker(new AutonomousExecutionTracker())`(各 ThreadLocal tracker)。worker 完成后,**合并 worker tracker 证据到主线程 tracker**(或主线程聚合后,收集所有 worker 的工具调用证据)。
- **守护判定**(聚合后,主线程):聚合答案 + read/tracker.satisfiesCompletionCondition(riskLevel)(基于合并证据)→ 完成;否则降级/Replan(简化:降级返回,不做 Replan——Multi-Agent 范围内 YAGNI)。
- **替代方案**(若合并复杂):假完成守护在主线程基于所有 worker 的 Observation 文本 + 一个共享 tracker(worker 线程共享主 tracker——但 ToolExecutionContextHolder 是 ThreadLocal,跨线程需 InheritableThreadLocal 或传递)。writing-plans 阶段定方案(推荐:worker 各自 tracker + 合并证据到主 tracker)。

### 4.4 `SubTask` + `WorkerResult`

- `record SubTask(String description)`(子任务描述,worker 据此执行)。
- `record TaskDecomposition(List<SubTask> tasks)`(BeanOutputConverter wrapper,解 List 泛型擦除)。
- `record WorkerResult(SubTask task, String thought, String observation, boolean failed)`(worker 执行结果)。

### 4.5 安全 + 流式(复用)

- 工具范围:`selectAutonomousTools` 按 intent/riskLevel(所有 worker 共享同一工具范围)。
- 假完成守护:AutonomousLoop 模式(写/副作用验证工具证据,跨所有 worker 合并)。
- 流式 SSE:Coordinator 分解/聚合 trace + 每个 worker 完成 trace Thought/Action/Observation。

### 4.6 timeline

- emit `MODEL_CALLED`(Coordinator 分解/聚合 + 每个 worker)+ `TOOL_*`(workers,经 Aspect 自动)。`RunEvent` 带 `paradigm=MULTI_AGENT`。
- 前端(codex):按 paradigm=MULTI_AGENT 语义化(decompose/worker-N/aggregate)。

### 4.7 接入地基

1. `AgentParadigm.isImplemented()` 加 `MULTI_AGENT`(最后一个范式实现,无占位范式剩余)。
2. `EngineSelector.LEGACY_RANK` 注册 `"multi-agent-loop"`(如 100,`Map.of` 上限内)。
3. `ChatServiceImpl`:Multi-Agent 走 `StreamableAgentEngine.stream()` 分发(已有)。
4. `ChatRoutingPolicyService.paradigmToExecutionMode`:MULTI_AGENT→`"opar"`(已由"其余→opar"覆盖;supports 不依赖 executionMode)。

## 5. 不做(YAGNI)

- 并排对比 UI(后续子项目)。
- Worker 间通信/依赖(worker 各自独立,无依赖——并行简化)。
- Worker 用不同范式(worker 统一单次执行,非 ReAct/Plan-Execute 嵌套)。
- Replan(聚合失败降级,不重试分解——Multi-Agent 范围内简化)。
- 动态 worker 数量(Coordinator LLM 决定 N,但 ≤ maxAgents 上限)。
- 跨 session agent memory(Multi-Agent 仅 run 内)。
- 框架级切换 / 范式矩阵(愿景 2/3 阶段)。

## 6. 向后兼容

- MULTI_AGENT 之前是占位(`isImplemented()` false,选时抛"未实现")。加 `isImplemented` 后,用户显式选 MULTI_AGENT 才触发 MultiAgentEngine;不选时现有引擎/路由零变更。
- MultiAgentEngine 是新类,不影响现有引擎。
- 复用 `ExplicitToolExecutioner`/`AutonomousExecutionTracker`/`ToolReflectionSupport`(不改)。

## 7. 验收

- 用户可选 MULTI_AGENT(API `paradigm=MULTI_AGENT` / 前端选择器),run 由 MultiAgentEngine 执行。
- Coordinator 分解任务为 N 子任务(≤ maxAgents)+ workers 并行执行(单次 ExplicitToolExecutioner)+ Coordinator 聚合。
- 写/副作用任务假完成守护(聚合后验证工具证据)。
- timeline 流式 emit MODEL_CALLED/TOOL_*,带 paradigm=MULTI_AGENT。
- 不选 MULTI_AGENT 时,现有行为不变(全量测试绿)。
- 并行 worker 线程安全(tracker 各线程 + 合并,无竞争/丢失证据)。

## 8. 实现切片建议(writing-plans 细化)

- **切片 1**:MultiAgentEngine 骨架(implements StreamableAgentEngine,name/paradigm/priority/supports)+ isImplemented MULTI_AGENT + LEGACY_RANK 注册 + 单测。
- **切片 2**:SubTask/TaskDecomposition/WorkerResult record + renderDecomposePrompt + renderAggregatePrompt + decompose/aggregate(BeanOutputConverter)+ 单测。
- **切片 3**:runMultiAgentLoop(分解 + 并行 workers CompletableFuture + ExplicitToolExecutioner + 聚合)+ tracker 线程安全(worker 各自 + 合并)+ 假完成守护 + 流式 SSE + 单测。
- **切片 4**:finalResult + resolveFinalAnswer + trace + 全量。

---

## 风险与注意

- **并行 worker 线程安全(核心复杂点)**:`CompletableFuture` 多线程 + `ToolExecutionContextHolder`(ThreadLocal tracker)。worker 各自 tracker + 合并证据到主线程(或主线程聚合后验证)。`ModelCallExecutor` 多线程并发调用(线程安全?failover 状态?)——确认 ModelCallExecutor 是否线程安全(可能需每 worker 独立 client 或同步)。
- **BeanOutputConverter List 泛型**:`TaskDecomposition{List<SubTask>}` wrapper(参考 PlanExecuteEngine Plan)。
- **worker 数量**:maxAgents clamp [1,8](避免过多并发 LLM)。
- **tracker 合并**:AutonomousExecutionTracker 的证据(写/命令调用)需跨 worker 合并(主线程聚合所有 worker 的工具证据)。具体合并 API 看 AutonomousExecutionTracker(writing-plans 确认;可能需新增 merge 方法或读 worker tracker 状态)。
- **碰引擎核心 + 流式 SSE + 并发**:MultiAgentEngine 新引擎,implements StreamableAgentEngine + CompletableFuture 并发。每切片全量测试(带全套 env)。
- **shell 空 MYSQL_* env**:全量测试命令必须显式传全套 env。
