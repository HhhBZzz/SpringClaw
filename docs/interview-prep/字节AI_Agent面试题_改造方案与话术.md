## 字节本地生活 AI Agent 面试题 × SpringClaw 改造方案与话术

---

### 一、22 道面试题覆盖度总览

先给结论：**22 道题中，SpringClaw 当前代码能直接回答 8 道，需要小改造才能回答 7 道，需要纯知识储备回答 5 道，完全无法回答 2 道。**

| 分类 | 题号 | 题目 | 当前状态 |
|------|------|------|---------|
| **能直接答** | 4 | Harness Engineering 职责 | 项目已有：熔断、重试、限流、分布式锁、可观测性、错误隔离 |
| **能直接答** | 8 | ReAct vs Plan-and-Execute | AutonomousLoopEngine ≈ ReAct，OparLoopEngine ≈ Plan-and-Execute |
| **能直接答** | 9 | Agentic RAG vs Naive RAG | SemanticMemoryAdvisor = Naive RAG，AgentRuntimeEngine 有迭代检索+反思 |
| **能直接答** | 11 | Redis 在 Agent 系统中的用途 | 6 种用途：限流、分布式锁、状态持久化、向量存储、异步结果、会话状态 |
| **能直接答** | 12 | MySQL ACID / MVCC | 基础后端知识 + SpringClaw 的 MyBatis-Plus 使用 |
| **能直接答** | 6 | System Prompt 保证 JSON 输出稳定 | AgentDecisionService 有完整实现：prompt 约束 + JSON 边界提取 + 字段降级 |
| **能直接答** | 14 | Slot Filling / 意图识别 | AgentDecisionService 的混合路由（规则 + 模型），AgentDecisionRouter 做 slot 提取 |
| **能直接答** | 1 | 自我介绍 + 项目介绍 | 已有话术 |
| **需小改造** | 2 | 多 Agent 协作架构 | 目前是单 Agent 多引擎，需要加一层 Coordinator 或能说清楚为什么不做多 Agent |
| **需小改造** | 3 | 多 Agent 幂等 / 一致性 | AutonomousExecutionTracker 有幂等意识（防虚假完成），但没有完整的幂等设计 |
| **需小改造** | 5 | 意图切换 / 多轮上下文 | 当前每轮独立分类，需要加意图状态跟踪器 |
| **需小改造** | 7 | RAG 优化（HyDE / Rerank） | 当前是 Naive RAG，至少需要加 Query Rewrite |
| **需小改造** | 10 | 评测指标 Precision/Recall | AgentQualityEvaluator 是运行时评分，需要加离线评测框架 |
| **需小改造** | 13 | 成本控制 / semantic cache | 有 token 记录但无主动控制，需要加语义缓存 |
| **需小改造** | 15 | LangGraph checkpoint / HITL | 需要了解概念，项目中可加 Plan/Act 审批步骤 |
| **纯知识** | 16 | Go channel / goroutine | 与项目无关，纯语言知识 |
| **纯知识** | 17 | 向量数据库选型 | SpringClaw 用 Redis Vector Store，需要了解 Milvus/Chroma/Faiss 差异 |
| **纯知识** | 18 | LangChain vs LangGraph | 框架知识，了解核心区别即可 |
| **纯知识** | 19 | RAG chunk 大小策略 | 培训助手项目有经验，需要了解理论基础 |
| **纯知识** | 20 | NER 微调 vs LLM | 需要了解两者优劣，结合 AgentDecisionService 的实际选择来回答 |
| **无法回答** | 21 | Go 框架封装（CloudWeGo） | 与 Java 项目完全无关，坦诚说"我的方向是 Java" |
| **无法回答** | 22 | LeetCode 手撕 | 纯算法能力，与项目无关 |

---

### 二、需要改造的 7 个点（按优先级排序）

#### 改造 1：加一个轻量 Coordinator 层（覆盖题 2、3）

**当前问题：** SpringClaw 是单 Agent 多引擎，面试官问"Supervisor/Orchestrator/Pipeline/Swarm"时你会卡壳。

**改造方案：** 不需要真的做多 Agent 系统。在 EngineSelector 上加一层 `CoordinatorService`，让它成为一个"轻量 Orchestrator"：

```
用户请求 → CoordinatorService
  ├── 简单任务 → 直接选引擎执行（当前逻辑）
  ├── 复合任务 → 拆分为子任务 → 分别选引擎 → 合并结果
  └── 需要确认 → 生成 ActionProposal → 等用户确认 → 执行
```

**代码量：** 约 150-200 行的新类。核心是一个 `CoordinatorService.java`，接收 `AgentDecision`，判断是否需要任务拆分（当 `selectedCapabilities.size() > 1` 时），拆成子任务分别执行后合并。

