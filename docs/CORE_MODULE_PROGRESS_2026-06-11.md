# SpringClaw 核心最小模块进度记录

> 日期：2026-06-11
> 最近更新：2026-06-16
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
- 跟踪远端 `origin/codex/bootstrap-github`。

当前未提交状态：

- 当前瞬时状态以 `git status --short` 为准；本文不再记录易过期的文件列表。
- 最近稳定基线已包含 Workspace Guard、stream action confirmation、Agent product mode metadata、timeline step schema、confirmation timeline、workspace tool action mapping、memory recall isolation、Memory Bank 文件化项目记忆、context summary 前端展示、模型用量、prompt cache、模型调用过程指标、Agent learning 最小闭环。

最近测试基线：

- `mvn test`
- 结果：`Tests run: 378, Failures: 0, Errors: 0, Skipped: 0`
- `cd frontend && npm run build`
- 结果：Vue typecheck 与 Vite build 通过
- `git diff --check`
- 结果：clean

注意：当前稳定推进集中在 Workspace Guard、确认链路、trace 元数据、timeline step schema、前端 Command Center 真实展示、Memory 召回隔离、Memory Bank 文件化项目记忆、context summary 可解释、模型用量、prompt cache、模型调用过程 Micrometer 指标化、失败 trace 经验沉淀；确认卡片和 workspace 命令/文件工具已开始进入结构化 timeline，命令预览和文件路径已进入工具输入摘要；不涉及 `AgentRuntimeEngine`、`AutonomousLoopEngine`、`OparLoopEngine` 合并或重构。

## 三、核心最小模块进度表

成熟度评分说明：

- 1：只有 demo 或散落代码。
- 2：有功能，但没有稳定抽象。
- 3：已有基础设施雏形，可运行但边界不完整。
- 4：抽象清晰，可维护，可审计。
- 5：接近可复用平台层。

