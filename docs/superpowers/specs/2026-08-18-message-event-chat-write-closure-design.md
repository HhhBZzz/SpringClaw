# message_event 写侧双轨收口设计(CHAT 对话行)

- 日期: 2026-08-18
- 状态: approved(用户委托"继续完成",2026-08-18 session)
- 来源: 2026-08-17-canonical-conversation-history-design §7 留置项("写侧双轨直到读切换验证完成后另行收口")
- 前置: T1–T4 已落地(读主路 ContextAssembler 已切 canonical + 回退;1137 绿;当前基线 1155 绿)

## 1. 问题

T1–T4 落地后 canonical 事件日志与 message_event 对同一对话事实双写,但:

1. **canonical 内容缺口**: confirmation 挂起路径(persistSuspension)不发 `assistant.answer`,
   挂起 run 在 canonical 历史里只有 USER 没有 ASSISTANT——双源不对称,写侧停写会丢挂起提示语。
2. **读侧未切完**(附录 A 自己留的尾巴 + 2026-08-18 六路侦察精确化):
   - `ConversationHistoryService` / `OparContextAwareSupport`("第一条消息/上一条/文件候选"本地技能)——message_event 单源
   - `MessageEventChatMemory`(spring-ai-chat-memory-enabled,默认 false)——单源
   - `GET /api/chat/history`——全库唯一无 canonical 回退的用户端历史端点
   - `MemoryCoordinator.shortTermItems`——**默认部署下最危险的单源**:canonical snapshot 路径(factory-enabled 默认 true)的 durable 对账源只有 message_event,无开关
3. **写侧无开关**: 停写对话行需要改代码,不是一个可灰度/可回滚的配置动作。
4. **派生 turn 无归属信息**: ConversationTurn 缺 channel/userId,下游 scope 过滤
   (MemoryCoordinator PERSONAL_SESSION 鉴权、history 端点越权校验)做不了。

侦察确认的前提事实:
- message_event 全部写入收口于 `MessageEventServiceImpl.append` 单一入口;USER/ASSISTANT CHAT
  对话行只有 `ChatResultPersister` 4 处写入点(persistTerminal :112/:115、persistSuspension :158/:161)。
- `TaskExecutionService.persistTaskTurn` 的 TASK 行无 canonical 对偶,**不在本次收口范围**。
- SYSTEM 诊断行(ROUTING/PLAN/ACT/MEMORY_USAGE)、TRACE 行、TOOL/WEBHOOK/路由切换等审计行
  均非对话语义,**不在本次收口范围**。
- RunState 携带 sessionKey/channel/userId,deriver 已逐 run 加载 RunState——归属信息无需改事件 payload。

## 2. 目标

**写侧收口可配置化**: 新增开关 `springclaw.runtime.message-event-chat-write-enabled`
(默认 true=维持双写),翻转后即停止 ChatResultPersister 的 USER/ASSISTANT CHAT 行写入,
且所有 LLM 上下文/用户可见读路径已具备 canonical 主路 + legacy 回退,翻转不丢数据、不炸请求。

### 用户决策点(本设计的默认选择)

| 决策点 | 选择 | 理由 |
|---|---|---|
| 本批是否直接停写 | 否——开关默认 true 维持双写 | 读切换尚无生产里程;审计面(AuditController/AdminManageController 的 CHAT 行消费)降级是产品决策,留给用户择时翻转 |
| suspension 挂起提示语 | canonical 补发 `assistant.answer`(answerKind=`SUSPENDED`) | 消除双源不对称;wire 名为稳定契约不变,answerKind 是自由字符串字段,加取值是增量兼容 |
| 读切换开关 | 复用 `springclaw.runtime.conversation-history-source`(canonical\|message-event,默认 canonical) | spec §3.3 已点名这些读路径"同模式切换";一个开关一个语义:历史事实源 |
| 展示/精确查询路径的截断 | deriver 新增 `deriveFull`(不截断);`derive` 保持 220(LLM 上下文用) | "第一条消息是什么"与 /history 展示需要全文;LLM 上下文截断规则不动,T4 等价性不受影响 |
| 双源拼接形态 | 沿用"canonical 空→整段回退 legacy"最小形态 | 附录 A 已论证交错拼接在纯存量会话场景无收益 |

