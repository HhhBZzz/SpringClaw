# SpringClaw Agent 架构审视报告

> 2026-06-09 | 架构审视视角：Agent Runtime Architect
> 基于 Phase 1-5 优化完成后的代码现状，从 5 个维度审视架构合理性

---

## 一、总体评估

Phase 1-5 优化后，ChatServiceImpl 从 1307 行降至 621 行（-52%），消除了 Pipeline 层死文件、异常类膨胀、重复工具方法等结构性债务。引擎体系已建立 EngineSelector + AgentEngine 接口的策略模式骨架。但审视发现，**路由层面存在双轨并行问题**，这是当前架构最严重的结构性缺陷。

**核心矛盾**：非流式路径（chat / executeTaskMessage）通过 EngineSelector 统一选择引擎，而流式路径（stream）仍使用 ChatServiceImpl 中的 `shouldUse*()` 硬编码分支。两者对同一 ChatContext 可能选出不同引擎。

---

## 二、分维度审视结果

### 维度 1：引擎体系与路由机制

#### CRITICAL-1：流式与非流式路由双轨并行

**现状**：
- `runAgentExecution()`（非流式）→ `engineSelector.select(context)` → 按 priority 遍历 AgentEngine 列表
- `executeStream()`（流式）→ `shouldUseBasicModelStreaming()` / `shouldUseModelLedStreaming()` / `shouldUseAgentRuntime()` 硬编码 if-else 阶梯

**风险**：
同一个 ChatContext，非流式路径可能选中 SimplifiedOparEngine（priority=10），而流式路径因为 `shouldUseBasicModelStreaming()` 条件不同而走 BasicStreamEngine。两者的 `supports()` 语义与 `shouldUse*()` 语义不一致。

```
BasicStreamEngine.supports():  decision.isGeneral() && modelCallEnabled
shouldUseBasicModelStreaming(): basicStreamingEnabled && useSimplifiedMode() && responseMode=agent|fast && intent=general && modelCallEnabled
```

两套条件有交叉但不等价。`supports()` 不检查 `responseMode`、`executionMode`、`basicStreamingEnabled`，而 `shouldUse*()` 检查。这意味着 EngineSelector 的 `supports()` 无法替代 `shouldUse*()` 的完整判断逻辑。

**建议 P0**：让每个引擎的 `supports()` 方法包含完整的路由条件（包括 responseMode、executionMode、feature flag），使 EngineSelector 成为唯一的路由决策点。流式路径也通过 EngineSelector 选引擎，再由引擎自行决定走流式还是非流式。

#### CRITICAL-2：EngineSelector 无兜底引擎保证

**现状**：SimplifiedOparEngine.supports() 返回 `true`，作为兜底引擎。但 EngineSelector.select() 在没有任何引擎 supports 时抛 IllegalStateException。

**风险**：如果 SimplifiedOparEngine 被误删或 supports() 条件变更，请求会直接崩溃而非降级。

**建议 P1**：给 EngineSelector 添加默认兜底逻辑（至少一个引擎必须 supports=true），或在 select() 无匹配时返回一个预定义的 FallbackEngine 而非抛异常。

#### WARNING-1：FallbackResponder 泄露具体引擎到接口层

**现状**：AgentEngine.execute() 的第二个参数是 `OparLoopEngine.FallbackResponder`。这意味着所有 AgentEngine 实现都要依赖 OparLoopEngine 这个具体类。

**影响**：BasicStreamEngine.execute()、SimplifiedOparEngine.execute()、AgentRuntimeEngine.execute() 的签名都引用了 OparLoopEngine，形成不必要的耦合。

**建议 P0**：将 FallbackResponder 提取为 AgentEngine 接口内的独立类型（如 `AgentEngine.FallbackResponder`），或作为独立接口放到 service.agent 包下，消除对 OparLoopEngine 的依赖。

#### WARNING-2：ModelLedStreamEngine 不实现 AgentEngine

