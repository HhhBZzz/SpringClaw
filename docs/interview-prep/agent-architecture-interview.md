# Agent 架构面试知识体系

> 以《从零开始构建智能体》(datawhalechina/hello-agents) 教程为框架，
> 用你的 springclaw 代码逐章对照讲解。每章先说"教程讲了什么"，
> 再说"你的项目怎么实现的"，面试官问到哪个点你都有代码证据。

---

# 第一部分：智能体与语言模型基础（教程第1-3章）

## 第1章 初识智能体 —— 教程说了什么

教程定义：**Agent = LLM + 决策循环 + 工具调用 + 记忆。** 不是"让 AI 聊天"，而是让 AI 像一个员工，观察→思考→行动→反思，直到完成任务。

教程的核心观点：2025 年开启了"Agent 元年"，技术焦点从训练更大模型转向构建更聪明的 Agent 应用。

### 你的 springclaw 怎么实现的

你的项目就是教程定义的完整落地。一句话概括：

> springclaw 是一个企业级 Java Agent 后端，实现了 5 种 Agent Engine、混合路由决策、AOP 工具治理、双轨记忆、多模型 failover、MetaGuard 输出质检。

**代码证据：** `AgentEngine.java:19-44` 定义了统一接口——`name()`, `priority()`, `supports()`, `execute()`。所有 Engine 都实现它。

---

## 第2章 智能体发展史 —— 教程说了什么

教程梳理了从规则系统 → 机器学习 → 深度学习 → LLM → Agent 的演进。核心论点是：**LLM 的出现让 Agent 从"玩具"变成"可用"，因为 LLM 解决了自然语言理解和推理的瓶颈。**

### 你的 springclaw 怎么实现的

你的项目站在这个演进的最前端——不是"用 LLM"，而是"治理 LLM"。传统 Agent 框架只关心"怎么调模型"，你关心的更多是"怎么安全地调、怎么审计、怎么降级"。

**面试可以这么说：**
> "我们项目站在 Agent 发展史的最新阶段——不是简单的 LLM 调用，而是把 LLM 当成不可靠的外部服务来治理。有 failover、有降级、有审计、有输出质检。这是 Agent 从 demo 到生产级的关键一步。"

---

## 第3章 大语言模型基础 —— 教程说了什么

教程讲了 Transformer 架构、Token、上下文窗口、温度、流式输出等基础概念。核心知识点：

- **无状态**：每次 API 调用是独立的，模型不记住上次对话
- **上下文窗口有限**：token 越多，模型对长程信息的回忆能力越差（"上下文腐蚀"）
- **流式输出**：逐 token 返回，降低用户等待感

### 你的 springclaw 怎么实现的

你在这些基础上做了工程化处理：

**① 解决无状态 → 双轨记忆**
```
短期：MySQL 事件流，保留当前会话的消息历史
长期：Redis Vector Store，跨会话语义检索
```
**代码证据：** `AssembledContext.java:62-84` 把三个来源拼成完整 prompt。

**② 解决上下文窗口有限 → 上下文组装 + token 管理**
`ContextAssembler` 负责从"候选信息宇宙"中甄选哪些内容进入有限的上下文窗口。

**③ 流式输出 → 3 种 SSE Engine**
`BasicStreamEngine`、`ModelLedStreamEngine`、`AutonomousLoopEngine` 都实现了 `AgentEngine.StreamableAgentEngine` 接口，自行管理 SSE 生命周期。

**代码证据：** `AgentEngine.java:64-84` —— `StreamableAgentEngine` 子接口，定义了 `stream()` 方法。

---

# 第二部分：构建你的大语言模型智能体（教程第4-7章）

## 第4章 智能体经典范式构建 —— 这是最核心的一章

教程从零实现了三种经典范式：**ReAct**、**Plan-and-Solve**、**Reflection**。这是面试必问的核心。

### 4.1 ReAct（Reasoning + Acting）—— "边想边做"