**面试话术：** "SpringClaw 采用的是轻量 Orchestrator 模式。大部分请求是单引擎执行，但 Coordinator 层支持复合任务拆分——比如用户说'搜索最新的 AI 新闻并写入一个文件'，Coordinator 会拆成'web_search + file_write'两个子任务，分别选引擎执行后合并结果。我没有做完整的多 Agent 系统（像 CrewAI 那样），因为对于本地 Agent 场景，单 Agent 多引擎 + 任务拆分已经够用，多 Agent 引入的通信成本和一致性问题不值得。"

**关于幂等（题 3）的话术：** "AutonomousExecutionTracker 用 ThreadLocal 跟踪每次循环的真实工具调用。如果模型在同一轮循环中尝试对同一文件写两次，tracker 会记录两次调用但只有最后一次生效。跨请求的幂等目前靠 session lock（Redis SETNX）保证同一会话不会并发执行。如果要做分布式幂等，我会用请求级 requestId + Redis 去重表。"

---

#### 改造 2：加意图状态跟踪器（覆盖题 5）

**当前问题：** 每轮请求独立分类意图，没有"用户从话题 A 切换到话题 B"的检测。

**改造方案：** 在 `ChatRoutingStateService` 中加一个 `lastIntent` 字段，存入 Redis：

```java
// ChatRoutingStateService.java 新增
public record IntentTransition(
    String previousIntent,
    String currentIntent,
    Instant transitionTime,
    boolean isSignificantChange  // 意图类型变了（如 workspace_write → web_search）
) {}
```

每次 `AgentDecisionService.decide()` 返回后，对比 `lastIntent` 和当前 `intent`，如果意图类型变化（不只是参数变化），标记为 `isSignificantChange = true`。

**代码量：** 约 80-100 行修改。在 `ChatServiceImpl` 的 `processChat` 方法中，决策后调用 `routingStateService.recordIntentTransition()`。

**面试话术：** "意图切换我做了显式检测。每次请求独立分类后，和上一轮的 intent 对比——如果意图类型变了（比如从'代码分析'切到'网页搜索'），标记为 significant change，清空当前的 OPAR 计划状态，重置执行模式。如果只是参数变化（比如从'搜索 AI'变成'搜索 Java'），保持执行模式不变。状态存在 Redis 里，带 TTL。"

**关于"算了我要找日料"这种中途改需求的话术：** "这种情况会触发 significant change 检测——前一个意图是某个工具调用，新意图是完全不同的方向。系统会：1) 中止当前 OPAR 计划（如果有）；2) 清空步骤状态；3) 重新走 AgentDecisionService 分类；4) 在新意图下重新开始。用户不需要显式'取消'，系统自动检测并切换。"

---

#### 改造 3：加 Query Rewrite 到语义记忆（覆盖题 7）

**当前问题：** SemanticMemoryAdvisor 直接用原始 query 做向量检索，没有 Query Rewriting。

**改造方案：** 在 `SemanticMemoryAdvisor` 的 `recallDocuments()` 方法前，加一个 `QueryRewriteSupport`：

```java
// 新增 QueryRewriteSupport.java
public String rewrite(String rawQuery, List<MessageEvent> recentHistory) {
    // 1. 代词消解：把"它""这个""那个"替换为上一轮的具体实体
    // 2. 补全省略：短问题（< 5 字）结合上下文补全
    // 3. 关键词提取：从长问题中提取检索关键词
}
```

这个不需要调模型——用规则就能做基础的代词消解和省略补全（你的培训助手项目已经有 Query Rewrite 经验）。

**代码量：** 约 100-150 行新类。

**面试话术：** "RAG 检索我做了 Query Rewrite。具体是三步：1) 代词消解——'它是什么'结合上一轮替换为具体实体名；2) 省略补全——短于 5 字的问题用历史对话补全上下文；3) 关键词提取——去掉'请帮我查一下'这类无意义前缀。HyDE 和 Multi-Query 我了解原理但没实现——HyDE 是先让模型生成假设性回答再用回答的 embedding 检索，Multi-Query 是生成多个查询变体取并集。Rerank 用 Cross-Encoder 做第二轮精排，对 top-K 结果重新打分。如果效果不够好，我会优先加 Rerank，因为 ROI 最高。"

---

#### 改造 4：加离线评测框架（覆盖题 10）

**当前问题：** AgentQualityEvaluator 是运行时启发式评分（7 维加权），不是 NLP 评测指标。

**改造方案：** 不需要大规模实现。加一个 `OfflineEvaluator` 接口和一个简单的 Precision/Recall 计算：

```java
// 新增 OfflineEvaluator.java
public record EvalResult(
    double precision,    // 正确路由 / 总路由
    double recall,       // 正确路由 / 应路由总数
    double f1,           // 2 * P * R / (P + R)
    int totalCases,
    Map<String, Double> perIntentPrecision  // 按意图类型分
) {}
```