| 核心模块 | 当前实现位置 | 当前作用 | 成熟度 | 当前进度 | 稳定建议 |
| --- | --- | --- | --- | --- | --- |
| Runtime / Engine | `ChatServiceImpl`、`EngineSelector`、`AgentRuntimeEngine`、`BasicStreamEngine`、`AutonomousLoopEngine`、`OparLoopEngine`、`SimplifiedOparEngine`、`AgentProductMode` | 请求进入后选择执行引擎，完成同步、流式、OPAR、自动循环等执行路径；后端已记录产品模式 `quick_answer` / `agent_analysis` / `execution_task` | 3 | 70% | 停止合并 engine；继续把内部 engine 名称收敛成用户能理解的产品模式 |
| Model 调用层 | `AiProviderService`、`ModelCallExecutor`、`ModelTransportGuardService`、`LlmUsageRecordServiceImpl`、`LlmUsageMetricsService`、`ModelCallMetricsService` | provider/model 切换、模型调用、传输异常保护、用量记录；prompt cache hit/miss、模型调用耗时、fallback、same-model retry 已能进入低基数 Micrometer 指标 | 4 | 84% | 保持稳定；后续只补失败原因聚合，不把 provider/model/source 直接放进指标标签 |
| Tool 调用层 | `CapabilityRegistry`、`ToolOrchestrator`、`ToolRuntimeAspect`、`ToolPackDescriptor`、`SystemToolPack`、`WebSearchToolPack` | 工具注册、工具选择、工具包描述、AOP 审计、运行时工具调用；Workspace Guard 拒绝原因已能进入结构化审计 JSON；workspace edit/write/command 已映射为 file/command timeline action，并记录命令预览/文件路径 input summary | 3 | 70% | 下一步继续补 output/risk/duration 的真实字段，不扩工具数量 |
| Context 管理 | `ChatContextFactory`、`ContextAssembler`、`AssembledContext`、`ConversationEventTextSupport`、`MemoryBankService`、`VectorMemoryService` | 组装短期上下文、文件化项目记忆、长期记忆召回、prompt 输入；长期记忆召回已做 session/user 防御性隔离，Memory Bank 已接入 observe prompt，meta 事件已暴露 context summary，learning active/filtered count 已进入上下文摘要 | 3 | 72% | 暂不做复杂压缩系统；下一步记录上下文来源、窗口大小、召回优先级到 trace |
| Session / Task 生命周期 | `AgentSession`、`MessageEvent`、`ChatResultPersister`、`AsyncChatResultStore`、task service 包 | 保存会话、消息事件、异步结果、任务入口 | 3 | 50% | 先区分 chat session 和 long-running task；不要马上建新 Task Runtime |
| Event Stream / Trace | `SseEventBridge`、`AgentRunTraceService`、`AgentRunTraceEvent`、`message_event`、`agent_run` 相关表 | 前端 SSE、运行 trace、工具审计事件、运行步骤展示；tool audit 可镜像到 run trace；`agent_run.product_mode` 已可持久化并回显到运行列表；trace event 已带 `springclaw.timeline-step.v1` 基础字段；用户确认 required/confirmed/cancelled 已进入 timeline；workspace 工具输入摘要已进入 trace target/input summary | 3 | 80% | 下一步把失败恢复、模型 fallback、重试等细节继续结构化，保持 MessageEvent 与结构化表的关联 |
| Workspace 管理 | `WorkspaceReviewService`、`WorkspaceTaskService`、`LocalFilesystemService`、`WorkspaceEditToolPack`、`WorkspaceGuard` | 工作区检索、代码统计、文件候选、本地文件访问、工作区修改/命令；最小路径和命令边界校验 | 3 | 55% | 继续保持最小边界模型；不要引入完整 diff/rollback 框架 |
| Permission / Policy | `ToolRiskPolicyService`、`ToolPermissionServiceImpl`、`AgentActionProposalService`、`application.yml` user-deny-tools | 风险分级、工具权限、动作确认、默认 deny 配置；动作确认生命周期已写入 run trace | 2 | 50% | 下一步继续把确认后的实际执行结果、拒绝原因和权限来源结构化 |
| Sandbox / Command Execution | `WorkspaceGuard`、`WorkspaceEditToolPack.workspaceRunCommand`、`SystemToolPack.runCommand`、`ScriptSkillExecutorService` | 执行 shell、脚本技能、工作区命令；workspace 命令已拦截危险命令和父目录路径段 | 2 | 45% | 当前仍不是成熟沙箱；先把拒绝原因、确认边界、审计记录做实 |
| Long-term Memory / Memory Bank | `VectorMemoryService`、`MemoryBankService`、`AgentLearningService`、Redis Vector Store 配置、`docs/memory-bank` | 会话记忆、语义召回、跨会话用户记忆控制、文件化项目记忆；vector store 召回结果已在服务层二次过滤，Memory Bank 已作为非 RAG 项目记忆进入上下文；失败 trace 可沉淀为可审阅 learning 条目，learning status 已可过滤 inactive 经验，并统计 active/filtered learning 数量 | 3 | 76% | 保持向量记忆为召回层，Memory Bank 作为项目长期记忆主线；下一步补召回来源、上下文预算记录和 learning 影响复盘 |
| Logs / Observability | `AgentRunTraceService`、`AgentRunTraceEvent`、`LlmUsageRecordServiceImpl`、`LlmUsageMetricsService`、`ModelCallMetricsService`、`AgentContextMetricsService`、audit/service/usage 包、后台页面 | token、耗时、模型调用、运行日志、审计记录；timeline step 已具备 category/action/target/source/riskLevel 基础字段；确认和 workspace 工具动作已可复盘，workspace 命令/文件输入摘要已可审计；上下文来源摘要、模型用量、prompt cache hit/miss、模型调用耗时/fallback/retry、learning active/filtered 数量已通过 Micrometer 记录为低基数数值指标 | 3 | 72% | 先统一 requestId/runId/toolCallId 关联；下一步补 tool duration/error reason 和 memory recall hit count |
| Cache 策略 | `config/cache`、Redis 配置、天气/汇率/新闻等工具缓存 | 外部数据缓存、Redis 支撑记忆和状态 | 2 | 45% | 先维持工具级缓存；暂不做全局 agent cache 策略 |
| Skill 系统 | `SkillCatalogService`、`SkillRegistryService`、`SkillRuntimeService`、skill markdown/runtime/script 包 | Skill 注册、导入、Python/builtin/prompt/script 运行 | 3 | 65% | 本轮冻结扩张；等 Runtime/Tool/Policy 稳定后再继续 marketplace/plugin 化 |
| MCP 适配 | 当前未见稳定主线模块 | 未来外部工具协议适配层 | 1 | 10% | 现在不要做；等 Tool registry/schema/audit 稳定后再接 |
| Knowledge Source / Wiki | `docs/memory-bank`、未来 Obsidian/Wiki.js Markdown source | 当前只支持本地 Markdown Memory Bank；Wiki.js / Obsidian 应作为项目知识源，不混入用户长期记忆 | 1 | 20% | 先稳定 Markdown 文件模型；不要现在接复杂 Wiki API 或 RAG 管道 |
| Self Evolution | `AgentLearningService`、`AgentRunTraceService`、`docs/memory-bank/agent-learnings.md` | 从失败 trace 中提炼 lesson/rule/counterexample/evidence，去重后写入可审阅 Memory Bank；新条目默认 active，Memory Bank 只加载 active/approved/旧格式条目，过滤 disabled/rejected/superseded | 2 | 40% | 下一步增加用户确认、反例分类、规则失效回滚；不要让模型自动无限写规则 |
| Plugin 系统 | 当前主要是 Skill/Tool 形态，未形成插件生命周期 | 未来可加载组件、版本、隔离、安装 | 1 | 15% | 现在不要做；避免项目发散 |
| Multi-agent 通信 | 当前未形成独立 agent 协作协议 | 未来 agent-to-agent、handoff、角色协作 | 1 | 10% | 现在不要做；先把单 agent 生命周期跑稳 |
| Frontend Command Center | Vue Agent Console、Admin Console、runtime console 相关页面、`ChatStreamMeta.contextSummary` | 展示聊天、stream、trace、动作确认、后台运维数据；Agent Console 与 Admin 表已展示 `productMode`；timeline 已消费结构化 step 字段并清理 JSON detail 展示；任务元数据面板已展示 context summary | 3 | 72% | 继续展示风险等级和真实 timeline，不要先堆视觉功能 |

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