**教程怎么讲的：**
ReAct 把"思考"和"行动"紧密结合，形成一个 **Thought → Action → Observation** 的循环：

```
Thought: "我需要搜索华为最新手机"
Action:  Search['华为最新手机']
Observation: "华为 Mate 80 Pro..."
Thought: "搜索结果显示是 Mate 80 Pro，我可以总结了"
Action:  Finish['华为最新手机是 Mate 80 Pro...']
```

核心机制：
- 提示词模板强制 LLM 输出结构化格式（Thought/Action）
- 代码解析 Action 字段，调用对应工具
- 把 Observation 追加到历史，进入下一轮
- `max_steps` 防止死循环

**教程的 ReActAgent 代码结构：**
```python
class ReActAgent:
    def run(self, question):
        while current_step < self.max_steps:
            # 1. 格式化提示词（注入工具列表 + 历史）
            # 2. 调用 LLM 获取 Thought + Action
            # 3. 解析 Action → 调用工具 → 得到 Observation
            # 4. 如果是 Finish → 返回答案
```

### 你的 springclaw 怎么实现 ReAct

你的 `OparLoopEngine` 就是 ReAct 的完整工程化版本：

```
Observe: 组装上下文（AssembledContext）
  │
Plan:   模型输出 PlanResult（CONTINUE/READY）
  │
Act:    按 Plan 选工具（ToolOrchestrator），模型调用工具
  │
Reflect: 判断信息是否足够，不足则回到 Plan（最多 3 步）
```

**代码证据：** `OparLoopEngine.java:197-233` —— 主循环：`for stepNo ≤ maxAgentSteps`，Plan → Act → 检查 READY。

**你比教程多做的（面试亮点）：**

1. **本地短路机制** —— 4 种情况不调 LLM 直接返回：
   - decision-bound（AgentDecision 已约束能力）
   - control-plane（确定性查询："现在几点"）
   - context-aware（确认词承接）
   - priority-structured（高置信度本地技能）

   **代码证据：** `OparLoopEngine.java:134-177`

2. **工具选择不是硬编码** —— 教程里工具是固定的 Search，你的 `ToolOrchestrator.selectTools()` 根据 channel/userId/question/plan/decision 动态选工具。

3. **有 failover** —— 教程里模型挂了直接报错，你的 `ModelCallExecutor` 封装了重试 + 多 provider failover。

---

### 4.2 Plan-and-Solve —— "先谋后动"

**教程怎么讲的：**
两阶段分离：先让模型输出完整计划（Python 列表），再严格按计划逐步执行。

```
规划阶段: "水果店问题" → ["计算周二销量", "计算周三销量", "计算总销量"]
执行阶段: 按列表顺序逐一执行，每步结果作为下一步输入
```

核心机制：
- `Planner` 类：调 LLM 生成结构化计划（强制输出 Python 列表格式）
- `Solver` 类：遍历计划列表，逐条执行
- 适用场景：结构化任务（数学题、报告撰写、代码生成）

### 你的 springclaw 怎么实现 Plan-and-Solve

你的 `AgentRuntimeEngine` 就是 Plan-and-Solve + Reflection 的组合：

```
INIT → PLAN_CAPABILITY → EXECUTE_CAPABILITY → REFLECT_EVIDENCE → EVALUATE_RUN
  │                                                            │
  └── evidence insufficient & retryable ──→ 换关键词重试（最多 3 轮）
```

**代码证据：** `AgentRuntimeEngine.java:147-201`

**你比教程多做的：**
- 教程的 Plan 是硬解析 Python 列表字符串（容易崩），你的 Plan 用 `CapabilityExecutorRegistry.plan()` 结构化生成
- 教程的 Solver 是线性执行，你的加了 Reflection 循环——执行完还能反思重试

---

### 4.3 Reflection —— "做完反思再优化"

**教程怎么讲的：**
三步循环：**执行 → 反思 → 优化**

