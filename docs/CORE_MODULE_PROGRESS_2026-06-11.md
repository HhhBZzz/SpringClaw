# SpringClaw 核心最小模块进度记录

> 日期：2026-06-11
> 范围：只记录当前实现位置、成熟度、稳定优先级；不要求继续合并 engine，不删除 legacy/fallback 代码。

## 一、当前定位

SpringClaw 当前是一个基于 Spring Boot / Spring AI 的本地 Agent Runtime 雏形，已经超过普通 Chat Demo，但还没有达到完整 Agent Infrastructure。

更准确地说：项目已经具备 Runtime、Tool、Context、Trace、Workspace、Skill、Frontend Console 的局部基础设施雏形；当前最大问题不是缺功能，而是主线过多、确认边界不统一、trace 结构化不足。

当前稳定策略：

1. 不继续合并 `AgentRuntimeEngine`、`AutonomousLoopEngine`、`OparLoopEngine`。
2. 先确认默认链路、可运行功能、测试基线、权限边界。
3. 只围绕现有模块补稳定性，不新增大 Runtime / Policy / ChangeSet 框架。

## 二、当前 Git 与测试基线

当前分支：

- `codex/bootstrap-github`
- 本地领先远端 6 个 commit。

当前未提交状态：

- 已修改：`src/main/java/com/springclaw/service/agent/AgentRuntimeEngine.java`
- 已修改：`src/main/java/com/springclaw/service/chat/impl/AutonomousLoopEngine.java`
- 已修改：`src/test/java/com/springclaw/service/agent/AgentRuntimeEngineTest.java`
- 已修改：`src/test/java/com/springclaw/service/chat/impl/AutonomousExecutionTrackerTest.java`
- 未跟踪：`README_CN.md`

最近测试基线：

- `mvn test`
- 结果：`Tests run: 298, Failures: 0, Errors: 0, Skipped: 0`

注意：上面的测试通过说明当前工作区可编译、测试可过；不代表未提交的 engine 相关变更已经适合作为长期架构方向。

## 三、核心最小模块进度表

成熟度评分说明：

- 1：只有 demo 或散落代码。
- 2：有功能，但没有稳定抽象。
- 3：已有基础设施雏形，可运行但边界不完整。
- 4：抽象清晰，可维护，可审计。
- 5：接近可复用平台层。