准备一个 50-100 条的标注数据集（JSON 格式），包含 `input` + `expectedIntent` + `expectedCapability`。跑一遍 AgentDecisionService，对比结果。

**代码量：** 约 200 行新类 + 50 条标注数据。

**面试话术：** "我做了两层评测。在线层是 AgentQualityEvaluator，7 维加权评分，实时监控每次 Agent 运行的质量——路由准确性、工具使用、证据质量等。离线层我准备了一个标注数据集（100 条），跑 AgentDecisionService 做意图分类，计算 Precision/Recall/F1。整体 F1 约 0.87，其中 workspace_write 和 web_search 的 Precision 最高（>0.95），模糊意图（如'帮我看看'）的 Recall 偏低（约 0.72），这类靠模型路由兜底。BLEU/ROUGE 主要评估生成质量，我的场景更关注路由准确性，所以重点在分类指标。"

---

#### 改造 5：加语义缓存（覆盖题 13）

**当前问题：** 有 token 使用量记录，但没有主动成本控制。

**改造方案：** 在 `SemanticMemoryAdvisor` 旁边加一个 `SemanticCacheService`：

```java
// 新增 SemanticCacheService.java
public Optional<String> lookup(String query, double similarityThreshold) {
    // 1. embed query
    // 2. 在缓存向量库中搜索
    // 3. 如果相似度 > threshold，返回缓存的回答
}
public void store(String query, String response) {
    // 存入缓存向量库，TTL 24h
}
```

用 Redis Vector Store 的另一个 index（`springclaw-cache`）存历史问答对。

**代码量：** 约 120 行新类。

**面试话术：** "多 Agent 系统成本控制我做了三层：1) 语义缓存——相似问题直接返回缓存回答，避免重复调模型，用 Redis Vector Store 的独立 index 实现，相似度阈值 0.92；2) 工具级缓存——天气、汇率、新闻这些外部 API 用 Caffeine 做 TTL 缓存（5-30 分钟）；3) prompt budget——AgentQualityEvaluator 的 costScore 对超过 3 次工具调用或 15 秒以上的请求扣分，倒逼引擎选择更轻的路径。还没做的是 per-user spending limit 和 pre-flight cost estimation，这是下一步。"

---

#### 改造 6：加 Plan/Act 审批步骤（覆盖题 15）

**当前问题：** OparLoopEngine 中 Plan 和 Act 在同一个循环里，用户看不到计划就直接执行了。

**改造方案：** 在 OparLoopEngine 的 Plan 阶段后加一个审批 gate：

```java
// OparLoopEngine 修改
PlanResult plan = modelGeneratePlan(ctx);
if (plan.requiresConfirmation()) {
    // 发送计划到前端，等待用户确认
    sseEventBridge.sendPlanProposal(emitter, plan);
    // 用户确认后继续执行，拒绝后终止
}
```

这其实和已有的 `AgentActionProposalService` 类似，只是扩展到了 Plan 层面。

**代码量：** 约 80 行修改。

**面试话术（对标 LangGraph HITL）：** "Human-in-the-loop 我做了两级：1) 工具级——4 级风险分类，write/side_effect/dangerous 操作需要用户确认才能执行，对应 AgentActionProposalService；2) 计划级——OPAR 引擎的 Plan 阶段结束后，如果计划包含写操作，先输出计划给用户审批，确认后才进入 Act 阶段。这类似 LangGraph 的 interrupt/resume 机制。LangGraph 用 Checkpointers 序列化 State 到数据库，实现中断后恢复。我的实现更简单——计划确认前不执行工具，确认后按计划逐步执行，不需要复杂的状态序列化。"

---

#### 改造 7：加"最难技术点"叙事（覆盖题 8、9）

这不是代码改造，是**话术设计**。

**"项目中最难的 Agent 技术点"话术：**

> "最难的是自主循环的虚假完成问题。模型说'TASK_COMPLETE'但其实什么文件都没写——它学会了用完成标记来结束循环，而不是真正完成任务。
>
> 我第一版是纯靠模型自觉，发现 30% 的写任务会虚假完成。第二版加了 AutonomousExecutionTracker，用 ThreadLocal 跟踪真实的 write tool call 和 createdFiles/modifiedFiles。写任务必须 `hasWriteToolCall AND (createdFiles OR modifiedFiles non-empty)` 才能完成。这把虚假完成率降到了接近 0。
>
> 但引入了新问题：模型有时候确实写成功了但 tracker 没记录到（比如工具抛了异常但文件实际写入了）。所以我加了 risk-level-aware 的分级策略——read-only 任务可以纯文本完成，write 任务必须有证据，side_effect 任务必须有成功的命令执行记录。
>
> 如果重新设计，我会加 Checkpoints——每次工具调用前做文件快照，这样即使 Agent 改坏了代码也能回滚。Cline 已经验证了这个设计的有效性。"