当前结论：能追踪主干，工具拒绝类事件开始具备结构化复盘能力，但仍不足以完整复盘每一步。

已经存在：

- SSE trace
- `message_event`
- `agent_run` / `AgentRunTraceService`
- `productMode` 已能从 SSE meta 和历史运行列表回显到前端
- `AgentRunTraceEvent` 已带 `stepSchema/category/action/target/source/riskLevel`
- 工具审计文本
- Workspace Guard 拒绝原因结构化字段：`guardAction`、`guardReasonCode`、`guardMessage`、`guardResolvedPath`

还不完整：

- 普通工具 output/risk/duration 结构化不足，workspace 工具 input summary 已开始补齐
- 文件读取、文件修改、命令执行等细分来源还没有完全进入统一 step schema
- 失败恢复、用户确认、重试没有形成完整 timeline

当前进度：76%

### 3. 工具调用是否结构化、可审计？

当前结论：可审计，Workspace Guard 拒绝和 workspace 工具输入已经开始结构化；普通工具输出、耗时、风险字段仍结构化不足。

已经存在：

- tool pack
- capability registry
- tool orchestrator
- runtime aspect
- audit service
- workspace guard failure detail schema：`springclaw.workspace-guard.v1`
- run trace timeline step schema：`springclaw.timeline-step.v1`

还不完整：

- 普通工具 audit 仍主要以摘要文本保存，但 run trace 已可提取 action/target/source/riskLevel，workspace 命令/文件路径已进入 input summary
- `tool_invocation` 里 risk/output/duration 等字段未充分填充
- 工具 schema、权限、分组、调用日志还没有完全统一到一个 registry 视角

当前进度：70%

### 4. 文件和命令执行是否有边界和确认？

当前结论：工作区文件和命令边界已开始收紧，但确认链路仍存在风险点。

已经存在：

- 工作区 root 边界
- 部分危险命令阻断
- symlink 真实路径越界阻断
- 命令父目录路径段阻断
- guard 拒绝原因进入工具审计和 run trace
- 工具权限与 deny-list
- action proposal / confirmation

还不完整：

- streamable engine 写操作确认需要重点复核
- `WorkspaceEditToolPack` 默认权限需要更明确
- 文件修改没有 staging/diff/rollback
- shell 执行不是成熟沙箱

当前进度：55%

### 5. 上下文是否可控，而不是越拼越乱？

当前结论：比纯拼 prompt 好，并已开始接入可版本化的 Memory Bank，但还不是成熟 Context Manager。

