# SpringClaw Agent 架构方案 v2

> 产品定位：个人本地部署的 AI Agent 助手 | 用户：1 个 Java 开发者 | 部署：自己的机器

---

## 一、从产品角度看，改完到底解决什么

### 1.1 当前的真实痛点（不是架构问题，是开发体验问题）

每次想加一个新能力（比如"支持查股票"），需要在 **6 个 Java 文件** 里粘贴中文关键词：

```
AgentDecisionRouter.java         → 加 "股票"、"股价"
ChatRoutingPolicyService.java    → 加 detectIntent 分支
LocalSkillFallbackService.java   → 加 30 行 if/else 分支
AgentCapabilityExecutionService  → 加 switch case
ToolOrchestrator.java            → 加构造器参数 keyword
ChatServiceImpl.java             → 可能加 shouldUse 条件
```

每次粘贴都**可能遗漏一个文件**，导致某种路径下功能不生效。这不是架构问题，是**维护噩梦**。

### 1.2 改完后

想加"股票"能力，只需要两步：

```
1. 写一个 StockToolPack implements ToolPackDescriptor
2. 在注解里声明: triggerKeywords = {"股票", "股价", "stock"}
```

系统自动：
- 识别"帮我查茅台股价" → 匹配 triggerKeywords → 路由到 web_research → 选中 StockToolPack
- 模型不可用时 → 自动作为 fallback 本地执行
- 前端 Console 自动显示 stock 工具

**零行 Java 代码粘贴**。

### 1.3 其他解决的具体问题

| 问题 | 改前 | 改后 |
|------|------|------|
| 英文输入 "what's the weather" | 全部规则路由失效，走 general 兜底 | triggerKeywords 支持多语言 |
| 想知道"当前走的是哪条链路" | 看日志才知道 | AgentView 的 trace 面板直接显示 Pipeline Stage |
| 本地兜底不生效（关键词没写到那个文件） | 静默失败，空白回答 | 所有 ToolPack 自动注册为 fallback 候选 |
| 加完功能忘了更新前端 skill 面板 | 前端显示过期数据 | RuntimeConsole API 实时从 ToolPack 注册表读取 |

---

## 二、改后的链路到底是什么样

### 2.1 总览

```
ChatController (/api/chat/stream)
  │
  ▼
AgentOrchestrator   ← 唯一入口，200 行以内
  │
  ├─ Step 1: 组装上下文（查数据库 + 向量库 + skill 注册表）
  │   ContextAssembler → AssembledContext
  │
  ├─ Step 2: 意图分类（从 ToolPack 注册表匹配 triggerKeywords）
  │   命中 "项目"+"分析"+"agent" → workspace skill → intent=workspace_analysis
  │   没有 skill 命中 → 轻量模型兜底分类
  │
  ├─ Step 3: 引擎选择（ToolPack 声明 preferredMode → 选择引擎）
  │   preferredMode=opar → OparLoopEngine
  │   preferredMode=simplified → SimplifiedEngine
  │   intent=general → BasicStreamEngine
  │
  └─ Step 4: 执行并返回
      引擎执行能力 → 反思 → 模型总结 → SSE 流式输出
```

### 2.2 一个具体请求走一遍

请求：`"帮我分析 springclaw 项目的 agent 链路"`

```
Step 1: 组装上下文
  ├─ messageEventService.listRecent()  → 最近 8 条消息
  ├─ memoryService.recallBySession()   → 向量语义召回
  └─ skillRegistryService.listVisible() → 当前可用的 skill 列表

Step 2: 意图分类（数据驱动，不再硬编码中文）
  ├─ 遍历所有 ToolPack 的 triggerKeywords
  │   WorkspaceToolPack: keywords=["项目","源码","代码"...] → 命中
  │   WebSearchToolPack: keywords=["搜索","天气"...] → 未命中
  │   SkillToolPack: keywords=["skill","脚本"...] → 未命中
  ├─ 命中 workspace → intent=workspace_analysis
  └─ 对应的 ToolPack 声明了 preferredMode=opar

Step 3: 引擎选择（策略模式，不再 if/else）
  ├─ BasicStreamEngine.supports()    → false (不是 general)
  ├─ AgentRuntimeEngine.supports()   → true (非 general + 非确认 + 非危险)
  ├─ SimplifiedEngine.supports()     → true (兜底)
  └─ 选第一个 supports=true 的 → AgentRuntimeEngine

Step 4: 执行
  AgentRuntimeEngine.run():
    ├─ PLAN:    确定要执行 workspace-search + workspace-review
    ├─ EXECUTE: 执行两个 Capability
    ├─ REFLECT: 模型判断证据是否充足
    └─ SUMMARIZE: 模型整理最终回答 → SSE 输出
```

---

## 三、OPAR 还在吗？

**在，而且行为不变，只是组织方式变了。**

### 改前

