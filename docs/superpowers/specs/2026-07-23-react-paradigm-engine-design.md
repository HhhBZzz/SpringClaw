# ReAct 范式引擎 — 设计

**Date:** 2026-07-23
**Status:** Design(brainstorming 产出,待 writing-plans)
**Parent:** 范式级切换地基(spec `2026-07-21-agent-paradigm-switching-foundation-design.md`,PR1-3 已在 main)

## 1. 目标

实现 **ReAct(Reasoning + Acting)范式引擎**——Thought-Action-Observation 循环:LLM 每步自主推理(Thought)+ 选工具执行(Action)+ 观察结果(Observation),循环至产出最终答案。接入范式地基,使用户可选 `REACT` 范式。

这是范式可切换产品愿景的**首个增量新范式实现**(地基 spec §8.1)。地基阶段 REACT 是占位(`isImplemented()` false,选时抛"未实现"),本 spec 把它变成真正可执行的引擎。

## 2. 现状

**范式地基 PR1-3(main)**:
- `AgentParadigm` 枚举(`runtime.contract`),`REACT("Thought-Action-Observation")` 占位,`isImplemented()` 目前 `SINGLE_TURN/OPAR/AUTONOMOUS_LOOP` 才 true。
- `AgentEngine.paradigm()`、`EngineSelector.select(ctx, paradigm)`(按范式过滤选引擎,占位范式抛"未实现")。
- `ChatRequest.paradigm` / `ChatContext.paradigm`(请求级透传)、`decide` paradigm 短路(`paradigmToExecutionMode`:SINGLE_TURN→simplified,其余→opar)。
- `RunState`/`RunEvent` 记 paradigm、canonical 校验、timeline 透出(`AgentRunTraceEvent.paradigm`)。

**现有引擎(参考)**:
- `AgentRuntimeEngine`(OPAR):PLAN 预选 capability(`CapabilityExecutorRegistry.plan`,确定性,无 LLM)→ EXECUTE(后端)→ REFLECT(LLM)。**非每步 LLM 选工具**。
- `OparLoopEngine`(OPAR):LLM-Plan(结构化 `PlanResult`)→ LLM-Act(原生工具调用)→ 循环。**两次 LLM 调用/步**。
- `AutonomousLoopEngine`(AUTONOMOUS_LOOP):单次 LLM 调用 + `.tools()` + 标记完成(`TASK_COMPLETE`)+ **假完成守护**。**结构上最接近 ReAct**。

**基础设施(复用)**:
- `ModelCallExecutor.executeChat`(LLM 调用 + provider failover + emit `MODEL_CALLED` + 使用记录)。
- `ToolOrchestrator.selectAutonomousTools(channel, userId, decision)`(按 intent/riskLevel 范围选 `@Tool` bean)+ Spring AI `.tools()`(原生工具调用,LLM 自主触发 + 自动 `TOOL_*` 事件 + `ToolRuntimeAspect` 审计)。
- `DeepSeekChatCompatibility.supportsNativeToolCalling(client)`(DeepSeek V4 返回 false,与 Spring AI 工具调用往返不兼容)。
- `ChatExecutionResult(observe, plan, action, reflect, modelEnabled)`(所有引擎统一输出)、`AgentStep(stepName, type, status, detail, durationMs)`(trace 步)。

## 3. 方案

新建 `ReActEngine implements StreamableAgentEngine`,`paradigm()=REACT`。复用 `ModelCallExecutor` + `ToolOrchestrator` + `AutonomousLoopEngine` 的安全模式(工具范围 + 假完成守护)。不新建上层抽象,直接接入现有 `EngineSelector` / `RunState` / `RunEvent` / timeline。

ReAct 与现有引擎的本质区别:**LLM 在循环每一步自主选择并触发工具**(AgentRuntime 是 PLAN 预选 capability;OparLoop 拆 plan/act 两次 LLM 调用),且 **Thought 作为显式推理可见**(教学核心)。

## 4. 设计

### 4.1 `ReActEngine` 类

- `implements StreamableAgentEngine`(SSE 流式,实时展示 Thought/Action/Observation,教学透明度最高)。
- `name()="react-loop"`、`paradigm()=REACT`、`priority()=4`(opar-loop=3 之后,simplified=10 之前;具体值 writing-plans 定,确保 REACT 请求时被 `select(ctx, REACT)` 唯一选中)。
- `supports(ChatContext ctx)`:返回 `ctx.paradigm() == AgentParadigm.REACT`。`EngineSelector.select(ctx, REACT)` 已按 `paradigm()` 过滤,`supports` 只需确认范式匹配,不依赖 `executionMode`/`responseMode`(`paradigmToExecutionMode` 已把 REACT 映射 opar,但 select 按 paradigm,supports 不看 executionMode)。