**现状**：ModelLedStreamEngine 有独立的 `stream()` 方法，不走 AgentEngine.execute() 接口。这是 Phase 5 的有意决策——因为 ToolContext 生命周期管理不适合 execute() 的同步返回模型。

**影响**：流式引擎和非流式引擎无法统一调度。EngineSelector 无法选中 ModelLedStreamEngine，必须由 ChatServiceImpl 硬编码分流。

**建议 P1**：扩展 AgentEngine 接口，增加 `stream()` 方法（可选实现），或定义 `StreamableAgentEngine extends AgentEngine` 子接口。让 EngineSelector 在流式场景也能统一路由。

---

### 维度 2：决策路由与意图识别

#### CRITICAL-3：AgentDecision.intent 与 ChatContext.intent 可能冲突

**现状**：ChatContext 是 record，同时持有 `intent` 和 `decision` 字段。intent 在 ChatContextFactory 组装时设置（基于规则匹配），decision 由 AgentDecisionService 生成（基于模型推理）。两者来源不同，语义不同。

**风险**：路由逻辑有时用 `context.intent()`（如 `shouldUseModelLedStreaming` 排除 "control-plane" intent），有时用 `context.decision()`（如 EngineSelector 的 supports 方法）。如果 intent=general 但 decision.isGeneral()=false，路由结果取决于用了哪个字段。

**建议 P0**：统一路由信号源。要么让 ChatContext.intent 完全由 AgentDecision.intent 决定（在 ChatContextFactory.build 中合并），要么在路由判断中始终使用 decision 而非 intent，让 intent 只作为辅助展示字段。

#### WARNING-3：ChatContext 携带冗余路由字段

**现状**：ChatContext record 包含 `executionMode`、`routingReason`、`responseMode`、`intent` 四个路由相关字段，加上 `decision` 共五个。这些字段有重叠语义——executionMode 和 decision.executionPath() 是同义，intent 和 decision.intent() 是同义。

**建议 P1**：考虑将路由字段合并到 decision 对象中，ChatContext 只保留 decision + responseMode（前端传入、不改），其余从 decision 派生。

#### WARNING-4：normalize() 替换连字符不一致

**现状**：shouldUseModelLedStreaming 排除 intent 时同时检查 "control-plane" 和 "control_plane"（两种写法），说明 normalize 逻辑不统一。TextUtils.normalizeWS() 只处理空白字符，不统一连字符。

**建议 P2**：在 ChatContextFactory.build 中统一 normalize intent 和 executionPath（全转小写 + 连字符统一），路由判断只需比较一个标准化值。

---

### 维度 3：工具编排与能力执行

#### CRITICAL-4：双工具执行范式

**现状**：项目同时存在两种工具执行方式：

1. **Spring AI 原生 tool calling**：通过 `.tools(toolArray)` 传给 ChatClient，让模型自行决定何时调用哪个工具。OparLoopEngine.runAction()、SimplifiedOparEngine.run()、ModelLedStreamEngine.stream() 都使用此方式。

2. **后端预执行 via CapabilityExecutorRegistry**：在模型调用前，由 CapabilityExecutorRegistry.execute() 先执行匹配的能力，将结果拼入 prompt 供模型参考。SimplifiedOparEngine.run() 调用 `capabilityExecutionService.execute(decision, assembled, requestId)`。

**风险**：同一个请求可能同时走两条路——后端先预执行了能力（capabilityResults 拼入 prompt），模型再通过原生 tool calling 调用同一个工具。结果：工具被执行两次，且两份结果可能不一致。

**建议 P0**：每个引擎只选一种范式。如果走原生 tool calling，就不预执行 capability；如果走预执行，就不挂 native tools。SimplifiedOparEngine 是当前唯一同时走两条路的引擎，需要决策取舍。

推荐方案：保留原生 tool calling（更符合主流 Agent 标准），取消后端预执行。将预执行逻辑作为引擎内部优化（仅在特定条件下启用），而非所有请求都执行。