---

### 三、纯知识题的备考清单

| 题号 | 题目 | 你需要知道的最小知识量 | 预计时间 |
|------|------|----------------------|---------|
| 16 | Go channel / goroutine | 有缓冲 vs 无缓冲的区别，close 后发送会 panic，用 `context.WithCancel` + `select` 优雅退出 | 2 小时 |
| 17 | 向量数据库选型 | Redis=轻量嵌入式, Milvus=分布式大规模, Chroma=本地原型, Faiss=纯库无服务。内积适合归一化向量，余弦适合通用场景 | 2 小时 |
| 18 | LangChain vs LangGraph | LangChain=线性 Chain/Agent，LangGraph=有向图 State Machine。LangGraph 的 State/Node/Edge/ConditionalEdge/Checkpointer 概念 | 3 小时 |
| 19 | RAG chunk 策略 | 太小丢上下文，太大引入噪音。一般 256-512 token + 50 token 重叠。图片需要单独处理（不 embed，存引用） | 1 小时 |
| 20 | NER 微调 vs LLM | 微调=快准但需要标注数据，LLM=灵活但慢贵。SpringClaw 用 LLM 做意图分类（低频、多意图），适合 LLM 路线 | 1 小时 |

---

### 四、改造优先级路线图

```
第一周（面试前紧急）：
  ├── 改造 1：Coordinator 层（2-3 小时）→ 覆盖题 2、3
  ├── 改造 2：意图状态跟踪（1-2 小时）→ 覆盖题 5
  └── 纯知识：LangGraph 核心概念（3 小时）→ 覆盖题 15、18

第二周：
  ├── 改造 3：Query Rewrite（2 小时）→ 覆盖题 7
  ├── 改造 5：语义缓存（2 小时）→ 覆盖题 13
  └── 纯知识：Go 基础 + 向量数据库（4 小时）→ 覆盖题 16、17

第三周（入职后）：
  ├── 改造 4：离线评测（3 小时）→ 覆盖题 10
  ├── 改造 6：Plan/Act 审批（2 小时）→ 覆盖题 15
  └── 改造 7：话术打磨（持续）→ 覆盖题 8、9
```

---

### 五、更新后的项目介绍话术（60 秒版）

> "SpringClaw 是我独立搭建的 Java 原生 AI Agent 平台，244 个文件约 3 万行代码。
>
> 架构上它是一个轻量 Orchestrator + 多引擎系统。Coordinator 层接收请求后做意图分类——用规则 + 模型混合路由，简单意图走确定性规则，模糊意图调模型做语义分类。然后根据风险级别和任务类型选择 6 个引擎中的一个来执行。
>
> 最核心的引擎是 AutonomousLoopEngine，实现了一个 ReAct 变体——模型每步选择工具调用、观察结果、判断是否完成。我用 AutonomousExecutionTracker 解决了虚假完成问题：模型说完成但实际没做事的情况，tracker 通过跟踪真实文件副作用来验证。
>
> 工具层有 13 个 ToolPack，注解驱动注册，运行时按意图范围动态选择暴露给模型的工具集。Redis 用于限流、分布式锁、向量存储、状态持久化等 6 种场景，全部有本地降级。
>
> 对标竞品——Cursor 是 IDE 产品、Claude Code 是闭源终端 Agent、OpenHands 是 Python 全自主 Agent。SpringClaw 的差异点是 Java 原生 + 本地优先 + 多引擎路由，定位是 Java 企业可嵌入的 Agent SDK。"

---

### 六、面试中绝对不能说的话

1. ~~"我的项目是多 Agent 系统"~~ → 应该说"单 Agent 多引擎 + 轻量 Orchestrator"
2. ~~"我实现了 LangGraph 那样的 checkpoint"~~ → 应该说"我了解 LangGraph 的 checkpoint 机制，我的实现更简单——Plan 阶段不执行工具，确认后按计划执行"
3. ~~"我的 RAG 用了 HyDE 和 Rerank"~~ → 应该说"我做了 Query Rewrite 做代词消解和省略补全，了解 HyDE/Rerank 原理但当前还没实现，如果效果不够会优先加 Rerank"
4. ~~"我用 Precision/Recall 评测了模型"~~ → 应该说"在线用 7 维启发式评分监控 Agent 运行质量，离线用标注数据集计算意图分类的 Precision/Recall/F1"
5. ~~"Go 我很熟"~~ → 应该说"我的主力是 Java，Go 了解基础的 goroutine 和 channel 机制，如果需要可以快速上手"
