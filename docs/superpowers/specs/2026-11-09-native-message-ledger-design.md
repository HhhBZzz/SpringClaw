# 原生消息账本(native message ledger)设计

- 日期: 2026-11-09
- 状态: approved
- 来源: 上下文管理对照 2026 主流 harness 方案(Claude Code / Codex CLI / dsh 已收敛的原生
  messages + 只追加 + 自动压缩 + 前缀缓存)的差距分析
- 前置: canonical 对话历史统一(spec 2026-08-17/2026-08-18,PR #62)已合入 main

## 1. 问题

模型可见上下文由「渲染文本」构成,结构在三个层面被降级:

1. **单串文本 prompt**: 全部 10 个引擎的模型调用都是
   `.system(soil).user(renderedText)`,全库无一处 `.messages(...)`。历史以
   `- USER: xxx` 文本行嵌入模板(AssembledContext.observePrompt /
   TypedContextPromptRenderer.promptPrefix),单行硬截 220 字符
   (ConversationTurn.canonical)。模型看到的"上一轮"是被截断的复述,不是自己的原文。
   `ContextAssembler.extractFileNamesFromListing()` 用正则从渲染 markdown 里抢救文件名,
   是结构降级的标本症状。

2. **工具结果跨步丢失**: AutonomousLoop 每步全新 prompt,历史段只回放模型自己的
   文本输出(buildStepHistory),上一步的工具调用与结果对下一步完全不可见——
   "观察工具输出后决定下一步"的执行规则失去了事实基础。

3. **前缀每轮重写**: 查询相关的语义召回/记忆块拼在 prompt 里,每轮重新渲染,
   前缀必变,DeepSeek 前缀缓存按设计必 miss(usage 侧已测量 cache hit/miss,
   请求构造侧从未经营缓存)。

已有但未接线的资产: MessageEventChatMemory 已能从 canonical 日志派生原生
UserMessage/AssistantMessage(deriveFull 不截断),但随
`spring-ai-chat-memory-enabled=false`(默认)整体旁路;TokenMeter(CJK 感知估算)
零消费方;ToolResultPruner 已在 AOP 层生效(免模型剪枝,唯一已对齐主流的件)。

## 2. 目标

**模型上下文 = 原生消息账本**: messages 数组逐字保留(用户消息原样/助手输出原样/
工具调用与结果为原生 tool_use+tool_result 消息对),只追加不重写,超预算时压缩,
账本种子从 canonical 事件日志派生("Model-visible ⟺ logged" 的投影层兑现)。

### 设计决策

| 决策点 | 选择 |
|---|---|
| 账本载体 | Spring AI `Message` 列表,run 内存态;持久化仍走 canonical 事件日志(账本是派生视图,不是第二事实源) |
| 账本归属 | `RunMessageLedger` 由 ChatContextFactory 在 build 时装配一次(与 ContextSnapshot 同时机),挂 ChatContext |
| 跨 run 种子 | `ConversationHistoryDeriver.deriveFull`(不截断)→ UserMessage/AssistantMessage;工具轮跨 run 暂不重建(v2 候选) |
| run 内工具轮 | AOP 捕获(ToolRuntimeAspect 唯一执行点)→ 步末以协议合法的 AssistantMessage(toolCalls)+ToolResponseMessage 对入账 |
| 记忆注入位 | 查询相关记忆(语义召回/MemoryBank/程序化规则)作为独立 user 消息插在「种子历史之后、当前问题之前」(缓存前缀 = system+历史,保持稳定) |
| 范式指令 | 各引擎指令模板渲染为首条 briefing user 消息(与当前问题合并),不再嵌进 system |
| 切换策略 | 开关 `springclaw.runtime.native-context-enabled`(默认 true);false 或装配异常 → 回退 legacy 文本 prompt(WARN+计数,不静默) |
| 压缩 | run 内 pre-step 触发: 压力比 > 阈值 → 模型摘要替换中段;摘要失败 → 硬截断兜底 |
| 展示层 | AssembledContext/observePrompt 保留为人类可读投影(透明支柱产品面),与模型路径解耦;ContextAssembler 不再是模型事实源 |

## 3. 设计

### 3.1 RunMessageLedger(`service/context/ledger/`)

```
RunMessageLedger {
    appendUser(text) / appendAssistant(text)
    appendToolRound(List<ToolRecord>)      // 步末 flush: 伪造成对消息
    replaceRange(from, to, Message)        // 压缩用
    List<Message> view()                   // 不可变快照
    pressure(TokenMeter, budget) -> double
}
ToolRecord { name, argsJson, resultText }  // resultText 已过 ToolResultPruner
```

工具轮伪造为协议合法顺序(Spring AI 1.1.2 API 已核实):

```
AssistantMessage(text="", toolCalls=[ToolCall(id, "function", name, argsJson)])
ToolResponseMessage(responses=[ToolResponse(id, name, resultText)])
AssistantMessage(text=步末文本)
```

callId 规则 `call_{runId短}_{seq}`,步内多次工具调用聚合成一对多消息
(一次 toolCalls 列表 + 一个 ToolResponseMessage 多 response)。

### 3.2 账本装配(ChatContextFactory.buildCanonical)

在 snapshot resolve 之后、ChatContext 构造之前:

1. 种子: deriver.deriveFull(sessionKey, memoryWindowMessages) →
   UserMessage/AssistantMessage(复用 ChatMemoryConfig.toChatMessage 的映射语义,
   剥信封提取)。
2. 记忆块: snapshot.memoryBankText/semanticRecallItems/activeLearningRules 渲染为
   单条 user 消息(带"仅供辅助参考"框架语),空则跳过。
3. ChatContext 追加 `messageLedger` 组件(record 尾部追加 + 兼容构造器,
   同 contextSnapshot 先例)。legacy build 与 ChatMemory advisor 路径不接账本
   (ledger=null → 引擎走 legacy prompt)。

### 3.3 LedgerPromptSupport(引擎唯一改面)

```
applyPrompt(spec, ctx, briefing, ledgerOrNull) -> spec
    native: spec.system(ctx.systemPrompt()).messages(ledger.view())
    legacy: spec.system(ctx.systemPrompt()).user(renderLegacyPrompt(ctx, briefing))
```

每个引擎的模型调用点从 `.user(renderXxx(ctx))` 改为这一个入口;legacy 渲染函数
原样保留(回退路径与现有测试锚点)。引擎跨步状态(prompt 字符串)改为持有 ledger,
步间 append 而非重渲染。

### 3.4 工具轮捕获(AOP)

- `ToolExecutionContextHolder` 增 LEDGER_HOLDER(同 TRACKER_HOLDER 先例)。
- ToolRuntimeAspect 在 pipeline execute 段记录 (name, argsJson, prune 后 result)
  到 recorder;read/proposal 两分支统一经过。
- 引擎(Autonomous/Opar 等带 .tools() 的)在 loop 前 set,finally clear;
  步末(拿到模型文本后)`appendAssistant(text)` 触发 pending 工具记录 flush。

### 3.5 引擎迁移与消息序契约

统一消息序: `system(SOUL) → 种子历史 → [记忆块] → briefing+问题 → run 内追加`。

| 引擎 | 迁移要点 |
|---|---|
| BasicStream / ModelLedStream | 单发: 账本=种子+记忆块+问题;无 run 内追加 |
| SimplifiedOpar | 单发同上 |
| OparLoop | 每步 Plan/Act 双调用各自 appendAssistant;假完成拒绝提示改 appendUser |
| AutonomousLoop | 步间工具轮入账(3.4);历史步骤回放(buildStepHistory)退役为展示 |
| ReAct/Reflexion/PlanExecute | 文本协议保留(范式语义),交换载体从"重渲染模板"改为账本 append |
| MultiAgent | coordinator/subtask 调用点接 applyPrompt |
| AgentRuntime | 调用点接 applyPrompt |

### 3.6 LedgerCompactor + CONTEXT_COMPACTED

- pre-step 检查 `pressure > springclaw.context.compaction-trigger-ratio(0.8)`。
- 动作: 保留尾部 `compaction-keep-recent-messages(6)` 条,更早段落(种子+记忆块之外
  的 run 内追加优先)交给模型摘要(source=context-compaction,无工具),替换为单条
  user 摘要消息(标注压缩范围与丢弃规模)。
- 事件: RunEventType 新增 `CONTEXT_COMPACTED("context.compacted")`
  (appendStructuredObservation,不改状态机——turn/step 同模式);payload:
  before/after 估算、丢弃条数、方式(MODEL_SUMMARY|HARD_TRUNCATE)。
- 失败兜底: 摘要调用异常 → 硬截断(头尾保留),WARN。

### 3.7 Token 记账

- TokenMeter 升 @Component(构造无参);预算 `springclaw.context.token-budget-tokens`
  (默认 24000,估算口径);AgentContextMetricsService 增 ledger pressure gauge。

### 3.8 配置汇总

```
springclaw.runtime.native-context-enabled: true        # 总开关(false=全 legacy)
springclaw.context.token-budget-tokens: 24000
springclaw.context.compaction-enabled: true
springclaw.context.compaction-trigger-ratio: 0.8
springclaw.context.compaction-keep-recent-messages: 6
```

### 3.9 清理项(迁移完成后)

- ContextAssembler: 删 looksLikeFileListing/extractFileNamesFromListing/
  extractFileNameFromPath/truncateFileName(正则抢救退役)。
- ConversationTurn.canonical 220 截断仅保留给 legacy 回退与展示;原生路径全走
  deriveFull。
- CoreCapabilityStatusLogger 的 ChatMemory 降级 WARN 措辞更新(原生账本接替)。

## 4. 兼容与回退

- 开关默认 true(大刀阔斧),但 legacy 渲染路径全量保留:任一引擎可独立回退。
- 账本装配异常(deriver 抛错等)→ 该请求降级 legacy prompt,WARN+metric,不静默。
- 观察面: AssembledContext/observePrompt/trace 事件结构不变,前端零改动。
- 持久化: ContextSnapshot/RunState JSON 结构零变化(账本为瞬态派生)。

## 5. 测试计划(纯 JUnit+Mockito)

- RunMessageLedgerTest: append/伪造对序/pressure/replaceRange。
- LedgerPromptSupportTest: native/legacy 双路、消息序、回退。
- 账本装配: ChatContextFactory canonical 路径种子/记忆块/异常回退。
- AOP 捕获: ToolRuntimeAspect 记录 + prune 后入账。
- LedgerCompactorTest: 触发/摘要替换/失败硬截断。
- 各引擎: 既有测试从"prompt 文本断言"迁移为"消息序断言"(legacy 断言保留)。

## 6. 实施批次

1. P1 基础设施: ledger/support/TokenMeter 接线/ChatContext 扩展/开关。
2. P2 默认链路: BasicStream/ModelLedStream/SimplifiedOpar。
3. P3 工具型: AutonomousLoop/OparLoop(工具轮跨步保留)。
4. P4 范式引擎: ReAct/Reflexion/PlanExecute/MultiAgent/AgentRuntime。
5. P5 压缩器 + CONTEXT_COMPACTED + 指标。
6. P6 清理 + 全量回归 + 附录 A 回填。

## 附录 A: 实施结果

分支 `feat/native-context-ledger`(基于 main@54fa7134),5 commit,41 文件 +2071/-62。

### 落地清单(按批次)

| 批次 | 内容 | 关键文件 |
|---|---|---|
| P1 | RunMessageLedger(append/伪造工具消息对/pressure/replaceRange/markRunInitialized 幂等)、LedgerToolRecorder(步内收集,args 1200/结果 6000/32 条上限)、LedgerPromptSupport(native/legacy 双路,applyPrompt 显式 system)、ChatContext 增 messageLedger 组件(18 参兼容构造)、ChatContextFactory.canonical 装配(deriveFull 种子+记忆块置尾,失败 WARN 回退 null)、ToolRuntimeAspect 入账(@Tool name 优先)、TokenMeter @Component | service/context/ledger/*、ChatContextFactory、ToolRuntimeAspect |
| P2 | BasicStream/ModelLedStream/SimplifiedOpar: applyPrompt 接入 + briefing 常量 + initRun 幂等;SimplifiedOpar legacy 重载保持调用方 systemPrompt 语义 | 三引擎 |
| P3 | OparLoop(Plan/Act phase briefing+planResult/actOutput 入账,act 挂 recorder)、AutonomousLoop(每步 recorder→appendAssistantOutput,**跨步工具结果保留**)、ChatServiceImpl.streamReflectAnswer(reflect briefing+全量账本) | OparLoopEngine、AutonomousLoopEngine、ChatServiceImpl |
| P4 | ReAct(稳定 system+Observation 入账)、Reflexion(尝试+反思教训入账)、PlanExecute(计划步+Observation)、MultiAgent(子任务+Worker 结果,账本 synchronized 支持跨线程);**TypedContextPromptRenderer.promptPrefix 在 native 模式返回空**(单点消除注入重复);四引擎旧签名兼容构造器,测试零改动 | 四引擎+Renderer |
| P5 | LedgerCompactor(预算钳制 [2000,+∞)/ratio [0.3,0.99]/模型摘要降级硬截断)、RunEventType.context.compacted、RunCoordinator.contextCompacted、AgentLoopKernel 步前单点接线(ObjectProvider,缺席不阻塞)、AgentContextMetricsService.recordLedgerCompaction | LedgerCompactor、AgentLoopKernel |
| P6 | ContextAssembler 正则抢救退役(looksLikeFileListing 等 4 函数删除,~90 行)、类注释降级为"展示投影层" | ContextAssembler |

### 与设计的偏差(登记)

1. **AgentRuntimeEngine 未迁移**:其两处模型调用是程序化 JSON 契约调用(证据反思器/摘要组装),prompt 全量由结构化产物构造、不含会话历史;接账本收益边际。列为后续候选,模式照 ReAct。
2. **消息序契约微调**:ReAct 的 Observation 与 Reflexion 反思教训/PlanExecute 计划步/MultiAgent 子任务以 **user 消息**入账(文本协议范式的环境消息等价物),伪造 tool_use/tool_result 消息对仅用于原生工具引擎(Autonomous/OPAR-act)——文本协议下模型从未发出 tool_use,伪造反而失真。
3. **压缩区间**:v1 压缩 [0, size-keepRecent)(含种子+记忆块),摘要指令强制保留任务目标;未做"种子豁免"(种子本身即历史,豁免会让压缩失效)。
4. **ContextSnapshot 持久化格式未动**:账本是瞬态派生(每次 build 从 canonical 日志重派生),spec §3.2 的"挂 snapshot"改为"挂 ChatContext"——避免 RunState JSON 变更,回滚面更小。

### 已知边界

- 流式引擎(reactor 线程执行工具)读不到 ThreadLocal recorder→工具轮不入账(单发 run 无步间需求,降级为纯文本历史,不报错)。
- 跨 run 工具轮不重建(种子仅 USER/ASSISTANT 原文)——TOOL_REQUESTED/SUCCEEDED 事件 payload 不含 args/结果,富化后可作 v2。
- legacy 测试构造点注入 `new LedgerPromptSupport(false)`——既有 prompt 文本断言全部保留为 legacy 语义;native 路径由新增 5 个测试类(38 用例)覆盖。
- 全量回归:Redis/MySQL 依赖的 6 个 @SpringBootTest 类在本机无服务环境下失败(基线 main 同样失败,已验证,非本改革回归)。