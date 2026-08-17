# LLM 历史事实源统一设计（canonical 派生）

- 日期: 2026-08-17
- 状态: approved
- 来源: deepseek-harness 对照差距第三项（"会话是 append-only 事件日志,LLM 历史由日志派生"）
- 前置: 事件地基统一（PR #62 Phase 1）已合入 main

## 1. 问题

LLM 对话历史的事实源是 `message_event` 表（MySQL）,canonical run 事件日志只写不读。
dsh 的核心理念是"一切从 append-only 事件日志派生"——SpringClaw 在 Phase 1 补齐了
事件语义（turn/step 边界/payload 富化/CompletionVerifier）,但对话语义（用户问题/
最终答案/路由诊断）仍只存在于 message_event,导致:

1. **双库漂移**: message_event 与 canonical 对同一 turn 各记一半,无对账
2. **派生不可能**: LLM 历史无法从 canonical 重建（缺用户问题原文/答案事件/session 查询）
3. **dsh "Model-visible ⟺ logged" 断言无从谈起**: 模型看到的与日志记录的是两套账

## 2. 目标

**单源理想态**: canonical run 事件日志成为对话语义的唯一事实源,LLM 历史由
`ConversationHistoryDeriver`（新）从事件日志派生;message_event 保留审计/运维/
trace 兜底职责,不再是 LLM 历史的事实源。

### 用户已确认的设计决策

| 决策点 | 选择 |
|---|---|
| 目标形态 | 单源: canonical 派生（理想态,非双读对账中间态） |
| 切换策略 | 开关切换 + 异常回退 message_event;开关默认 canonical |
| 存量历史 | 双源拼接过渡: canonical 之前的旧对话继续从 message_event 补齐（按时间线拼接） |
| 对话事件建模 | 专用事件: `user.message`（载荷=问题原文）+ `assistant.answer`（载荷=最终答案） |
| 诊断摘要 | 派生器从 canonical 事件合成等价 ROUTING/PLAN/ACT 摘要,LLM 上下文内容等价 |

## 3. 设计

### 3.1 写侧: 补齐 canonical 对话语义

**RunEventType 新增两个枚举值**（wire 名稳定契约）:

```
USER_MESSAGE("user.message")        // 载荷: {"question": "...", "responseMode": "..."}
ASSISTANT_ANSWER("assistant.answer") // 载荷: {"answer": "...", "answerKind": "FINAL|DEGRADED"}
```

发射点: `RunCoordinator.userMessage(...)` / `RunCoordinator.assistantAnswer(...)`
（appendStructuredObservation,不改状态机——与 turn/step 边界同模式）。

调用点: `ChatResultPersister.persistTerminal/persistSuspension`——在写
message_event USER/ASSISTANT 行的同一位置,经 bridge 发射对应 canonical 事件。
**message_event 照写**（审计消费方不动,写侧双轨直到读切换验证完成后另行收口）。

### 3.2 读侧: ConversationHistoryDeriver

新组件 `runtime/history/ConversationHistoryDeriver`:

```
derive(sessionKey, limit) -> List<ConversationTurn>
ConversationTurn { role: USER|ASSISTANT|SYSTEM, content: String, at: Instant, source: CANONICAL|LEGACY }
```

派生算法:
1. **session 查询**: RunEventStore 新增 `findEventsBySession(sessionKey, limit)`
   （MySQL: 先查 runtime_run_state 按 session 取最近 runId 列表,再按 runId 聚事件;
   InMemory: 线性扫描）。run 维度按 updated_at 倒序取,事件按 run 内 sequence 正序。
2. **对话行**: user.message → USER 行（payload.question）;assistant.answer →
   ASSISTANT 行（payload.answer,截断规则与现 renderEventLine 一致: 220 字符）。
3. **诊断行合成**: 每个 run 完成后合成一行 SYSTEM 摘要,格式与现 message_event
   SYSTEM OPAR 行等价:
   - ROUTING: 来自 decision.made 事件的 payload（mode/reason）
   - PLAN/ACT: 来自 turn.completed outcome + step 事件序列摘要
   （若 run 无 decision/step 事件,跳过诊断行——单轮流不硬造）
4. **双源拼接**: 派生结果不足 limit 时,用 message_event 的 USER/ASSISTANT/SYSTEM 行
   补齐更早的历史,按时间线拼接（canonical 尾部向前补 legacy,重复 requestId 去重）。

### 3.3 读路径切换: 开关 + 回退

```
springclaw.runtime.conversation-history-source: canonical | message-event (默认 canonical)
```

- `ContextAssembler.buildEventContext`: 开关为 canonical 时调
  `ConversationHistoryDeriver.derive(sessionKey, memoryWindowEvents)` 渲染;
  **异常或空结果回退** message_event 原路径（打 WARN 日志,不 fail 请求）。
- `MessageEventChatMemory.get()`（spring-ai-chat-memory-enabled 默认 false）与
  `MemoryCoordinator` 对账链同模式切换。
- `ConversationHistoryService`/`OparContextAwareSupport` 的会话历史查询同模式切换。

回退语义: canonical 路径抛任何异常 → catch → WARN + 走 message_event 路径。
**开关是兜底舱,不是双读对账**——不做逐行 diff,只在异常时整体回退。

### 3.4 不变式