```java
// ChatServiceImpl.java 中
if (useSimplifiedMode(context.executionMode()))
    simplifiedOparEngine.run(...);   // ← 硬编码选择
else
    oparLoopEngine.runLoop(...);     // ← 硬编码选择
```

### 改后

```java
// AgentOrchestrator 中
engines.stream()
    .filter(e -> e.supports(ctx))
    .findFirst()
    .orElse(defaultEngine)
    .execute(ctx);
```

`OparLoopEngine` 的内部逻辑（Plan→Act 循环、最多 3 步、本地兜底）**一行不改**。

它只是变成了 `implements AgentEngine`，声明自己的 `supports()` 条件：

```java
@Component
public class OparLoopEngine implements AgentEngine {
    public int priority() { return 2; }  // 比 BasicStream 低，比 Simplified 高

    public boolean supports(AgentContext ctx) {
        return "opar".equals(ctx.preferredMode())    // skill 声明了 opar
            || ctx.decision().executionPath().equals("agent_tools")  // 复杂工具路径
            || ctx.isAutoUpgraded();                  // 自动升级
    }

    // 内部逻辑完全不变
    public ChatExecutionResult runLoop(...) { ... }
}
```

### OPAR 的触发条件

| 条件 | 示例 |
|------|------|
| Skill 的 SKILL.md 声明了 `preferred_mode: opar` | workspace-review skill 声明了 opar |
| 用户选了"深度"模式（responseMode=deep） | 前端 toggle 切到 opar |
| 问题被自动升级（shouldAutoUpgrade = true） | 问题包含多层步骤 |
| 管理员消息前缀 "深度分析：" | 手动覆盖 |

---

## 四、Tool 工具如何调用

### 4.1 统一注册（一次声明，三种调用路径共享）

```java
// 每个 ToolPack 加一个自描述注解（或实现 ToolPackDescriptor 接口）
@ToolPackDescriptor(
    id = "weather",
    toolset = "web",
    triggerKeywords = {"天气", "气温", "weather", "forecast"},
    fallbackCandidate = true,      // 模型挂了时本地直接调
    riskLevel = "read",
    preferredMode = "simplified"   // 不需要 opar 多步规划
)
public class WeatherToolPack {
    // 原来的 @Tool 方法保持不变
    @Tool(description = "查询天气")
    public String queryWeather(String city) { ... }
}
```

### 4.2 三种调用路径共用同一注册表

```
路径 A：模型主动调用（挂载到 ChatClient）
  ToolOrchestrator.selectTools(intent)
    → CapabilityRegistry.findByIntent(intent)
      → 返回匹配的 ToolPack bean 数组
        → 挂载到 ChatClient.prompt().tools(tools)
          → 模型在回答中自行决定调用哪个 tool

路径 B：后端主动执行（AgentRuntimeEngine）
  CapabilityRegistry.execute(decision, context)
    → 找到 supports(decision) 的 Capability
      → 直接调用 toolPack.queryWeather("北京")
        → 结果注入模型总结 prompt

路径 C：本地兜底（模型不可用时）
  FallbackExecutor.tryExecute(question)
    → CapabilityRegistry.findFallback(question)
      → filter(fallbackCandidate=true)
        → filter(triggerKeywords 匹配)
          → 直接调用并返回文本
```

**核心改变**：不再有 `LocalSkillFallbackService` 里 30 条 `if (containsAny(lower, "天气"))`。关键词匹配只存在于 ToolPack 的 `triggerKeywords` 声明中，**写一次，三路径共享**。

### 4.3 前端 Console 实时显示

```
/api/runtime-console/tools
  → CapabilityRegistry.listAll()
    → 返回所有注册的 ToolPack 及其 triggerKeywords、riskLevel、status
      → 前端 Tools 面板实时展示
```

不需要手动同步前端和后端的工具列表。

---

## 五、模式如何切换

### 5.1 前端控制（用户可见）

前端 AgentView 顶栏的 toggle：

```
[simplified] [opar]
     ↑           ↑
     |           └─ responseMode=deep → 所有引擎中 opar 优先级最高
     └─ responseMode=agent → 默认，由引擎自己决定
```

对应的快捷模式按钮（快速/Agent/深度/工具优先）发送不同的 `responseMode`：

```
快速 → responseMode=fast  → BasicStreamEngine
Agent → responseMode=agent → 自动选择
深度 → responseMode=deep  → OparLoopEngine
工具 → responseMode=tool  → SimplifiedEngine + 工具优先
```

### 5.2 后端自动决策（用户无感）

当用户没有手动选择时，路由由**数据驱动**：

```
Skill 的 SKILL.md 声明 preferredMode=opar
  → 引擎选择时 OparLoopEngine.supports() = true
    → 自动走 OPAR

Skill 的 SKILL.md 声明 preferredMode=simplified
  → SimplifiedEngine.supports() = true
    → 自动走 Simplified

intent=general（普通聊天）
  → BasicStreamEngine.supports() = true
    → 最短路径，不挂工具
```