## 3. 设计

### 3.1 T5a — suspension canonical 答案补齐

`ChatResultPersister.persistSuspension` 在既有 message_event 双写之外,经
`RunLifecycleObserver.assistantAnswer(requestId, assistantMessage, "SUSPENDED", now)` 发射
canonical 事件(与 persistTerminal 同位置、同 null 守卫)。

deriver 无需改动(只读 payload.answer,不看 answerKind)。
效果: 挂起 run 的 canonical 历史从"只有 USER"变为"USER + ASSISTANT(确认提示语)",
与 legacy 行集对齐——T4 等价性测试扩 suspension 场景断言。

### 3.2 T5b — ConversationTurn 归属富化

record 增加 `channel`、`userId` 两组件;deriver 从已加载的 RunState 填充(无新增 IO)。
`ConversationTurn.canonical(...)` 工厂签名扩参;`ContextAssemblerHistorySourceTest` 两处
裸构造同步更新。纯增量,渲染逻辑(readTurnLine)不读这两个字段。

### 3.3 T5c — deriveFull 变体

```java
derive(sessionKey, limit)       // 既有:220 截断,LLM 上下文用
deriveFull(sessionKey, limit)   // 新增:不截断,展示/精确查询/帧构建用
```

内部统一为 `derive(sessionKey, limit, boolean truncate)`;截断点在 toTurn 一处。

### 3.4 T5d — 读侧剩余五路切换(统一模式)

统一模式: 开关=canonical 且 deriver 非空 → 走派生;派生抛异常或结果为空 → WARN + legacy 原路径
(与 ContextAssembler T3 完全一致;开关是兜底舱,不做双读对账)。

| 读路径 | canonical 映射 | legacy 回退 |
|---|---|---|
| `ConversationHistoryService` | findFirst/Latest: deriveFull → 过滤 USER turn(时间正/倒序取);countRemembered: derive(sessionKey, 400) 计 USER turn 数(有界,文档化) | 原 listSessionEvents/countSessionEvents |
| `OparContextAwareSupport.extractFileCandidatesFromRecentAssistant` | deriveFull(sessionKey, 4) → 最后一条 ASSISTANT turn → parseFileNamesFromAnswer 直解析(canonical 无 [REFLECT] 前缀,不需剥离) | 原 listSessionEvents 路径 |
| `MessageEventChatMemory.get` | deriveFull(conversationId, memoryWindowMessages) → USER→UserMessage / ASSISTANT→AssistantMessage | 原 listSessionEvents 路径 |
| `ChatController /api/chat/history` | canonical 非空: turns → ChatHistoryMessage(id=`runId:role`, role user/agent, content 原文, createdAt=turn.at);越权校验: 任一龙 turn.userId==username 放行,否则 403 | canonical 为空: 原 countSessionEvents 校验 + listSessionEvents 渲染 |
| `MemoryCoordinator.shortTermItems` | canonical durable 源替代 readShortTermChatEvents: deriveFull(sessionKey, 40) → scope 过滤(channel 匹配 + PERSONAL_SESSION userId 匹配) → 合成 ShortTermMemoryEntry → 同既有 watermark/mergeRecovery 管线 | 异常/空 → 原 message_event durable 读;再失败 → 缓存(既有三级不变) |

**MemoryCoordinator 合成身份规则**(ShortTermMemoryEntry 约束: eventId>0、eventKey/requestId/role/userId/content 非空、content≤4000):

- `eventId = at.toEpochMilli() * 4 + slot`(USER=1, ASSISTANT=2): 正数、近似单调、
  同 run 内有序;跨 run 同毫秒同 slot 的理论冲突后果仅是 zset 同分字典序兜底,无害。