```
执行: 模型写初版代码（试除法找素数）
反思: 评审员模型批判："时间复杂度过高，应该用埃氏筛法"
优化: 根据反馈生成优化版代码
```

核心机制：
- `Memory` 类：存储每轮"执行-反思"轨迹
- 三个不同的提示词：执行提示词、反思提示词、优化提示词
- 反思模型扮演"评审员"角色，从事实/逻辑/效率/遗漏四个维度评估
- `max_iterations` 控制最大迭代次数

### 你的 springclaw 怎么实现 Reflection

你的 Reflection 比教程复杂得多——教程用三个独立提示词，你用**混合反思器**：

```
reflectEvidence():
  ① deterministicReflection() 先行 —— 纯规则判断
     - 检查是否有成功结果
     - 检查是否搜索引擎噪声（反爬/验证码）
     - 检查必选能力是否缺失
  ② 如果确定性判断不足以决定 → 调模型反思
  ③ 模型失败 → 回退确定性结果
```

**代码证据：** `AgentRuntimeEngine.java:224-271` —— `reflectEvidence()` 混合反思器。
`AgentRuntimeEngine.java:370-398` —— `deterministicReflection()` 确定性规则。

**你的 Reflection 独有的检测能力（教程完全没有）：**
- **搜索引擎噪声检测**：识别反爬虫/验证码拦截的脏数据（`looksLikeSearchEngineNoise`）
- **必选能力缺失检测**：`missingSelectedCapability()` —— 检查每个必选工具是否真的返回了有效结果
- **质量评分**：`AgentQualityEvaluator` 对每次 Run 打分（route/tool/evidence/reflection/answer/cost/risk 7 维度）

**代码证据：** `AgentRuntimeEngine.java:591-602` —— 噪声检测：payload 包含 "All Images News" + 国家列表 → 判定为脏数据，触发 retry。

---

### 4.4 三种范式对比总结（教程第4章小结 + 你的实现）

| 范式 | 教程实现 | 你的 springclaw |
|---|---|---|
| ReAct | `ReActAgent` 类，while 循环，max_steps | `OparLoopEngine`：Plan→Act→Reflect，+ 本地短路 + failover |
| Plan-Solve | `Planner` + `Solver` 分离 | `AgentRuntimeEngine`：plan → execute → reflect，+ 自动重试 |
| Reflection | `ReflectionAgent`：执行→反思→优化 | `AgentRuntimeEngine` 内置：确定性反思 + 模型反思双路径 |

**面试可以这么说：**
> "教程第4章的三种范式，我们的项目全部实现了，而且是工程化的。ReAct 对应 OparLoopEngine，加了本地短路机制——4 种场景不调 LLM 直接返回。Plan-and-Solve 对应 AgentRuntimeEngine 的规划阶段。Reflection 我们做得更深——不是简单的'评审员模型'，而是混合反思器：先跑确定性规则（噪声检测、必选能力缺失检测），规则判断不了才调模型。"

---

## 第5章 基于低代码平台的智能体搭建 —— 与你的关系

教程这章讲 Dify/Coze 等低代码平台的使用。**这章和你没有直接代码对应**，但面试时有个重要观点：

> 低代码平台适合快速验证想法，但生产环境需要完全的控制权——工具治理、安全审计、failover、输出质检，这些低代码平台做不到。

**面试可以这么说：**
> "我们评估过低代码平台，但它们缺少我们需要的治理能力——没有工具调用 AOP 审计、没有 proposal 确认流程、没有 MetaGuard。所以我们选择自建。"

---

## 第6章 框架开发实践 —— 教程说了什么

教程对比了 4 个主流框架：AutoGen（对话驱动）、AgentScope（工程化平台）、CAMEL（角色扮演）、LangGraph（图编排）。核心观点：