| 核心模块 | 当前实现位置 | 当前作用 | 成熟度 | 当前进度 | 稳定建议 |
| --- | --- | --- | --- | --- | --- |
| Runtime / Engine | `ChatServiceImpl`、`EngineSelector`、`AgentRuntimeEngine`、`BasicStreamEngine`、`AutonomousLoopEngine`、`OparLoopEngine`、`SimplifiedOparEngine` | 请求进入后选择执行引擎，完成同步、流式、OPAR、自动循环等执行路径 | 3 | 65% | 停止合并 engine；先把默认链路、fallback、streamable 确认边界记录清楚 |
| Model 调用层 | `AiProviderService`、`ModelCallExecutor`、`ModelTransportGuardService`、`LlmUsageRecordServiceImpl` | provider/model 切换、模型调用、传输异常保护、用量记录 | 4 | 80% | 保持稳定；不要在本轮把模型层和 agent loop 继续揉在一起 |
| Tool 调用层 | `CapabilityRegistry`、`ToolOrchestrator`、`ToolRuntimeAspect`、`ToolPackDescriptor`、`SystemToolPack`、`WebSearchToolPack` | 工具注册、工具选择、工具包描述、AOP 审计、运行时工具调用 | 3 | 60% | 下一步只补结构化审计字段和权限校验，不扩工具数量 |
| Context 管理 | `ChatContextFactory`、`ContextAssembler`、`VectorMemoryService` | 组装短期上下文、长期记忆召回、prompt 输入 | 3 | 55% | 暂不做复杂压缩系统；先记录上下文来源、窗口大小、召回优先级 |
| Session / Task 生命周期 | `AgentSession`、`MessageEvent`、`ChatResultPersister`、`AsyncChatResultStore`、task service 包 | 保存会话、消息事件、异步结果、任务入口 | 3 | 50% | 先区分 chat session 和 long-running task；不要马上建新 Task Runtime |
| Event Stream / Trace | `SseEventBridge`、`AgentRunTraceService`、`message_event`、`agent_run` 相关表 | 前端 SSE、运行 trace、工具审计事件、运行步骤展示 | 3 | 60% | 优先补 tool invocation 的结构化 input/output/risk/status，而不是增加新事件类型 |
| Workspace 管理 | `WorkspaceReviewService`、`WorkspaceTaskService`、`LocalFilesystemService`、`WorkspaceEditToolPack` | 工作区检索、代码统计、文件候选、本地文件访问、工作区修改/命令 | 2 | 45% | 本轮先收紧边界和确认；不要引入完整 diff/rollback 框架 |
| Permission / Policy | `ToolRiskPolicyService`、`ToolPermissionServiceImpl`、`AgentActionProposalService`、`application.yml` user-deny-tools | 风险分级、工具权限、动作确认、默认 deny 配置 | 2 | 45% | P0 是确认 streamable engine 写操作是否绕过 action_required；先补最小测试 |
| Sandbox / Command Execution | `WorkspaceEditToolPack.workspaceRunCommand`、`SystemToolPack.runCommand`、`ScriptSkillExecutorService` | 执行 shell、脚本技能、工作区命令 | 2 | 35% | 当前不是成熟沙箱；默认应谨慎收紧，而不是扩大自动执行 |
| Long-term Memory | `VectorMemoryService`、Redis Vector Store 配置、memory service 包 | 会话记忆、语义召回、跨会话用户记忆控制 | 3 | 55% | 保持为记忆召回层；暂不扩成知识库 RAG 平台 |
| Logs / Observability | `AgentRunTraceService`、`LlmUsageRecordServiceImpl`、audit/service/usage 包、后台页面 | token、耗时、模型调用、运行日志、审计记录 | 3 | 55% | 先统一 requestId/runId/toolCallId 关联；成本和重试统计后置 |
| Cache 策略 | `config/cache`、Redis 配置、天气/汇率/新闻等工具缓存 | 外部数据缓存、Redis 支撑记忆和状态 | 2 | 45% | 先维持工具级缓存；暂不做全局 agent cache 策略 |
| Skill 系统 | `SkillCatalogService`、`SkillRegistryService`、`SkillRuntimeService`、skill markdown/runtime/script 包 | Skill 注册、导入、Python/builtin/prompt/script 运行 | 3 | 65% | 本轮冻结扩张；等 Runtime/Tool/Policy 稳定后再继续 marketplace/plugin 化 |
| MCP 适配 | 当前未见稳定主线模块 | 未来外部工具协议适配层 | 1 | 10% | 现在不要做；等 Tool registry/schema/audit 稳定后再接 |
| Plugin 系统 | 当前主要是 Skill/Tool 形态，未形成插件生命周期 | 未来可加载组件、版本、隔离、安装 | 1 | 15% | 现在不要做；避免项目发散 |
| Multi-agent 通信 | 当前未形成独立 agent 协作协议 | 未来 agent-to-agent、handoff、角色协作 | 1 | 10% | 现在不要做；先把单 agent 生命周期跑稳 |
| Frontend Command Center | Vue Agent Console、Admin Console、runtime console 相关页面 | 展示聊天、stream、trace、动作确认、后台运维数据 | 3 | 60% | 继续消费后端真实结构化 trace，不要先堆视觉功能 |

## 四、当前默认 Runtime 链路记录

当前默认配置下，普通聊天不是直接走最重的 OPAR loop。

真实主线大致是：