#### WARNING-5：ToolOrchestrator 与 CapabilityExecutorRegistry 职责重叠

**现状**：ToolOrchestrator.selectTools()/selectAgentTools() 负责选择可挂载的工具对象数组。CapabilityExecutorRegistry.execute() 负责基于 AgentDecision 预执行能力。两者都在"决定哪些工具应该执行"层面操作。

**建议 P1**：合并为一个统一的 ToolSelectionService，根据引擎类型和 decision 返回统一的工具执行计划。消除"选择工具"和"执行能力"两个入口并存的问题。

#### WARNING-6：AgentCapabilityExecutionService noop 语义

**现状**：SimplifiedOparEngine 的无 @Autowired 构造函数用 `AgentCapabilityExecutionService.noop()` 替代。noop() 返回空结果列表。这意味着在特定部署配置下，后端预执行被静默跳过而非显式禁用。

**建议 P2**：用 feature flag 控制（如 `springclaw.capability.pre-execution-enabled=false`），而非 noop 模式。让行为可观测、可调试。

---

### 维度 4：本地兜底与记忆系统

#### WARNING-7：本地兜底绕过引擎体系

**现状**：OparLoopEngine 和 SimplifiedOparEngine 在进入模型调用前，先尝试 3-4 层本地兜底（controlPlane → priorityStructured → contextAware → tryFallback）。如果命中任何一层，直接返回结果，不走后续引擎逻辑。

**影响**：本地兜底是"引擎内部的影子路径"。即使 EngineSelector 选中了 OparLoopEngine，引擎可能不走 OPAR 循环而走本地快捷路径。这对调试和可观测性不利——trace 显示 "opar-loop" 但实际没走 OPAR。

**建议 P1**：将本地兜底提取为独立引擎 LocalFallbackEngine（priority=0，在 BasicStreamEngine 之前），让 EngineSelector 显式选择它。这样 trace 和路由日志会准确反映实际路径。

#### WARNING-8：observePrompt 冗余计算

**现状**：ChatExecutionResult.record 中的 observePrompt 字段在每个引擎中都是 `assembled.observePrompt()` 的副本。这个值在 ChatContext 组装时已计算，引擎又重复存入结果。

**建议 P2**：ChatExecutionResult 不存 observePrompt，从 context.assembled() 直接取。减少 record 字段冗余。

---

### 维度 5：SSE 契约与前端兼容性

#### WARNING-9：手动 JSON 序列化

**现状**：SseEventBridge 所有 sendXxx() 方法都通过手动拼接 JSON 字符串构建 SSE 事件数据。例如：

```java
String json = "{\"intent\":\"" + safe(decision.intent()) + "\",\"executionPath\":\"" + ...
```

**风险**：如果字段值包含双引号、反斜杠或换行，手动拼接会产出非法 JSON。前端 JSON.parse() 会崩溃。

**建议 P1**：改用 ObjectMapper.writeValueAsString() 或 Jackson 的 JsonGenerator 构建事件数据。SseEventBridge 已注入 ObjectMapper（在 sendMeta 等方法中部分使用），但大部分方法仍手动拼接。

#### WARNING-10：trace 事件过度使用

**现状**：一次流式请求可能产生 6-8 条 trace 事件（接收请求→判断意图→选择能力→调用模型→模型整理→降级处理→完成）。每个引擎的 stream() 方法也独立发送 3-5 条 trace。

**影响**：前端 SSE 连接在高频 trace 下可能缓冲区溢出。对用户可见的只有 token/status/error/done，trace 是开发调试用途。

**建议 P2**：将 trace 事件改为可选（开发环境启用，生产环境默认关闭或降级为异步日志）。前端只接收 5 种核心事件（token, status, meta, error, done），trace 仅在 /api/runtime-console 等管理端点可见。

---

## 三、优先级排序与推荐路线