1. **框架的价值**：封装主循环、状态管理、工具调用、日志记录，让开发者专注业务逻辑
2. **框架的代价**：过度抽象、API 不稳定、黑盒化、依赖复杂

### 你的 springclaw 怎么对应的

你**没有用任何第三方 Agent 框架**——你是自己实现框架的那个人。

教程第6章讲的是"用框架"，你的项目是"造框架"。你造的框架比教程讲的更完整：

| 框架能力 | 教程讲的 | 你的 springclaw |
|---|---|---|
| Agent 基类 | AutoGen 的 AssistantAgent | `AgentEngine` 接口 + 5 个实现 |
| 工具系统 | 框架内置 | 13 个 ToolPack + AOP 治理 |
| 记忆系统 | 框架内置 | 自建双轨（MySQL + Redis） |
| 状态管理 | 框架处理 | 自建 ChatContext + AssembledContext |
| 可观测性 | 框架回调 | 自建 AgentRunTraceService + ToolAuditService |
| 多智能体协作 | AutoGen GroupChat | 暂未实现（但你架构预留了） |

---

## 第7章 构建你的 Agent 框架 —— 这章直接对应你的架构

教程从零构建 HelloAgents 框架，目录结构：

```
hello_agents/
├── core/          ← Agent 基类、LLM 接口、消息系统、配置、异常
├── agents/        ← SimpleAgent、ReActAgent、ReflectionAgent、PlanSolveAgent
├── tools/         ← 工具基类、注册表、内置工具
├── context/       ← 上下文构建器
└── skills/        ← 技能加载器
```

**设计理念（教程原文）：**
> "除了核心的 Agent 类，一切皆为 Tools。Memory、RAG、RL、MCP 等模块，都被统一抽象为一种'工具'。"

### 你的 springclaw 的对应结构

把你的项目按教程的框架重新"翻译"一下：

```
springclaw/
├── service/agent/          ← 教程的 core/ + agents/
│   ├── AgentEngine.java            ← 教程的 core/agent.py（Agent 基类）
│   ├── AgentRuntimeEngine.java     ← 教程的 agents/plan_solve_agent.py + reflection_agent.py
│   ├── AgentDecisionService.java   ← 教程没有（混合路由是你的创新）
│   └── CapabilityExecutor.java     ← 教程的 tools/base.py
│
├── service/chat/impl/
│   ├── OparLoopEngine.java         ← 教程的 agents/react_agent.py
│   ├── SimplifiedOparEngine.java   ← 教程的 agents/simple_agent.py
│   └── ModelCallExecutor.java      ← 教程的 core/llm.py
│
├── tool/
│   ├── pack/ (13 个 ToolPack)      ← 教程的 tools/builtin/
│   └── runtime/
│       ├── ToolRuntimeAspect.java  ← 教程没有（AOP 治理）
│       ├── ToolOrchestrator.java   ← 教程的 tools/registry.py
│       └── CapabilityRegistry.java ← 教程的 tools/registry.py
│
├── service/context/               ← 教程的 context/
│   └── AssembledContext.java
│
└── service/memory/                ← 教程第8章
    ├── VectorMemoryService.java   ← 教程的 QdrantVectorStore
    └── EventPersistenceService    ← 教程的 SQLiteDocumentStore
```

**关键差异——你和教程最大的不同：**

教程第7章的设计理念是"一切皆为 Tools"，把 Memory/RAG 都抽象成工具。**你的设计恰恰相反——你没有把一切塞进工具，而是把工具治理独立成一层（AOP），把记忆独立成服务层（双轨），把决策独立成混合路由。** 这是因为教程的目标是"教学友好"，你的目标是"生产级安全"。

**面试可以这么说：**
> "教程第7章把一切抽象为 Tool，这是教学友好的设计。但我们在生产环境发现，工具、记忆、决策需要不同的治理策略——工具的治理是 AOP 审计 + proposal 确认，记忆的治理是双轨存储 + 语义去重，决策的治理是混合路由。如果全塞进 Tool 抽象，每种治理策略会互相污染。所以我们选择了更'重'但更安全的分离式架构。"