```text
ChatController
  -> ChatServiceImpl
  -> ChatContextFactory
  -> AgentDecisionService / AgentDecisionRouter
  -> EngineSelector
  -> BasicStreamEngine 或 AgentRuntimeEngine / AutonomousLoopEngine / OparLoopEngine / SimplifiedOparEngine
  -> ModelCallExecutor / ToolOrchestrator / CapabilityRegistry
  -> AgentRunTraceService / SseEventBridge / ChatResultPersister
```

当前默认更接近：

- 普通 general chat：`BasicStreamEngine`
- 非 general、非危险、无需确认：`AgentRuntimeEngine`
- OPAR 且高风险写入/副作用/危险：`AutonomousLoopEngine`
- 显式 OPAR 或自动升级场景：`OparLoopEngine`
- 兜底：`SimplifiedOparEngine`

这说明项目已经有 Runtime 主线，但主线不是单一 engine。稳定期应接受多 engine 并存，只把每条路径的触发条件、权限边界、trace 结果记录清楚。

## 五、五个关键问题的当前进度

### 1. 一次任务的生命周期是否清晰？

当前结论：部分清晰。

已经存在：

- requestId / sessionId / message event
- ChatContext 组装
- AgentDecision
- EngineSelector
- engine execute / stream
- trace / persister

还不完整：

- 没有统一表达 `Task -> Plan -> Context -> Tool -> Execute -> Observe -> Verify -> Result`
- 不同 engine 内部生命周期不一致
- chat session 与 long-running task 还没有完全分层

当前进度：55%

### 2. Agent 每一步行为是否可追踪？

当前结论：能追踪主干，但不足以完整复盘每一步。

已经存在：

- SSE trace
- `message_event`
- `agent_run` / `AgentRunTraceService`
- 工具审计文本

还不完整：

- 工具 input/output/risk/status 结构化不足
- 文件读取、文件修改、命令执行还没有统一 step schema
- 失败恢复、用户确认、重试没有形成完整 timeline

当前进度：60%

### 3. 工具调用是否结构化、可审计？

当前结论：可审计，但结构化不足。

已经存在：

- tool pack
- capability registry
- tool orchestrator
- runtime aspect
- audit service

还不完整：

- audit 主要以文本事件保存
- `tool_invocation` 里 risk/input summary 等字段未充分填充
- 工具 schema、权限、分组、调用日志还没有完全统一到一个 registry 视角

当前进度：60%

### 4. 文件和命令执行是否有边界和确认？

当前结论：有边界，但确认链路存在风险点。

已经存在：

- 工作区 root 边界
- 部分危险命令阻断
- 工具权限与 deny-list
- action proposal / confirmation

还不完整：

- streamable engine 写操作确认需要重点复核
- `WorkspaceEditToolPack` 默认权限需要更明确
- 文件修改没有 staging/diff/rollback
- shell 执行不是成熟沙箱

当前进度：40%

### 5. 上下文是否可控，而不是越拼越乱？

当前结论：比纯拼 prompt 好，但还不是成熟 Context Manager。

已经存在：

- 短期窗口
- 长期记忆召回
- 会话作用域控制
- ChatContext/AssembledContext

还不完整：

- 没有 token budget allocator
- 没有文件上下文 map
- 没有上下文优先级和污染控制
- 没有摘要压缩策略

当前进度：55%

## 六、稳定期优先级

### P0：先稳定，不继续扩张

1. 保留当前 engine 并存状态。
2. 记录每个 engine 的 supports 条件和真实调用场景。
3. 不新增 Runtime/Policy/ChangeSet 大模块。
4. 不删除 legacy/fallback engine。
5. 确认未提交改动是否进入稳定基线。

### P1：补最小安全边界

1. 验证 streamable engine 是否会绕过 `action_required`。
2. 确认 `WorkspaceEditToolPack` 是否需要进入默认 deny-list。
3. 给写文件、运行命令、脚本执行补最小回归测试。
4. 将失败场景记录到现有 trace/audit，不新建框架。