已经存在：

- 短期窗口
- 长期记忆召回
- 文件化项目记忆 `docs/memory-bank`
- 会话作用域控制
- ChatContext/AssembledContext
- `VectorMemoryService` 对 vector store 结果做 session/user 防御性过滤，避免 filter 失效时串记忆
- `ConversationEventTextSupport` 已避免把 Memory Bank 内容误提取成用户问题
- `AssembledContext.sourceSummary` 和 SSE meta 已输出 Memory Bank/短期/长期上下文字符数

还不完整：

- 没有 token budget allocator
- 没有文件上下文 map
- 没有上下文优先级和污染控制
- 没有摘要压缩策略
- 召回来源、命中数、裁剪原因还没有进入 trace

当前进度：68%

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

### Step 1：提交 Workspace Guard 最小边界

目标：

- 保留当前 Workspace Guard、workspace edit 工具边界、tool audit 结构化拒绝原因。
- 不修改 engine，不新增 Runtime/Policy/ChangeSet 大模块。

验证：

- `mvn -q -Dtest=WorkspaceGuardTest,WorkspaceEditToolPackTest,ToolRuntimeAspectTest,MessageEventToolAuditServiceTest test`
- `mvn test`
- `git diff --check`

建议 commit：

- `工作区：增加边界守护并结构化拒绝原因`

### Step 2：继续补确认链路回归测试，不改架构

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

### Step 3.5：产品模式 metadata 与控制台展示已落地

当前状态：

- `AgentProductMode` 将内部路由映射成 `quick_answer`、`agent_analysis`、`execution_task`。
- `SseEventBridge.sendMeta` 已在 SSE meta 中输出 `productMode`。
- `AgentRunTraceService.recordRunMetadata` 已持久化 `agent_run.product_mode`。
- `AgentRunTraceService.recentRuns` 已在不替换 MessageEvent TRACE 主来源的前提下补充 `productMode`。
- Agent Console 与 Admin Console 已展示产品模式，不再只暴露 engine/response mode 细节。
- schema 和已有数据库迁移已补 `product_mode`。

下一步：

- Runtime Console replay 继续读取 `product_mode`，后续补完整 step schema。
- 继续用产品模式解释用户体验，不把 engine 类名作为产品概念暴露。

### Step 3.6：timeline step schema 最小版已落地

当前状态：

- `AgentRunTraceEvent` 已增加 `stepSchema`、`category`、`action`、`target`、`source`、`riskLevel`。
- `AgentRunTraceService` 能从 `springclaw.tool-audit.v1` 提取 `tool.invoke`、tool name、toolset，并推断基础风险等级。
- `SseEventBridge` 已把这些字段输出到实时 trace SSE。
- Agent Console timeline 已优先展示 `target/action/source/riskLevel`，并避免直接展示整段工具审计 JSON。

下一步：

- 把用户确认、文件读取、文件修改、命令执行、失败恢复分别映射成更明确的 action/source。
- 继续复用现有 trace 和 audit，不新增大 Runtime 或 Policy 框架。

### Step 3.7：确认和 workspace 动作已进入 timeline

当前状态：

- `AgentActionProposalService` 已把 `confirmation.required`、`confirmation.confirmed`、`confirmation.cancelled`、`confirmation.rejected` 写入 run trace。
- `AgentRunTraceService` 已能识别 `springclaw.timeline-step.v1` detail，并优先使用其中的 `category/action/target/source/riskLevel`。
- `WorkspaceEditToolPack.workspaceRunCommand`、`workspaceWriteFile`、`workspaceApplyPatch` 的工具审计已分别映射为 `command.run`、`file.write`、`file.patch`。

下一步：

- 把模型 fallback、失败恢复、重试次数继续补进 timeline。

### Step 3.8：workspace 工具 input summary 已进入 audit/trace

当前状态：

- `ToolRuntimeAspect` 已在工具 START 事件里输出 `springclaw.tool-input.v1`。
- `MessageEventToolAuditService` 已将 `action`、`target`、`inputSummary` 展开进 `springclaw.tool-audit.v1`。
- `AgentRunTraceService` 已优先使用工具审计中的 `action/target/inputSummary`。
- `WorkspaceEditToolPack.workspaceRunCommand` 可展示命令预览。
- `WorkspaceEditToolPack.workspaceWriteFile` / `workspaceApplyPatch` 可展示文件路径，不记录文件正文。

