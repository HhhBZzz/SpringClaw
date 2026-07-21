# Agent 范式级切换 — 地基设计

**Date:** 2026-07-21
**Status:** Design approved; spec pending user review
**Parent goal:** 让 SpringClaw 成为"所有人学 agent"的参考——完整理清大模型在 agent 中的流程、所有功能可视化、主流 agent 框架范式可自由切换。

## 1. 目标

把 agent **范式**（ReAct / OPAR / Plan-Execute / Reflexion / 多智能体等思考模式）从当前隐含在 `executionMode` + 引擎内部的状态，提升为**用户可在每次请求显式选择的一等概念**，并为后续逐个增量实现新范式打下地基。

本 spec 只覆盖**地基子项目**。新范式（ReAct/Plan-Execute/Reflexion/多智能体）的实际实现、并排对比 UI 是后续增量子项目，每个独立 spec。

## 2. 现状

- `AgentEngine` 接口已是较好的引擎抽象：`name() / priority() / supports(ChatContext) / execute(ctx, fallbackResponder)`，`EngineSelector` 按 priority 选第一个 `supports()=true` 的引擎。
- 现有引擎隐含覆盖三类范式：
  - 单轮：`SimplifiedOparEngine` / `BasicStreamEngine` / `ModelLedStreamEngine`
  - OPAR：`OparLoopEngine`
  - 自主多步：`AutonomousLoopEngine`
- 范式目前由 `ChatRoutingPolicyService`（意图 + autoUpgrade + responseMode）+ `executionMode`（"opar"/"simplified"）自动路由，**用户不能直接选范式**。
- 问题：范式不是一等概念，无法"同问题跑不同范式对比"，也无法在 timeline 上按范式语义标注步骤。

## 3. 方案选择

扩展现有 `AgentEngine` 接口加 `paradigm()` 一等概念（方案 A），而非新建上层抽象（B，大改动）或仅扩展 `executionMode` 字符串（C，语义弱）。理由：`AgentEngine` 已是雏形好地基，最小改动达成"范式可选"。

## 4. 设计

### 4.1 范式枚举 `AgentParadigm`

新枚举（一等公民，用户可选）：

```
SINGLE_TURN       单轮 Function-Calling（一问一答 + 工具）
OPAR              Observe-Plan-Act-Reflect
AUTONOMOUS_LOOP   自主多步循环
REACT             Thought-Action-Observation（占位，增量实现）
PLAN_EXECUTE      先规划再执行（占位，增量实现）
REFLECTION        反思改进（占位，增量实现）
MULTI_AGENT       多智能体（占位，增量实现）
```

- 地基阶段：前 3 个**复用现有引擎显式化**；后 4 个作为**占位范式**（选择时返回"范式未实现，待增量"的明确降级，不静默走错引擎）。

### 4.2 `AgentEngine` 加范式归属

`AgentEngine` 接口加方法：

```java
AgentParadigm paradigm();
```

现有引擎映射：
- `SimplifiedOparEngine` / `BasicStreamEngine` / `ModelLedStreamEngine` → `SINGLE_TURN`
- `OparLoopEngine` → `OPAR`
- `AutonomousLoopEngine` → `AUTONOMOUS_LOOP`

### 4.3 切换机制（每次请求指定）

- `ChatRequest` 加可选字段 `paradigm`（`AgentParadigm?`，缺省 = 不指定）。
- `EngineSelector` 选择逻辑：
  - 请求带 `paradigm` 时：在 `engine.paradigm() == 请求paradigm` 的引擎里按 priority 选第一个 `supports()=true` 的；若无任何引擎匹配，返回明确的"该范式未实现/无可用引擎"错误（不回退到别的范式）。
  - 请求不带 `paradigm` 时：走现有 priority + supports 自动路由（**完全向后兼容**）。
- `ChatRoutingPolicyService`：当请求显式指定 paradigm 时，跳过意图自动路由（用户选择优先）。
- SSE/异步路径同样透传 paradigm。

### 4.4 前端范式选择器

`AgentView` 加范式 dropdown（单轮/OPAR/自主多步 + 灰显占位的 ReAct/Plan-Execute/Reflexion/多智能体并 tooltip "规划中"），发送时带 `paradigm` 字段；不选时走后端自动路由。

### 4.5 timeline 范式标注（教学核心）

- run 的 paradigm 写进 `RunState`（accept 时从请求记录）+ 每个 `RunEvent` 携带 paradigm 标签。
- 前端 timeline 按 paradigm **语义化展示阶段**：
  - SINGLE_TURN：question / tool / answer
  - OPAR：observe / plan / act / reflect
  - AUTONOMOUS_LOOP：goal / step / observation
  - （REACT 等增量范式各自语义，后续子项目定义）
- 不再只展示泛化的 trace 行，而是"这是 OPAR 的 Plan 步"。

### 4.6 对比可视化（地基：单 timeline + 范式标签）

- 地基只做：一次跑一个范式，timeline 带范式标签 + 语义化阶段。
- 要对比就手动跑两次，看两个 timeline。
- **并排自动对比**（同问题同时跑多范式并排显示）作为增量子项目，不进地基。

## 5. 不做（YAGNI，留给增量）

- ReAct / Plan-Execute / Reflexion / 多智能体的实际执行实现（地基只占位）。
- 并排对比 UI、范式性能/成本/token 对比指标。
- 框架级切换（Spring AI / LangChain4j 底层切换）——愿景第 2 阶段。
- 范式矩阵（范式 × 框架）——愿景第 3 阶段。

## 6. 向后兼容

- `ChatRequest.paradigm` 缺省时不改变任何现有路由行为。
- 现有引擎加 `paradigm()` 是接口扩展，实现类各加一行返回，不破坏构造/测试。
- `EngineSelector` 不带 paradigm 走原逻辑。

## 7. 验收

- 用户可在 API（`paradigm` 字段）和前端（选择器）显式选 SINGLE_TURN/OPAR/AUTONOMOUS_LOOP，run 按该范式执行。
- 选占位范式（REACT 等）返回明确"未实现"提示，不静默走错。
- timeline 按所选范式语义化展示阶段。
- 不带 paradigm 时，所有现有路由/引擎行为不变（现有测试全绿）。
- 现有 6 引擎各正确声明 `paradigm()`。

## 8. 后续增量子项目（每项独立 spec → plan → 实现）

1. ReAct 范式实现（Thought-Action-Observation 循环引擎）
2. Plan-Execute 范式实现
3. Reflexion 范式实现
4. 多智能体范式实现
5. 并排对比可视化 UI

每个新范式实现 `AgentEngine` + `paradigm()=XXX` + 定义自己的 timeline 阶段语义，接入地基即可，互不阻塞。

## 9. 实现切片建议（writing-plans 阶段细化）

地基可拆为小 PR：
- PR1：`AgentParadigm` 枚举 + 现有引擎 `paradigm()` 声明 + `EngineSelector` 按 paradigm 选（含占位范式错误处理）+ 单测
- PR2：`ChatRequest.paradigm` 字段 + 三模式（send/stream/async）透传 + 路由层尊重 paradigm
- PR3：`RunState` 记录 paradigm + RunEvent 携带 + timeline 语义化（前端）
- PR4：前端范式选择器