---

# 第三部分：高级知识扩展（教程第8-12章）

## 第8章 记忆与检索 —— 教程说了什么

教程从认知科学出发，构建了四层记忆架构：

```
人类记忆                     →    HelloAgents 记忆系统
─────────────────────────────────────────────────
感觉记忆（0.5-3秒）           →    （无直接对应）
工作记忆（15-30秒，7±2项）     →    WorkingMemory（TTL 管理）
长期记忆                      →
  ├─ 情景记忆（个人经历）      →    EpisodicMemory（时间序列）
  └─ 语义记忆（一般知识）      →    SemanticMemory（知识图谱）
                               →    PerceptualMemory（多模态）
```

技术实现分四层：
```
基础设施层 → MemoryManager、MemoryItem、MemoryConfig、BaseMemory
记忆类型层 → Working/Episodic/Semantic/Perceptual
存储后端层 → QdrantVectorStore、Neo4jGraphStore、SQLiteDocumentStore
嵌入服务层 → DashScopeEmbedding、LocalTransformerEmbedding
```

### 你的 springclaw 怎么实现记忆

你的架构比教程更"实战"——没有四层抽象，而是直接双轨：

```
springclaw 记忆系统
│
├── 短期记忆（MySQL 事件流）
│   └─ MessageEventService / EventPersistenceService
│      └─ 存 message_event 表，按会话维度保留最近 N 条
│      └─ 对应教程的 WorkingMemory + EpisodicMemory
│
├── 长期记忆（Redis Vector Store）
│   └─ VectorMemoryService + EmbeddingService
│      └─ 语义向量检索，跨会话召回
│      └─ 对应教程的 SemanticMemory + QdrantVectorStore
│
└── 项目记忆（Memory Bank）
    └─ CLAUDE.md / 项目文档，启动时加载
       └─ 对应教程的 RAG 知识库
```

**代码证据：** `AssembledContext.java:62-84` —— `renderObservePrompt()` 把三个来源拼成：
```
# 当前问题
{question}

# 项目记忆（Memory Bank）
{projectMemory}

# 短期会话上下文（事件流）
{eventContext}

# 长期语义记忆（同会话优先）
{semanticContext}
```

**你比教程多做的：**
- 教程的记忆是"框架层抽象"，你的记忆是"真实持久化"（MySQL + Redis 双写）
- 教程用 Qdrant 做向量存储（需要额外部署），你用 Redis（已有基础设施复用）
- 教程的记忆需要手动调用 `memory_tool`，你的记忆在 ContextAssembler 中自动注入

**面试可以这么说：**
> "教程第8章的四层记忆架构给了很好的理论框架。我们在实现时做了简化——不需要四层抽象，而是直接双轨：MySQL 事件流覆盖工作记忆+情景记忆，Redis Vector Store 覆盖语义记忆。关键是自动注入——ContextAssembler 在每次请求时自动从三个来源拼装上下文，Agent 不需要手动调用'记忆工具'。"

---

## 第9章 上下文工程 —— 教程说了什么

教程定义：**上下文工程 = 在每次模型调用前，以可复用、可度量、可演进的方式，拼装并优化输入上下文。**

核心概念：
- **上下文腐蚀（Context Rot）**：上下文窗口越长，模型准确回忆信息的能力越差
- **注意力预算**：每新增一个 token 都消耗模型的"注意力"，token 越多信噪比越低
- **GSSC 流水线**：Gather → Select → Structure → Compress

### 你的 springclaw 怎么实现上下文工程

你的 `AssembledContext` 就是 GSSC 流水线的落地：