验证：

- `mvn -Dtest=ToolRuntimeAspectTest,MessageEventToolAuditServiceTest,AgentRunTraceServiceTest test`
- `mvn test`

下一步：

- 继续补工具 output summary、duration、risk level 的真实来源。
- 保持不新增大框架，不继续合并 engine。

### Step Memory-1：长期记忆召回隔离已落地

当前状态：

- `VectorMemoryService.recallBySession` 已在 vector store 返回后按 `sessionKey` 再过滤一次。
- `VectorMemoryService.recallByUser` 已在 vector store 返回后按 `userId` 再过滤一次。
- 即使底层 vector store 忽略 metadata filter，也不会直接把其他 session/user 的记忆带进上下文。
- 为了保留目标记忆，vector 查询会取 `topK * 2` 的小缓冲，再在服务层过滤并限制到原始 `topK`。

验证：

- `mvn -Dtest=VectorMemoryServiceTest test`
- `mvn test`

下一步：

- 记录每次上下文实际使用的 memory source、命中数、裁剪数量。
- 继续补上下文预算，不扩成知识库 RAG 平台。

### Step Memory-2：Memory Bank 文件化项目记忆已落地

当前状态：

- `MemoryBankService` 从 `docs/memory-bank` 读取项目级 Markdown 记忆。
- `ContextAssembler` 已把 Memory Bank 注入 observe prompt，并保留短期事件流和长期语义记忆。
- `AssembledContext.withQuestion` 在 Agent 循环改写问题时保留 Memory Bank。
- `ConversationEventTextSupport` 已避免把 Memory Bank 内容误提取为用户问题，降低上下文污染。

验证：

- `mvn -Dtest=AssembledContextTest,ConversationEventTextSupportTest,MemoryBankServiceTest,ContextAssemblerTest test`
- `mvn test`

下一步：

- 记录本次上下文实际使用了哪些 source、字符数、召回数、裁剪原因。
- 暂不把 Memory Bank 扩成知识库 RAG 或 Wiki 同步系统。

### Step 4：Context 输入记录化

目标：

- 记录每次请求实际拼入了哪些上下文来源。
- 不做复杂压缩，只让上下文来源可解释。

当前状态：

- `AssembledContext.sourceSummary` 已提供 `springclaw.context-source.v1` 摘要。
- `SseEventBridge.sendMeta` 已输出 `contextSummary`，包含 Memory Bank 是否使用、Memory Bank 字符数、短期上下文字符数、长期语义记忆字符数、observe prompt 总字符数。
- 前端 `ChatStreamMeta` 已补可选 `contextSummary` 类型，Agent Console 的任务元数据展开面板已展示该摘要。
- `AgentContextMetricsService` 已把 context summary 记录到 Micrometer：`springclaw.agent.context.chars{source=...}` 和 `springclaw.agent.context.memory_bank.used`，只记录数值和固定来源标签，不记录正文、requestId、sessionKey 或 userId。

验证：

- ContextAssembler 单测
- `mvn -Dtest=AssembledContextTest,SseEventBridgeTest,AgentContextMetricsServiceTest test`
- `mvn -Dtest=VueOnlyFrontendPolicyTest#agentConsoleShouldExposeContextSummaryMetadata test`
- SSE meta 事件中能看到 context source summary
- Actuator/Micrometer 可用于后续导出 agent context 指标，暂不引入新的观测框架。

### Step 4.1：Java Agent 技术栈最小尝试

当前结论：

- 优先复用项目已有 Spring Boot Actuator / Micrometer，而不是立即引入 LangChain4j、MCP Server 或新的 Agent 框架。
- Spring AI Tool Calling / MCP 后续有价值，但必须等 Tool registry、schema、audit、permission 边界稳定后再接。
- 本次最小尝试只把上下文来源摘要转成低基数指标，用于判断 Memory Bank、短期上下文、长期语义记忆、observe prompt 的实际规模。

当前状态：

- `AgentContextMetricsService` 记录 `springclaw.agent.context.chars{source=memory_bank|short_term|semantic_memory|observe_prompt}`。
- `AgentContextMetricsService` 记录 `springclaw.agent.context.memory_bank.used`。
- `SseEventBridge.sendMeta` 在发送 meta 时同步记录指标。
- 指标不包含高基数字段，不暴露 prompt 正文。