### 4.2 ReAct 循环(`stream` / `execute`)

每步(上限 `springclaw.chat.max-react-steps`,默认 6,与 `max-steps` 独立的配置键):

1. **Thought + Action**(单次 `ModelCallExecutor.executeChat`,emit `MODEL_CALLED`):
   - 系统 prompt 要求 ReAct 推理(先 Thought 推理,再决定 Action)。
   - 工具 = `ToolOrchestrator.selectAutonomousTools(channel, userId, decision)`(按 `AgentDecision` 的 intent/riskLevel 范围限定 LLM 可选工具)。
   - **主路径**(工具调用模型):`.tools(tools)` 附加到请求——LLM 输出 Thought(文本推理,工具调用前的输出,流式可见)+ Action(原生工具调用,Spring AI 处理往返 + 自动 `TOOL_REQUESTED/STARTED/SUCCEEDED/FAILED`)。
   - **回退路径**(`!DeepSeekChatCompatibility.supportsNativeToolCalling(client)`,如 DeepSeek V4):显式 prompt——LLM 输出结构化(Thought + Action 名 + 参数),代码解析 + 手动执行工具 + 手动 emit `TOOL_*`。
2. **Observation**:工具结果(主路径 Spring AI 自动返回;回退路径手动执行)。追加进历史(下一步 LLM 调用的 prompt 含全部历史 Thought/Action/Observation)。
3. **终止**:
   - LLM 这轮**不调工具**(输出最终答案文本)= 完成。
   - `max-react-steps` 兜底(达上限降级返回当前最佳答案)。
   - **假完成守护**(写/副作用任务,见 4.4)。
4. 产出 `AgentStep(THOUGHT/ACTION/OBSERVATION, ...)` 作为引擎内部 trace + 最终 `ChatExecutionResult`(`plan`=Thought 轨迹,`action`=Action 轨迹,`reflect`=最终答案)。

### 4.3 Action 选择机制

| | 主路径:原生工具调用 | 回退:显式 prompt |
|---|---|---|
| 适用 | `supportsNativeToolCalling`=true(GPT/Claude/Qwen 等) | DeepSeek V4 等 |
| Thought | LLM 输出文本(工具调用前,流式可见) | LLM 输出结构化 Thought 字段 |
| Action | Spring AI `.tools()`,LLM 自主触发 | LLM 输出 Action 名+参数,代码解析执行 |
| 工具事件 | 自动 `TOOL_*` | 手动 emit `TOOL_*` |
| 判定 | `DeepSeekChatCompatibility.supportsNativeToolCalling(client)` | 同左取反 |

回退路径复用 `AgentRuntimeEngine` 的显式 prompt + JSON 解析模式(`renderReflectionPrompt` + `parseReflectionResult`)的成熟做法。

### 4.4 安全(AutonomousLoop 模式)

- **工具范围限定**:`selectAutonomousTools` 按 `AgentDecision` 的 intent/riskLevel 决定 LLM 可选工具集(`ToolOrchestrator.resolveScopeToolsets`)——LLM 不能选范围外工具。
- **假完成守护**:写/副作用任务(`riskLevel` = write/side_effect/dangerous),LLM 声明完成时验证有对应的工具执行证据(`TOOL_SUCCEEDED`),否则不标记完成(降级或继续)。复用 `AutonomousLoopEngine` 的守护机制。

### 4.5 timeline

- **流式 emit**:每步 `MODEL_CALLED`(Thought 的 LLM 调用,经 `ModelCallExecutor.emitModelCalled` 自动)+ `TOOL_*`(Action/Observation,原生工具调用自动 / 回退手动)。`RunEvent` 带 `paradigm=REACT`(PR3 event() 焊接)。
- **前端**(codex / PR4 范式选择器配套):按 `paradigm=REACT` 语义化渲染——LLM 流式输出→Thought 阶段,工具调用→Action 阶段,工具结果→Observation 阶段。
- **不新增 `RunEventType` wire name**(复用现有 `MODEL_CALLED`/`TOOL_*`,前端按 paradigm 标签语义化,避免扩 contract + 持久化)。`AgentStep(THOUGHT/ACTION/OBSERVATION)` 作为引擎内部 trace 结构。