- `eventKey = "canonical:<runId>:user" | "canonical:<runId>:assistant"`(稳定幂等键)。
- `requestId = runId`;`userId`/`channel` 取自 turn 归属;`content` 截断 4000(deriveFull 原文)。
- watermark = 合成 eventId 最大值,mergeRecovery 语义不变。
- 与 shadow 写(Redis 缓存)的关系: 对账成功时返回值只取 durable 派生条目(与 legacy 路径
  同构——durable 成功即不读缓存条目),缓存仍是 durable 失败时的兜底。

### 3.5 T5e — 写侧开关

```
springclaw.runtime.message-event-chat-write-enabled: ${SPRINGCLAW_RUNTIME_MESSAGE_EVENT_CHAT_WRITE_ENABLED:true}
```

- application.yml 声明(补单一默认值事实源 + 友好 env 别名;同时补登 T3 遗漏的
  `conversation-history-source: ${SPRINGCLAW_RUNTIME_CONVERSATION_HISTORY_SOURCE:canonical}`)。
- 接线点: `ChatResultPersister` @Autowired 构造器加 boolean 参数;4 处 CHAT append
  (persistTerminal :112/:115、persistSuspension :158/:161)包条件;既有便捷构造器传 true 保持兼容。
- false 时: 跳过 CHAT 行 append → receipt 为 null → shadowTerminal/shadowSuspension 既有
  null 守卫自然跳过(Redis 由 MemoryCoordinator canonical 对账维持温度);SYSTEM OPAR 行、
  MEMORY_USAGE 行、TASK 行、TRACE/TOOL/审计行全部照写。
- canonical 发射(assistantAnswer/userMessage)不受此开关影响——那是主事实源。

### 3.6 翻转前置条件(spec 登记,非本批实施)

在 `/api/chat/history` 与审计面之外,翻转 write-enabled=false 前需确认:
1. canonical 读路径生产里程(本 PR 合入后观察窗口);
2. `TerminalMemoryExtractionService`(semantic-extraction.enabled 默认 false)仍为
   message_event 单源——启用该开关前须先完成其 canonical 切换,而其等价性依赖
   诊断行合成(ROUTING/PLAN/ACT payload 富化,附录 A 偏差项①);
3. `ShortTermMemoryRecoveryService`(recover 无 main 调用方的休眠工具)保持 legacy——
   其语义就是"MySQL→Redis 修复",post-flip 由 MemoryCoordinator 对账维持 Redis;
4. 审计面 CHAT 行消失(AuditController 日志/stats、AdminManageController 活跃度)
   是翻转的既定代价,需用户明确接受。

## 4. 不变式

1. 开关默认态 = 今天的行为(双写 + canonical 读主路 + legacy 回退),零行为变化。
2. 派生是纯读;canonical 事件 append-only。
3. 每条新切换路径: canonical 异常/空 → legacy 回退,不炸请求。
4. SYSTEM 诊断/TRACE/TOOL/审计/TASK 行写入行为不变。
5. 审计消费方(message_event 直读)不动。

## 5. 测试策略(纯 JUnit5 + Mockito,不起 Spring 上下文)

1. **T5a**: persister suspension 发射断言(mock observer verify assistantAnswer SUSPENDED);
   等价性测试扩 suspension 场景(canonical 与 legacy 行集对齐)。
2. **T5b/c**: deriver 测试——归属字段填充、deriveFull 不截断、derive 保持 220。
3. **T5d**: 每路 3 用例(canonical 命中 / 异常回退 / 空回退)+ legacy 开关位既有断言不动;
   MemoryCoordinator 增 scope 过滤(PERSONAL_SESSION 他人 turn 剔除、channel 不匹配剔除)
   与合成 entry 不变式(eventId>0、content≤4000)。
4. **T5e**: write-enabled=false → messageEventService 零 CHAT append(verify never),
   canonical 发射不受影响;默认 true → 既有断言不动。
5. **全量回归**: 1155 基线全绿。

