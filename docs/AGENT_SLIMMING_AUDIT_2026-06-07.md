# SpringClaw Agent Slimming Audit - 2026-06-07

## 背景

最近两个月 SpringClaw Agent 的主要问题不是缺少功能，而是框架演进后旧逻辑没有同步退场：

- `CapabilityRegistry` 已经成为能力注册中心，但旧的 `AgentToolProviderConfig` 仍然维护一套关键词和工具映射。
- `ToolPackDescriptor` 已经让 ToolPack 自描述能力，但部分路由仍然从外层配置或手写规则推断。
- `EngineSelector` 和 `TurnPipeline` 已经建立统一生命周期，但旧的 fallback、追问消解、异常兜底仍存在多处交叉。

这会导致新增能力时看似只改注解，实际仍可能被旧 provider、旧关键词、旧异常兜底影响，形成用户看到的多轮混乱、工具参数幻觉、格式不一致和错误原因被掩盖。

## 本次已删除

第一刀删除旧的工具 provider 路由层，让工具选择只依赖 `CapabilityRegistry`：

- 删除 `AgentToolProvider`
- 删除 `DefaultAgentToolProvider`
- 删除 `AgentToolProviderConfig`
- 删除 `ToolOrchestrator` 旧构造函数中注入所有 ToolPack 和 `springclaw.skill.*trigger-keywords` 的路径
- 删除 Runtime Console 中 `List<AgentToolProvider>` 的第二套工具展示来源

保留的能力语义已经迁移到 ToolPack 自描述层，例如 workspace 搜索关键词归并到 `WorkspaceSearchToolPack` 的 `@ToolPackDescriptor`。

## 当前统一链路

工具选择现在应遵循这一条链：

```text
User Turn
  -> TurnPipeline / Routing
  -> CapabilityRegistry
  -> ToolPackDescriptor
  -> ToolOrchestrator
  -> ToolPack execution
  -> Trace / Evidence
```

Runtime Console 的 Tools 面板也从同一个 `CapabilityRegistry` 读取描述、风险级别和工具集，不再维护另一套 provider 展示模型。

## 删除原则

后续继续瘦身时必须遵守：

1. 路由语义只能沉淀到 ToolPack 描述、能力契约或 TurnPipeline 阶段中。
2. Router、Fallback、Console 不再新增业务关键词 `containsAny`。
3. 删除旧逻辑前先写或改测试，让测试证明新架构已经覆盖旧行为。
4. 保留真实韧性代码，例如缓存、数据库恢复、网络重试；删除的是重复路由、隐藏兜底、硬编码补丁。
5. 每一刀完成后运行全量后端测试，确保不是靠感觉判断。

## 后续瘦身队列

### 1. ChatRoutingPolicyService fallback 关键词

现状：`detectIntent` 仍有兜底关键词和结构化打分逻辑。

目标：保留长度、换行、代码块等结构化特征；关键词语义全部移到 `CapabilityRegistry` 或 ToolPack 描述中。

### 2. ContextualFollowUpQuestionResolver

现状：追问消解仍容易把短句、控制语句、代词绑定到上一轮工具参数。

目标：改为 TurnPipeline 内的通用 ContextualResolver，只在高置信度、同一能力族、槽位缺失明确时消解；CONTROL 和 CHAT 必须 bypass。

### 3. Trace 与 RunState 模型重复

现状：`AgentRunTraceEvent`、`AgentRun`、`AgentStep` 与新的 `RunState`、`RunTraceEvent` 存在概念重叠。

目标：统一前端展示、后端运行态和持久化边界，避免 Memory 和 Trace 混流。

### 4. 异常吞噬和伪网络错误

现状：多个 engine 和 service 仍有 broad `catch (Exception)`，可能把模型 JSON 解析、参数提取、反思失败包装成“上游连接中断”。

目标：统一抛出 `AgentExecutionException` 子类，最外层只做异常翻译，不吞真实原因。

### 5. 前端假按钮与真实后台能力绑定

现状：部分按钮、tab、状态展示仍需要确认是否完全来自真实 API。

目标：Skills、Tools、Tasks、Usage、模型选择、模式选择、Run Inspector 都必须有真实数据源、禁用态或明确的错误态，不能只是视觉占位。

## 验证记录

- `rg "AgentToolProvider|DefaultAgentToolProvider|AgentToolProviderConfig|legacyProviders|springclaw\\.skill\\..*trigger-keywords|keywordProvider" -n src/main/java src/test/java src/main/resources` 无匹配。
- `mvn -Dtest=ToolOrchestratorTest test` 通过。
- `mvn test` 通过，287 个测试零失败。