### 4.6 接入地基

1. `AgentParadigm.isImplemented()`(`AgentParadigm.java`):加 `REACT` 到白名单(否则 `select(ctx, REACT)` 抛"范式未实现")。
2. `EngineSelector.LEGACY_RANK`(`EngineSelector.java`):注册 `"react-loop"`(否则 `EngineSelector` 初始化失败——未登记 name)。
3. `ChatServiceImpl` L252-260:ReAct 走 `StreamableAgentEngine.stream()` 分发路径(已有,无需 `instanceof ReActEngine` 特殊分支)。
4. `ChatRoutingPolicyService.paradigmToExecutionMode`:REACT→`"opar"`(已由"其余→opar"覆盖,无需改;supports 不依赖 executionMode)。

## 5. 不做(YAGNI)

- **并排对比 UI**(同问题同时跑多范式并排显示)——后续独立子项目(地基 spec §4.6)。
- **plan/execute 拆分**(OparLoop 风格两次 LLM 调用)——ReAct 用经典单次 LLM 调用(Thought+Action 折叠)。
- **新 `RunEventType` wire name**(THOUGHT/OBSERVATION)——复用现有 `MODEL_CALLED`/`TOOL_*`,前端按 paradigm 语义化。
- **框架级切换**(Spring AI / LangChain4j 底层)——愿景第 2 阶段。
- **Plan-Execute / Reflexion / 多智能体**——各自独立 spec(地基 spec §8.2-8.4)。

## 6. 向后兼容

- REACT 之前是占位(`isImplemented()` false,选时 `select` 抛"未实现")。加 `isImplemented` 后,用户显式选 REACT 才触发 ReActEngine;**不选 REACT 时现有引擎/路由零变更**(null paradigm 走原逻辑,其他范式走各自引擎)。
- ReActEngine 是新类,不影响现有 6 引擎的 `supports()`/`priority()`/路由。

## 7. 验收

- 用户可选 REACT(API `paradigm=REACT` / 前端选择器),run 由 ReActEngine 执行(Thought-Action-Observation 循环)。
- LLM 每步自主选工具(范围内),工具结果反馈进历史,循环至 LLM 输出最终答案或 `max-react-steps`。
- 写/副作用任务有假完成守护(无工具证据不标完成)。
- timeline 流式 emit `MODEL_CALLED`/`TOOL_*`,带 `paradigm=REACT`;前端可按 REACT 语义化(后续 codex)。
- 不选 REACT 时,所有现有路由/引擎行为不变(全量测试绿)。
- DeepSeek V4(`supportsNativeToolCalling`=false)回退显式 prompt 可用。

## 8. 实现切片建议(writing-plans 阶段细化)

可拆为小 PR(或合并,writing-plans 定):
- **切片 1**(地基接入):`ReActEngine` 骨架(implements `StreamableAgentEngine`,`paradigm/name/priority/supports`)+ `isImplemented` 加 REACT + `LEGACY_RANK` 注册 `react-loop` + 单测(选 REACT 能选中 ReActEngine,空 stream/execute)。最小可见:REACT 不再报"未实现",选中引擎。
- **切片 2**(循环):ReAct 循环(Thought+Action 主路径原生工具调用 + Observation + 终止 + `max-react-steps`)+ DeepSeek 回退(显式 prompt)+ 流式 SSE。
- **切片 3**(安全 + trace):假完成守护(写/副作用验证)+ `AgentStep(THOUGHT/ACTION/OBSERVATION)` trace + `ChatExecutionResult` 映射 + 全量测试。

---

## 风险与注意

- **碰引擎核心 + 流式 SSE**:ReActEngine 是新引擎(不改正文现有引擎),但 implements StreamableAgentEngine 需正确管理 SSE 生命周期/锁(参考 AutonomousLoopEngine)。每切片全量测试(带全套 env)。
- **DeepSeek V4 回退**:显式 prompt 解析脆弱(参考 AgentRuntimeEngine 的 JSON 解析容错)。回退路径需手动 emit TOOL_* + 审计。
- **假完成守护复用**:AutonomousLoopEngine 的守护逻辑若不易复用(私有),可能需提取共享或重写——writing-plans 确认。
- **max-react-steps**:新配置键(独立于 opar 的 max-steps),默认 6(与 opar 上限一致)。