```
Gather（收集）
  ├─ 短期事件流（MySQL message_event）
  ├─ 语义记忆（Redis Vector Store）
  └─ 项目记忆（Memory Bank 文件）

Select（选择）
  └─ memoryLearningFilteredCount —— 记录过滤掉多少条记忆
     └─ 对应教程的"从候选信息宇宙中甄别哪些进入上下文"

Structure（结构化）
  └─ renderObservePrompt() —— 四个 section 用 Markdown 标题分隔
     └─ 对应教程的"用 XML/Markdown 分隔提示分区"

Compress（压缩）
  └─ TextUtils.truncate() —— 每条记忆截断到合理长度
     └─ 对应教程的"用最少 tokens 获得最大信号"
```

**代码证据：** `AssembledContext.java:6-16` —— record 里包含 `memoryLearningActiveCount` 和 `memoryLearningFilteredCount`，这是上下文工程的度量指标。

**你比教程多做的：**
- 教程的 ContextBuilder 是框架组件，你的 ContextAssembler 是每次请求自动运行的
- 你记录了过滤统计（activeCount / filteredCount），可观测上下文质量
- 你的 OparLoopEngine 在每次循环中通过 `withQuestion()` 更新上下文（换关键词重试时自动刷新上下文）

**面试可以这么说：**
> "教程第9章的上下文工程理念，我们在 ContextAssembler 中完整落地了。Gather 从三个来源收集信息，Select 通过语义相关性过滤，Structure 用 Markdown 标题分区拼接，Compress 截断过长内容。我们还记录了每次组装的过滤统计（多少条记忆被激活、多少条被过滤），用来监控上下文质量。"

---

## 第10章 智能体通信协议 —— 教程说了什么

教程讲了 MCP（Model Context Protocol）、A2A（Agent-to-Agent）等协议。核心观点：**标准化协议让不同 Agent 之间可以互相调用和协作。**

### 你的 springclaw 怎么实现的

你目前是**单体 Agent**，没有多 Agent 协作。但你做了"多渠道适配"——Agent 通过统一的 `ChannelAdapter` 接口对接飞书/Telegram/微信：

```
ChannelAdapter 接口
  ├─ FeishuChannelAdapter  —— 飞书消息收发
  ├─ TelegramChannelAdapter —— Telegram 消息收发
  └─ WechatChannelAdapter   —— 微信消息收发
```

这和教程的 MCP 理念一致：**统一接口，屏蔽底层差异。**

**面试可以这么说：**
> "我们目前是单体 Agent 架构，但渠道层已经用了策略模式——ChannelAdapter 接口统一对接飞书/Telegram/微信。这和教程第10章的 MCP/A2A 理念一致：统一协议、屏蔽差异。多 Agent 协作是我们架构预留的方向。"

---

## 第11章 Agentic-RL（强化学习） —— 与你的关系

教程讲用强化学习训练 Agent。**这章和你的项目没有直接代码对应**——你的 Agent 不涉及 RL 训练。

**面试如果问到：**
> "我们目前是 prompt-driven 的 Agent，不涉及 RL 训练。但教程第11章的知识帮助我们理解了为什么 Reflection 循环有效——本质上 Reflection 就是一种轻量级的在线 RL：执行→获得反馈→优化，只是反馈来自另一个 LLM 而非环境 reward。"

---

## 第12章 智能体性能评估 —— 教程说了什么

教程讲了三类评估：
1. **工具调用评估**：Agent 是否选对工具、参数是否正确
2. **端到端评估**：最终答案是否正确
3. **评估基准**：BFCL、τ-bench、SWE-bench 等

### 你的 springclaw 怎么实现评估

你自建了一套**在线质量评估系统**：

```
每次 AgentRun 完成后：
  │
  ▼
AgentQualityEvaluator.evaluate()
  │
  输出 AgentQualityScore {
      overallScore,     // 综合评分 0-100
      level,            // 等级
      routeScore,       // 路由准确度
      toolScore,        // 工具执行质量
      evidenceScore,    // 证据充分度
      reflectionScore,  // 反思质量
      answerScore,      // 回答质量
      costScore,        // 成本（耗时）
      riskScore,        // 风险评分
      reason            // 一句话原因
  }
```