验证：

- `mvn -Dtest=AgentContextMetricsServiceTest,SseEventBridgeTest test`
- `mvn test`
- `git diff --check`

下一步：

- 可继续为模型调用耗时、tool duration、tool error reason、memory recall hit count 补 Micrometer 指标。
- 暂不把 MCP、LangChain4j、多 agent 框架作为当前主线。

### Step 4.2：模型用量、prompt cache 与模型调用过程指标已落地

当前状态：

- `LlmUsageRecordServiceImpl` 原本已能从 provider usage 中提取 prompt cache hit/miss，并在 runtime usage summary 中输出命中率、健康度、原因解释和建议。
- `LlmUsageMetricsService` 已把模型用量记录到 Micrometer：`springclaw.ai.usage.responses{usage=known|unknown}`、`springclaw.ai.usage.tokens{kind=prompt|prompt_cache_hit|prompt_cache_miss|completion|total}`、`springclaw.ai.prompt_cache.records{status=known|unknown}`。
- `ModelCallMetricsService` 已把模型调用过程记录到 Micrometer：`springclaw.ai.model.calls{outcome=success|failure,failover=used|none,retry=used|none}`、`springclaw.ai.model.call.duration{outcome=success|failure}`、`springclaw.ai.model.failovers`、`springclaw.ai.model.retries`。
- 指标只记录数值和固定状态/类型标签，不记录 prompt 正文，也不记录 requestId、sessionKey、userId、provider、model、source。
- 该接入点覆盖现有 `recordChatResponse` 调用路径，包括基础流式、model-led stream、ChatService 直接记录和 `ModelCallExecutor.executeChat`。
- 模型调用过程指标接入 `ModelCallExecutor`，不改变 engine 选择、fallback 次序或 retry 语义。

验证：

- `mvn -Dtest=LlmUsageMetricsServiceTest,LlmUsageRecordServiceTest test`
- `mvn -Dtest=ModelCallMetricsServiceTest,ModelCallExecutorTest test`
- `mvn test`
- `git diff --check`

下一步：

- 补工具 duration、tool error reason、memory recall hit count 的低基数指标。
- 继续分析低 prompt cache 命中率时的真实原因：稳定 prompt 前缀、减少前缀动态内容、固定 provider/model、控制上下文拼接顺序。

### Step 4.3：Agent learning 自进化最小闭环已落地

当前状态：

- `AgentLearningService` 已能把失败 `AgentRunTraceEvent` 转成 `springclaw.agent-learning.v1` Markdown 条目。
- 条目包含 `status/requestId/source/trigger/lesson/rule/counterexample/evidence/signature`，用于表达“从执行中学到的规则和反例”。
- 学习条目写入 `docs/memory-bank/agent-learnings.md`，按 signature 去重，字段短文本截断，不保存完整 prompt。
- `AgentRunTraceService` 在记录失败 trace 后 best-effort 调用学习服务，不影响 trace 主链路。
- `MemoryBankService` 已优先读取 `agent-learnings.md`，并只让 active/approved/旧格式经验进入下一轮 Context；`disabled/rejected/superseded` 会被过滤。
- `MemoryBankService.renderSnapshot`、`AssembledContext.sourceSummary`、SSE meta 和 `AgentContextMetricsService` 已暴露 active/filtered learning count，用于判断 Memory Bank 中按状态可进入上下文的学习规则数量，以及被过滤的失效规则数量。

验证：

- `mvn -Dtest=AgentLearningServiceTest,AgentRunTraceServiceTest,MemoryBankServiceTest test`
- `mvn test`
- `git diff --check`

下一步：

- 在现有 status 过滤基础上增加用户确认/撤销入口，避免错误规则污染上下文。
- 把 Obsidian / Wiki.js 作为 Markdown Knowledge Source 接入，不把它们直接混进用户长期记忆或向量 RAG。
- 继续补 learning 影响复盘：把 active learning count 和后续任务成功/失败 trace 关联起来。

### Step 5：再评估 Workspace diff/rollback，不急着实现

目标：

- 只有当写入确认、审计、trace 都稳定后，再设计最小 diff/rollback。
- 当前阶段只记录缺口，不引入大系统。

验证：

- 设计文档和最小测试计划先行。

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