### 5.3 引擎优先级（策略模式）

```
EngineSelector.select(ctx):
  ┌─────────────────────┐
  │ Priority 1: 显式覆盖 │  responseMode=deep → OparLoopEngine
  │                     │  responseMode=fast → BasicStreamEngine
  ├─────────────────────┤
  │ Priority 2: Skill推荐│  SKILL.md preferredMode=opar → OparLoopEngine
  │                     │  SKILL.md preferredMode=simplified → SimplifiedEngine
  ├─────────────────────┤
  │ Priority 3: 自动选择 │  general → BasicStreamEngine
  │                     │  非general + runtime → AgentRuntimeEngine
  │                     │  其他 → SimplifiedEngine
  └─────────────────────┘
```

新增一种模式 = 新增一个 `@Component class XxxEngine implements AgentEngine`。不会碰任何已有代码。

---

## 六、具体代码改造量（务实的）

### Phase 1: 消灭胶水代码（2-3 天，风险最低）

**目标**：新增能力不再需要粘贴关键词到 6 个文件。

| 改动 | 文件 | 内容 |
|------|------|------|
| 新增 | `ToolPackDescriptor.java` | 注解/接口，声明 id、triggerKeywords、riskLevel、fallbackCandidate |
| 改造 | 所有 ToolPack 类 | 加 `@ToolPackDescriptor` 注解，声明自己的触发词 |
| **删除** | `LocalSkillFallbackService.java` 中的 30 条 if/else | 替换为 `fallbackCapabilities.filter(matchesKeywords).findFirst()` |
| **删除** | `AgentDecisionRouter.java` 中的 9 类关键词 | 替换为从 ToolPack 注册表动态读取 |
| **删除** | `ChatRoutingPolicyService.detectIntent()` | 同上 |
| 改造 | `AgentCapabilityExecutionService.java` 中的 switch | 替换为 CapabilityRegistry 遍历 |

**Phase 1 完成后**：新增能力 = 写 ToolPack + 加注解，不改路由代码。OPAR 行为不变。

### Phase 2: 统一引擎接口（1-2 天）

| 改动 | 文件 | 内容 |
|------|------|------|
| 新增 | `AgentEngine.java` | 接口: name(), priority(), supports(ctx), execute(ctx) |
| 改造 | `OparLoopEngine.java` | implements AgentEngine |
| 改造 | `SimplifiedOparEngine.java` | implements AgentEngine |
| 改造 | `AgentRuntimeEngine.java` | implements AgentEngine |
| 新增 | `BasicStreamEngine.java` | 从 ChatServiceImpl 中提取最短路径逻辑 |
| 改造 | `ChatServiceImpl.java` | 删除 shouldUseXxx() 方法，改为 AgentOrchestrator |

**Phase 2 完成后**：新增引擎 = implements AgentEngine + @Component。

### Phase 3: 包结构整理（1 天）

按功能域重组包结构（见之前文档），不改逻辑。

---

## 七、改与不改造的对比

### 场景：想加一个"查快递"能力

**改前（6 文件 × 散落关键词）**：

```
1. ExpressToolPack.java              ← 新写工具类
2. AgentDecisionRouter.java          ← 加 "快递"、"物流"、"tracking"
3. ChatRoutingPolicyService.java     ← 加 detectIntent 分支
4. LocalSkillFallbackService.java    ← 加 if(containsAny("快递")) 分支
5. AgentCapabilityExecutionService   ← 加 switch case + executeExpress()
6. ToolOrchestrator构造器             ← 加 expressTriggerKeywords 参数
7. 可能改 ChatServiceImpl            ← 加 shouldUse 条件
```

**改后（1 文件）**：

```java
@ToolPackDescriptor(
    id = "express",
    toolset = "web",
    triggerKeywords = {"快递", "物流", "tracking", "express"},
    fallbackCandidate = true,
    riskLevel = "read"
)
public class ExpressToolPack {
    @Tool(description = "查询快递物流信息")
    public String queryExpress(String trackingNumber) { ... }
}
```

系统自动完成：路由匹配、兜底执行、前端展示。

---

## 八、总结

这次改造的核心不是"引入新范式"或"重写成微服务"，而是：

1. **消灭粘贴**：能力自描述，不再需要去 6 个文件里贴关键词
2. **统一引擎**：三种引擎用同一个接口，不再 if/else
3. **保留 OPAR**：行为不变，只是变成了策略模式的一种实现
4. **数据驱动路由**：意图分类从 SKILL.md 和 ToolPack 注解读取，不再硬编码中文
5. **个人项目友好**：改造量小（Phase 1 + 2 共 3-5 天），不引入新框架，不改变部署方式