**代码证据：** `AgentRuntimeEngine.java:401-439` —— `toVerification()` 方法调用 `qualityEvaluator.evaluate()` 给每次 Run 打分。7 维度评分，记录在 `AgentRunTraceEvent` 中。

**你比教程多做的：**
- 教程的评估是离线 benchmark（跑测试集），你的评估是**在线实时**的——每次 Run 都打分
- 你的评估结果写进 trace，可以做长期趋势分析
- 你的评估直接驱动 Reflection 循环——分数不够就重试

**面试可以这么说：**
> "教程第12章讲的是离线评估（跑 benchmark），我们做的是在线实时评估。每次 AgentRun 完成后，AgentQualityEvaluator 从 7 个维度打分：路由、工具、证据、反思、回答、成本、风险。评分结果直接驱动 Reflection 循环——证据不足就重试，风险太高就降级。这是把评估从'事后检查'变成了'运行时决策'。"

---

# 第四部分：综合案例进阶（教程第13-15章）

教程这三章是实战项目（旅行助手、深度研究、赛博小镇），**和你没有直接代码对应**，但面试时有个重要观点：

> 教程的实战案例验证了一个原则：**复杂 Agent 应用 = 基础范式（ReAct/Plan-Solve/Reflection） + 领域工具 + 记忆 + 评估。** 你的 springclaw 已经具备所有这些基础能力，接入任何领域工具就能覆盖教程里的所有场景。

**面试可以这么说：**
> "教程第13-15章的三个实战案例，我们的基础架构都能支撑。旅行助手 = SimplifiedOparEngine + 天气/搜索 ToolPack。深度研究 = AgentRuntimeEngine + Exa搜索 + Jina Reader + Reflection 循环。赛博小镇 = 多 Agent 协作（架构预留）。关键是我们不需要为每个场景重新搭建基础设施——治理、记忆、路由、评估都是现成的。"

---

# 第五部分：毕业设计及未来展望（教程第16章）

教程鼓励读者综合所学构建毕业作品。**你的 springclaw 就是一个毕业作品级别的大型项目**——它综合了教程全部 16 章的知识点。

---

# 附加：面试常见追问 —— 怎么回答

### Q1："你们的 Agent 是怎么做决策的？"

> 混合路由。关键词规则先行（AgentDecisionRouter），快且可解释。低置信度（只有 1 个关键词、多工具集竞争）才调小模型做意图分类。模型失败回退规则结果。这个设计的理念是：确定性路径优先，不确定性才付模型成本。

### Q2："你们实现了教程里的哪些 Agent 范式？"

> 全部三种。ReAct 对应 OparLoopEngine（Plan→Act→Reflect 循环，最多 3 步），Plan-and-Solve 对应 AgentRuntimeEngine 的规划阶段，Reflection 对应 AgentRuntimeEngine 的混合反思器（确定性规则 + 模型反思双路径）。另外还有 SimplifiedOparEngine 对应 SimpleAgent（直接调模型）。

### Q3："你们的记忆怎么做的？对应教程哪一章？"

> 对应教程第8章。双轨——短期 MySQL 事件流（工作记忆+情景记忆），长期 Redis Vector Store（语义记忆），外加项目记忆（Memory Bank）。ContextAssembler 自动注入，Agent 不需要手动调"记忆工具"。

### Q4："工具调用安全吗？怎么防止 Agent 乱写文件？"

> 这是教程没有深入的部分——工具治理。5 层：1) Token + Role 鉴权；2) 意图风险评估（read/write/dangerous）；3) AOP 切面拦截所有 @Tool 调用做权限+限流+审计；4) 高风险工具必须先创建 proposal、用户确认、DB 校验 5 个不变量；5) WorkspaceGitGuard 防止写目标目录外的文件。