## 6. 实施拆分(预计 5 个 commit)

1. **T5a+b+c**: suspension 发射 + turn 归属 + deriveFull(deriver/persister/测试)
2. **T5d-1**: ConversationHistoryService + OparContextAwareSupport 切换
3. **T5d-2**: MessageEventChatMemory + /api/chat/history 切换
4. **T5d-3**: MemoryCoordinator canonical durable 源
5. **T5e+f**: 写侧开关 + yml 声明 + 本 spec 附录实施结果 + 全量回归

## 7. 明确不做

- 不实现诊断行合成(ROUTING/PLAN/ACT)与 decision.made/step payload 富化(附录 A 偏差项①,另立)。
- 不切 TerminalMemoryExtractionService / ShortTermMemoryRecoveryService(见 §3.6)。
- 不停 TASK 行、SYSTEM 行、TRACE 行、TOOL/审计行写入。
- 不动审计/管理端消费方;不做生产双读对账。
- TokenMeter 接线(6e9d20f3 留置的零调用方问题)与本次收口无关,另立。

## 附录 B: 实施结果(2026-08-18)

| Commit | 内容 | 测试 |
|---|---|---|
| 2521a7ca (T5a+b+c) | persistSuspension 补发 assistant.answer(SUSPENDED);ConversationTurn 增 channel/userId(取自 RunState);deriver 新增 deriveFull 不截断变体 | deriver+2(归属/deriveFull)、persister+1(SUSPENDED 发射)、等价性+suspension 场景;目标包 25 绿 |
| d353efc4 (T5d-1) | ConversationHistoryService 切换(canonical 精确查询/窗口计数+回退,OBSERVE 信封两路对齐);OparContextAwareSupport 文件候选 canonical 直解析(无 [REFLECT] 剥离) | ConversationHistoryService+4、OparContextAware+2;16 绿 |
| 233f5772 (T5d-2) | MessageEventChatMemory 切换(内容提取两路对齐);ChatController /history canonical 渲染(id=runId:role)+turn 归属越权校验+回退;ChatController 10 参历史构造器保留 | ChatMemory+2、history 端点+3;17 绿 |
| 2d59fa93 (T5d-3) | MemoryCoordinator 短期层 durable 源切换(合成 eventId=epochMilli*4+slot、eventKey=canonical:<runId>:<role>;watermark/mergeRecovery 管线复用;三级回退不变);MemoryFrameSourceKind +CANONICAL_RUN_EVENT;MemoryFrameConfig 装配 | MemoryCoordinator+4(scope 过滤/异常回退/空回退/对账并入);两处 wiring 测试补 deriver bean;34 绿 |
| (T5e+f) | 写侧开关 springclaw.runtime.message-event-chat-write-enabled(默认 true)接 ChatResultPersister 4 处 CHAT append;application.yml 补登本开关与 conversation-history-source(T3 遗漏);本附录 | persister+2(停写后 canonical/SYSTEM/shadow 行为断言);全量回归 |

偏差与备注:
- §3.4 ConversationHistoryService 的 canonical "第一条消息"是最近窗口语义(deriver
  runScanLimit≤200):超过 200 run 的会话,首条退化为窗口内首条。精确全文首条依赖
  尚未实现的 findEventsBySession(T2 偏差遗留),已在类 javadoc 登记。
- countRememberedUserQuestions canonical 计数按最近 400 turn 窗口(文档化上界)。
- MemoryCoordinator canonical 条目经 eventKey "canonical:" 前缀携带来源标记,
  fromShortTerm 据此区分 sourceKind——未改 fromShortTerm 签名。
- ChatController 越权校验在 canonical 路径用 turn 归属(任一 turn.userId==当前用户放行),
  与 legacy 计数语义等价但粒度更细;canonical 空→legacy 计数校验不变。
- 翻转前置条件(§3.6)不变:TerminalMemoryExtractionService 仍 legacy 单源(默认关闭),
  诊断行合成未实现——两者是 write-enabled=false 生产翻转前的登记项。