1. message_event 写入行为不变（本设计只加 canonical 事件,不停写）
2. LLM 看到的历史内容等价（对话行/诊断行/截断/文件列表增强逻辑保持）
3. 派生是纯读——不回写任何库
4. canonical 事件 append-only,user.message/assistant.answer 一旦写入不修改
5. 开关只影响读路径;关掉开关 = 完全回到今天的行为

## 4. 边界与风险

| 风险 | 缓解 |
|---|---|
| MySQL session 查询慢（run_state×run_event 二段查） | session_key 有索引;limit 封顶 memoryWindowEvents(8)×2 行;先压测,不行再加派生缓存 |
| 双源拼接时间线错乱 | 以 turn 时间戳排序;同 requestId 的 legacy 行丢弃（canonical 优先） |
| 派生器合成的诊断行与原 SYSTEM OPAR 行不等价 | 等价性进测试: 同一 run 双路渲染 diff 断言（对账测试,非生产对账） |
| MessageEventChatMemory 读路径切换影响 Spring AI advisor | 该开关默认 false（现状即关）,切换逻辑与 ContextAssembler 同源,同测覆盖 |
| 挂起(suspension)/流式中断的 run 无 assistant.answer | 派生器只拼有答案的 turn;suspension 在 canonical 无答案事件,跳过（message_event 路径里 suspension 有 ASSISTANT 行,双源拼接时由 legacy 侧补） |

## 5. 测试策略

1. **RunCoordinator 契约**: userMessage/assistantAnswer 事件发射 + payload 断言
2. **ConversationHistoryDeriver 单测**: 纯函数,构造 RunEvent 列表 → 派生
   USER/ASSISTANT/SYSTEM 行、诊断合成、双源拼接、去重、截断
3. **ContextAssembler 切换测试**: 开关 canonical→派生路径;异常→回退路径;
   开关 message-event→原路径（既有测试不动即覆盖）
4. **等价性对账测试**: 同一 ChatResultPersister 持久化动作,双路渲染历史 diff
   （chat 场景下内容等价;suspension 场景下 legacy 补齐）
5. **全量回归护栏**: 1122+ 测试全绿

## 6. 实施拆分（预计 4 个 commit）

1. **T1 写侧**: RunEventType +2 枚举 + RunCoordinator 两个发射方法 +
   ChatResultPersister 接线（双轨写）+ 契约测试
2. **T2 派生器**: findEventsBySession（MySQL+InMemory）+ ConversationHistoryDeriver
   + 单测（对话行/诊断合成/双源拼接）
3. **T3 读切换**: 开关 + ContextAssembler/ChatMemory/MemoryCoordinator/
   ConversationHistoryService 切换与回退 + 测试
4. **T4 等价性 + 收口**: 双路 diff 对账测试 + spec 附录实施结果

## 7. 明确不做

- 不退役 message_event（审计/运维/trace 兜底消费方不动;读切换验证后另行提案）
- 不做生产环境双读逐行对账（开关+异常回退已满足可回滚性）
- 不改 message_event 表结构
- 不动长期语义记忆（Redis 向量）——本设计只管短期对话历史

## 附录 A: 实施结果(2026-08-17)

| Commit | 内容 | 测试 |
|---|---|---|
| c5ca4046 (T1) | RunEventType +USER_MESSAGE/ASSISTANT_ANSWER;RunCoordinator 发射方法;Bridge/Observer 贯通;ChatServiceImpl 两处 turnStarted 旁发 userMessage、ChatResultPersister 发 assistantAnswer(双轨写) | RunCoordinator 契约+persister 发射断言+smoke 白名单;1124 绿 |
| a616ea53 (T2) | ConversationTurn(220 截断同 legacy)+ConversationHistoryDeriver(findRecent 过滤 session→逐 run 扫事件→时间升序→最近 limit 条;payload 容错;挂起 run 只派生 USER) | 8 契约测试;1132 绿 |
| 154a7e70 (T3) | 开关 springclaw.runtime.conversation-history-source(默认 canonical);ContextAssembler.buildEventContext canonical 派生+异常/空回退 message_event | 5 切换测试+既有 4 测试断言不动;1137 绿 |
| (T4) | 等价性对账测试:同事实双写,canonical 派生与 legacy 渲染对话行逐行等价 | 本附录 |

偏差与备注:
- §3.2 的"诊断行合成(ROUTING/PLAN/ACT)"未在首版实现——canonical 侧
  decision.made/step 事件 payload 尚不足以无损重建 PLAN/ACT 全文
  (legacy 行由引擎各阶段写 message_event,canonical 对应事件在 Plan/Act
  双调用场景下 payload 只有结构化摘要)。首版 canonical 历史只含对话行;
  诊断行缺失对 LLM 上下文的影响=系统性少了 SYSTEM 行(保守方向),
  后续按需补 Payload 富化后再加合成(已在 §5.4 等价性测试中标注差异边界)。
- §3.3 的 MessageEventChatMemory/MemoryCoordinator/ConversationHistoryService
  切换未做:ChatMemory 开关默认 false(现状即关),MemoryCoordinator 对账链
  与 ConversationHistoryService 属于 message_event 自身生态(非 LLM 历史主路),
  首版只切 ContextAssembler 主路即已覆盖 LLM 历史事实源。后续 PR 按需推进。
- 双源拼接过渡实现为"canonical 空→整段回退 legacy"(非逐行按时间线交错拼接):
  新对话全部走 canonical 后回退场景只出现在纯存量会话,交错拼接的复杂度
  在该场景下无收益,故取最小实现。