| 优先级 | 问题 | 建议动作 | 预估工作量 |
|--------|------|----------|-----------|
| **P0** | CRITICAL-1 双轨路由 | 各引擎 supports() 包含完整条件，流式路径也走 EngineSelector | 2-3 天 |
| **P0** | CRITICAL-3 intent/decision 冲突 | 合并路由信号源，ChatContext.intent 由 decision 派生 | 1 天 |
| **P0** | WARNING-1 FallbackResponder 耦合 | 提取为 AgentEngine.FallbackResponder | 0.5 天 |
| **P0** | CRITICAL-4 双工具范式 | SimplifiedOparEngine 取舍，推荐保留 native tool calling | 1 天 |
| **P1** | WARNING-2 ModelLedStreamEngine 不走 AgentEngine | 扩展接口或定义 StreamableAgentEngine 子接口 | 1-2 天 |
| **P1** | WARNING-7 本地兜底绕过引擎 | 提取为 LocalFallbackEngine | 1 天 |
| **P1** | WARNING-9 手动 JSON 序列化 | SseEventBridge 改用 ObjectMapper | 0.5 天 |
| **P1** | CRITICAL-2 无兜底保证 | EngineSelector 添加 fallback 逻辑 | 0.5 天 |
| **P2** | WARNING-3 冗余路由字段 | ChatContext 精简，路由字段合并到 decision | 1 天 |
| **P2** | WARNING-4 normalize 不一致 | ChatContextFactory 统一 normalize | 0.5 天 |
| **P2** | WARNING-5 职责重叠 | 合并为 ToolSelectionService | 1 天 |
| **P2** | WARNING-6 noop 语义 | 改用 feature flag | 0.5 天 |
| **P2** | WARNING-8 observePrompt 冗余 | ChatExecutionResult 精简 | 0.5 天 |
| **P2** | WARNING-10 trace 过度 | 生产环境默认关闭 trace SSE | 0.5 天 |

---

## 四、架构演进方向建议

当前引擎体系的根本问题是"策略模式骨架已建好，但流式路径还没接入"。P0 修复完成后，架构将呈现：

```
ChatServiceImpl
  ├── chat() / executeTaskMessage()
  │     └── EngineSelector.select(context) → AgentEngine.execute()
  │
  └── stream()
        └── EngineSelector.select(context) →
              ├── if engine instanceof StreamableAgentEngine → engine.stream(emitter, ...)
              ├── else → engine.execute() → resolveFinalAnswer → streamReflectAnswer
```

引擎优先级梯队（P0 修复后）：

```
p=0  LocalFallbackEngine（新增，命中本地技能时最短路径）
p=1  BasicStreamEngine（general intent，不挂工具）
p=2  AgentRuntimeEngine（非 general intent，单步执行 + 验证）
p=3  OparLoopEngine（opar 模式，多步规划循环）
p=10 SimplifiedOparEngine（兜底，总是可用）
```

ModelLedStreamEngine 作为 StreamableAgentEngine（p=5 或 p=8），在 EngineSelector 选中后走 stream() 分支而非 execute()。

---

## 五、Phase 5 优化效果确认

Phase 5 的核心目标是"提取引擎独立 @Service，ChatServiceImpl 从编排+执行混合体变为纯编排层"。从结果看：

- ChatServiceImpl 1307→621 行（-52%），职责从"执行+编排"变为"纯编排+降级兜底"
- BasicStreamEngine、ModelLedStreamEngine 已独立管理流式生命周期
- LocalExecutionSupport 消除了引擎间的本地兜底逻辑重复
- EngineSelector 策略模式骨架已建立

但 Phase 5 只完成了"引擎独立化"，还没完成"路由统一化"。流式路径的 `shouldUse*()` 阶梯仍是硬编码分流，这是 Phase 6 需要解决的 P0 问题。

---

*报告完毕。4 个 CRITICAL、6 个 WARNING，0 个阻断级问题。架构概念合理，需要中等规模重构统一路由和工具范式。*