### Q5："模型挂了怎么办？"

> 三层兜底。1) AiProviderService 多 provider 自动 failover；2) ModelCallExecutor 封装重试；3) LocalSkillFallbackService——模型完全不可用时，本地技能执行确定性查询。用户无感。

### Q6："为什么有 5 个 Engine？不是越多越复杂吗？"

> 这是教程第7章框架设计理念的实践——开闭原则。每个 Engine 只覆盖一种场景，接口统一（AgentEngine），EngineSelector 按优先级选。加新场景不改旧代码。SimplifiedOparEngine 永远兜底（priority=10）。

### Q7："你和 LangChain/AutoGen 有什么不同？"

> 我们是企业级应用，它们是框架。最大的差异是治理——框架不关心工具调用审计、proposal 确认流程、MetaGuard 输出质检、双轨记忆。我们的复杂度不在"怎么调 LLM"，而在"怎么安全地调、怎么审计、怎么降级"。

### Q8："你项目中最大的技术难点是什么？"

> 两个。1) OPAR 本地短路——怎么在不调模型的情况下判断"可以本地确定性回答"，4 种短路条件各有边界，错了会漏掉真需要模型的请求。2) 工具治理的 proposal 流程——AOP 切面里 DB 二次校验 5 个不变量 + args hash 防篡改，还不能让 AOP 太重影响性能。

---

# 速查表：教程 16 章 → 你的 springclaw 映射

| 教程章节 | 教程内容 | 你的 springclaw 对应 |
|---|---|---|
| 第1章 初识智能体 | Agent 定义 | `AgentEngine` 接口 |
| 第2章 发展史 | 演进脉络 | 你的项目站在最新阶段（治理型 Agent） |
| 第3章 LLM 基础 | 无状态/上下文窗口/流式 | 双轨记忆 + ContextAssembler + SSE Engine |
| 第4章 经典范式 | ReAct/Plan-Solve/Reflection | OparLoopEngine + AgentRuntimeEngine |
| 第5章 低代码平台 | Dify/Coze | 无直接对应（你选择自建） |
| 第6章 框架实践 | AutoGen/LangGraph 等 | 你没用框架，你造框架 |
| 第7章 构建框架 | HelloAgents 框架设计 | 你的整个 service/agent/ + tool/ 结构 |
| 第8章 记忆与检索 | 四层记忆架构 | 双轨记忆（MySQL + Redis Vector） |
| 第9章 上下文工程 | GSSC 流水线 | `AssembledContext` + ContextAssembler |
| 第10章 通信协议 | MCP/A2A | ChannelAdapter 策略模式 |
| 第11章 Agentic-RL | 强化学习 | 无直接对应（Reflection 作为轻量 RL） |
| 第12章 性能评估 | 离线 benchmark | `AgentQualityEvaluator` 在线 7 维评分 |
| 第13-15章 实战 | 旅行助手/深度研究/赛博小镇 | 基础架构可支撑，接入领域工具即可 |
| 第16章 毕业设计 | 综合项目 | 你的 springclaw 就是毕业作品级别 |

---

# 你项目中独有的亮点（面试时重点提，教程里全都没有）

1. **混合路由** — 规则+模型双路径，规则先行降成本，模型兜底提准确率
2. **OPAR 本地短路** — 4 种短路场景，不调模型直接返回
3. **混合反思器** — 确定性规则（噪声检测+必选能力缺失检测）先行，模型反思兜底
4. **AOP 工具治理** — proposal 确认 + DB 5 不变量 + GitGuard 完整闭环
5. **MetaGuard** — 检测模型幻觉拒答，自动拦截重试
6. **双轨记忆** — MySQL 事件流 + Redis Vector Store + Memory Bank 三层
7. **在线质量评估** — 每次 Run 实时 7 维评分，驱动 Reflection 循环
8. **优雅降级** — 模型不可用时，自动 fallback 到本地技能执行