### P2：补最小可观测性

1. 给工具调用补结构化字段。
2. 明确 `requestId -> runId -> toolCallId -> message_event` 的关联。
3. 前端只消费真实 trace，不做假 timeline。
4. 先让一次任务可以复盘，再谈复杂 agent harness。

### P3：再考虑 Skill / Plugin / MCP

1. Skill 先冻结为现有 runtime 能力。
2. MCP 适配等 Tool registry/schema 稳定后再做。
3. Plugin/Marketplace 等 Runtime 主线稳定后再做。
4. Multi-agent 暂停，避免主线发散。

## 七、下一步最小进度计划

每一步都应能编译、能测试、能单独提交。

### Step 1：确认当前未提交改动

目标：

- 判断当前 4 个 Java/test 修改和 `README_CN.md` 是否要保留。

验证：

- `git diff`
- `mvn test`

建议 commit：

- `docs/status: record current runtime baseline`
- 或者先不提交代码，只提交本进度文档。

### Step 2：补一个安全回归测试，不改架构

目标：

- 覆盖“写文件/命令执行类请求必须进入确认或被拒绝”的最小场景。

涉及方向：

- `ChatServiceImpl` stream 分支
- `AutonomousLoopEngine`
- `ToolRiskPolicyService`
- `AgentActionProposalService`

验证：

- 针对 streamable engine 的 action_required 测试
- `mvn -Dtest=相关测试 test`
- `mvn test`

### Step 3：工具审计结构化补齐

目标：

- 复用现有 `ToolRuntimeAspect` 和 `AgentRunTraceService`。
- 不新增大框架，只把已有工具调用的 input summary、risk、status、duration 写实。

验证：

- 工具调用单测
- trace service 单测
- 前端 runtime console 可看到真实字段

### Step 4：Workspace 边界收紧

目标：

- 明确 workspace 写入、命令执行、脚本执行的默认策略。
- 先通过配置和测试收紧，不做 diff/rollback 大系统。

验证：

- 文件写入越界测试
- dangerous command deny 测试
- user-deny-tools 配置回归测试

### Step 5：Context 输入记录化

目标：

- 记录每次请求实际拼入了哪些上下文来源。
- 不做复杂压缩，只让上下文来源可解释。

验证：

- ContextAssembler 单测
- trace 中能看到 context source summary

## 八、现在应该继续做什么

应该继续：

1. Runtime 默认链路和 engine 触发条件文档化。
2. Tool audit 结构化。
3. Workspace/command 安全边界。
4. Trace timeline 可复盘。
5. Context 来源可解释。
6. 前端 Command Center 消费真实 trace 数据。

应该暂时停止：

1. 合并 engine。
2. 新增大 Runtime 框架。
3. 新增大 Policy 框架。
4. 新增 ChangeSet/diff/rollback 大系统。
5. Multi-agent。
6. Plugin marketplace。
7. MCP 适配。
8. 继续扩展视觉型前端功能。

现在看起来酷但会让项目变散：

1. 同时推进多 agent、插件市场、MCP、复杂 skill marketplace。
2. 继续把 engine 合并成一个更大的 super engine。
3. 在 trace 还不完整时堆更多工具。
4. 在 workspace 写入没有确认闭环前扩大自动执行。
5. 在 context 不可解释前继续增加长期记忆和知识库能力。

## 九、当前阶段定义

当前阶段不是“继续造能力”，而是“把已经出现的 runtime 雏形固定下来”。

最小成功标准：

1. 默认请求走哪条 engine 可解释。
2. 一次任务的生命周期可复盘。
3. 工具调用有结构化审计。
4. 文件/命令执行有明确边界和确认。
5. 上下文来源可解释、可限制。
6. 前端展示的 timeline 来自真实后端事件。

达到以上标准后，SpringClaw 才适合继续向 Skill、Plugin、MCP、Multi-agent 演进。
