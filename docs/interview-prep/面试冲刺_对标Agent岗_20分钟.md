# 面试话术 · Agent 开发核心考点视角(harness 视角,完成态)

> 不逐条贴 JD。从"怎么把一个不可控的 LLM 撑成能稳定跑、可审计、可降级的后端"这个工程支撑(harness)视角组织。
> 着重 agent 开发真正高频的考点:**Agent Loop / Function Calling 稳定性 / 记忆与上下文 / 模型治理 / 容错降级 / 可观测**。
> 全程完成态,不讲演进史/技术债。被问缺口时一句话带过"演进方向"。

---

## 〇、口径校准(必读,旧材料的坑已修)

| 项 | 旧材料 | 实际 | 统一口径 |
|---|---|---|---|
| failover 冷却 | "90 秒" | `application.yml:220` 实配 **30s** | **30 秒**。被追问:"代码兜底默认 90、线上配 30" |
| 引擎数量 | 5/6 不一致 | 6 个实现 | **6 个引擎** |
| 项目角色 | "独立从零" | 有协作痕迹 | **"主导设计与核心实现"** |
| F1 分数 | "0.87" | 无真实评测 | 不报具体数 |
| 并发量级 | "大规模并发" | 单实例为主 | **"生产级架构,为分布式预留"** |
| 多模态 | —— | 代码无 ImageModel | 讲"演进方向",别承诺已有 |
| 记忆组装 | 旧材料散讲 | 默认 ContextAssembler 单一来源 | **"ContextAssembler 统一组装"** |

**绝对不要说**:别报具体 F1/准确率分数、别说多 Agent 已实现、别说 LangGraph checkpoint 已实现、别说 HyDE/Rerank 已实现、别说"已支撑大规模并发"、别把项目说成电商、别说"正在重构/正在收敛/发现技术债"(全程完成态)。

---

## 一、20 分钟可口播话术

### 【开场 · 这是个什么 ~1.5 分钟】

> 我做的叫 **SpringClaw**,一个 Java 的 AI Agent 运行时平台。Spring Boot 3.5 + Spring AI 1.1,244 个 Java 文件。
>
> 我想聊的不是"我接了个大模型 API",而是**怎么把一个本质不可控的 LLM,撑成一个能稳定跑、可审计、可降级、可回放的后端服务**——也就是 Agent 的工程支撑体系。我从几个 agent 开发的核心考点展开:loop 怎么转、Function Calling 怎么稳、记忆怎么组织、模型怎么治理、容错和可观测怎么搭。

### 【Agent Loop / 编排引擎 ~4 分钟】(agent 第一考点)

> 做 agent 第一件事是决定 loop 怎么跑。我没用一个 loop 打天下,做了三类引擎:
>
> - **Simplified**:单次调用,模型自己决定要不要调工具。简单问题用,延迟最低。
> - **OPAR**:显式 Plan-Act-Reflect,模型先出结构化计划再逐步执行,每步可调工具。复杂问题用。
> - **Autonomous**:完全自主循环,带副作用追踪。长任务用。
>
> 为什么要分?因为 agent loop 最大的矛盾是**能力和成本的权衡**。OPAR 多轮往返,延迟是单次的 3-7 倍,简单问答用它就是浪费。所以路由是第一步:`ChatRoutingPolicyService` 做关键词评分,命中动作动词、技术名词、多步连接词且长度够长,分数到阈值自动升级 OPAR。这是按复杂度按需付费。
>
> 然后 loop 有两个经典坑,我都堵了:
>
> **第一,无限循环**。步数硬 clamp——OPAR 最多 6 步、Autonomous 最多 15 步,配置写错也不会变死循环。
>
> **第二,假完成**。这是 agent 最隐蔽的 bug——模型说"我做完了",但其实啥也没干。我做了 `AutonomousExecutionTracker`:模型说 TASK_COMPLETE 时,必须追踪到真实的文件写入或命令执行副作用,否则拒绝完成,把拒绝理由注回 prompt 让它继续。这堵住 LLM 最经典的"嘴上说做完其实没动"。
>
> 这两个坑是 agent 落生产的基本功,我把它做成确定性闸门,不靠模型自觉。

### 【Function Calling 稳定性 ~6 分钟】(agent 最硬的考点,着重)

> FC 不是"调个函数"那么简单,它有一整条故障链。我拆成几个故障域逐个攻,这是我在这个项目投入最系统的一块。
>
> **第一,工具暴露**。不能把所有工具一股脑塞给模型——一是 token 膨胀,二是模型会选错、会幻觉调不该调的写工具。我用 `@ToolPackDescriptor` 注解把每个工具的触发关键词、风险等级、所属 toolset 下沉成自描述元数据,运行时按 **意图 + 风险等级 + 关键词命中 + 用户权限** 四层过滤,动态裁出最小够用子集。这是越权和误触发的第一道闸门。
>
> **第二,写操作安全**。AI 调写工具——改文件、跑命令——是 agent 落生产最大的风险。我做了二阶段人工审批:AOP 拦到危险工具,先算参数 sha256 指纹、记 git HEAD baseline、算出 targetPaths,建一个 PENDING proposal 挂起,抛异常暂停。人工确认后,执行前**重读数据库二次校验**——状态机双 CAS、参数指纹复核、git HEAD 不能变、执行完比对 targetPaths,越界写就精确 rollback。把 AI 写操作关进审批笼子。审批窗口期内仓库被篡改、参数被调包、工具越界写三类事故都 fail-closed 且可追溯。
>
> **第三,循环和重试策略**。这是 FC 稳定性里最容易被做错的点。**工具调用失败不能盲目重试**——写工具副作用不幂等,重试就是重复写入。所以工具层零重试,错误回传模型让它自己纠正。但 LLM 调用层要重试,而且是**跨 provider 切换的重试**,不是同模型傻重试。这里有个关键取舍:Spring AI 内置的 RetryTemplate 是同一个模型的 HTTP 层重试,不会切 provider,我**显式关掉**(`max-attempts:1`),把重试控制权上移到自己的 `ModelCallExecutor`,做三级 failover:同 model 失败冷却 30 秒 → 同 provider 换备 model → 跨 provider 切换。故障还要分级——socket 读超时走阈值不立即熔断,连接异常、5xx、429 立即熔断,避免无谓重试放大错误。
>
> **第四,输出污染**。模型会把内部阶段名、身份信息吐给用户;更隐蔽的是,DeepSeek 关掉原生 tool-calling 后会**幻觉输出 XML 工具调用标签**(像 `<use_mcp_tool>`),假装在调工具,其实是死文本,后端不会执行会原样泄漏给用户。我做了 MetaGuard:检测词表用 yml 配可热更,检测到就用 DOTALL 正则擦掉 XML 块,擦空了降级本地技能兜底。检测和清洗是两层——能救的擦掉标签保留正文,救不了的才降级。
>
> **第五,权限、限流、审计**。AOP 一个拦截点,一次调用按序穿过权限(角色 × 工具名策略表,双通配加权打分消歧)、限流(Redis 固定窗口,Redis 挂了降级本地滑动窗口)、审计。审计我**复用 message_event 事件流**,不另建 invocation_log 表——工具调用和对话事件同一条时间线,按 sessionKey/requestId 串完整调用链,五状态打点(DENIED/START/SUCCESS/PENDING/FAILED)。
>
> 这五块合起来,FC 从"碰运气"变成"可治理"。核心一句话:**把控制权从框架上移到应用层,换框架给不了的能力**。

### 【记忆与上下文 ~3 分钟】(agent 第二考点)

> agent 第二个核心是上下文怎么喂给模型。我做成双轨:
>
> - **短期轨**:MySQL 的 message_event 事件流。每条消息带 eventKey 幂等键、role、channel、requestId,7 个复合索引。它是**可审计、可追责**的,能回答"模型为什么这么答"——这是企业场景的刚需。
> - **长期轨**:Redis VectorStore。双路召回——同会话 `recallBySession` 优先 + 跨会话 `recallByUser` 补充,带 filterExpression 按会话隔离,避免串话。
> - **第三层兜底**:进程内 ConcurrentMap,向量服务挂了保留最近几轮。三段降级,记忆永远在线。
>
> 上下文由 `ContextAssembler` **统一组装**——把事件流、语义召回、项目记忆三路织成一段 prompt。我没走 Spring AI 的 `MessageChatMemoryAdvisor` / `QuestionAnswerAdvisor` 路线,因为我的召回逻辑比 Advisor 细:要双路、要和 `MemoryCoordinator` 记忆帧层一致、要去重截断。这是设计选择,不是重复造轮子——Spring AI 的 Advisor 装不下这套精细召回。
>
> 这套双轨底座,换个数据源就是商品知识库:商品文档向量化走 Redis,合规和上下架留痕走 MySQL,写操作走 proposal 审批。我另一个 RAG 项目对这块有深度实战。

### 【模型治理 ~2 分钟】

> LLM 调用最容易挂、也最贵。我自建了 provider 治理面——多 provider 注册表(primary/qwen/coding-plan/deepseek),运行时可热切,活跃 provider/model 指针持久化 Redis 重启恢复。前面说的三级 failover 在这里落地。
>
> 为什么自建不用 Spring AI 自带的?因为它只支持单一 provider 静态配置,给不了我多 provider 运行时切换和跨 provider failover。这是框架的明确空白。
>
> 全挂了还有**本地技能降级**:绕过模型直接 Java 调 ToolPack 方法,"模型降级但工具能力不降级"——用户还能拿到天气、汇率这些确定性答案。模型不可用从致命错误降级成可服务状态。

### 【工程支撑 / harness ~2.5 分钟】

> 把上面这些撑起来的是几块工程基础:
>
> **三种入口按场景选**:同步、流式、异步。流式用 Spring AI `ChatClient.stream()` 反应式 API,每 token 在 `doOnNext` 立即推送,**首 token 延迟约等于模型 TTFT**,不用等完整回答。异步是给长任务的——OPAR 可能跑几十秒,HTTP 同步会超时,所以请求 30ms 投 RabbitMQ 返回 QUEUED,消费者跑完写 Redis(24h TTL)+ Caffeine 兜底,再回投响应队列 + WebSocket 推前端,带死信队列,消费端还做 11 项一致性校验防错投。
>
> **三套事件机制按事务边界分工**,这是我比较得意的一个设计点:message_event 是同步审计流,**不能用 ApplicationEvent**——否则事务回滚会误触发审计;proposal 生命周期用 `@TransactionalEventListener(AFTER_COMMIT)`,保证持久化后才投影;跨进程解耦才用 RabbitMQ。选型体现对 Spring 事务边界的理解。
>
> **缓存分层**:`ChatClient` 按 provider+model 缓存,避免每请求重建 `OpenAiChatModel`;工具结果 Caffeine 缓存(天气 10 分钟、汇率 30 分钟)。这里有个细节——我用 `ConcurrentMap` 装"不可淘汰的进程状态"(模型和 ChatClient 必须 1:1 永久绑定,淘汰了 failover 会丢缓存重建),用 Caffeine 装"有 TTL 的纯缓存"。"状态容器"和"缓存"这两个语义要分清,不能混用。

### 【收尾 · 和现成框架的区别 ~1.5 分钟】

> 最后说为什么这么做。
>
> **对比 Spring AI**:我是"用零件、弃装配线"。`@Tool`、`ToolCallingManager`、`ChatClient`、`RedisVectorStore`、`EmbeddingModel` 全部用,但它的 OpenAI 自动配置全关了——它只支持单 provider 静态配置,给不了我多 provider 运行时切换和 failover。
>
> **对比 LangChain**:它偏 Python 生态、偏编排链;我做 Java 企业级后端,治理内置——权限、审计、限流、事务一致性、写操作审批,这些它不内置。
>
> **对比纯调 API**:核心是把不可控的 LLM,变成可治理、可审计、可降级、可回放的后端服务。模型全挂仍可服务。
>
> 整个系统贯穿三个取舍:一是**职责上移不依赖框架默认行为**(关 RetryTemplate 换跨 provider failover);二是**故障分级不一刀切**(传输 vs 业务、read vs write、瞬时抖动 vs 过载 vs 宕机);三是**配置可热更新但代码留硬兜底**(MetaGuard 词表 yml 热更 + 硬编码兜底;限流 Redis 挂本地滑窗)。
>
> 演进方向主要两个:一是接 LLM 响应语义缓存降延迟省 token(目前已在监控 provider 原生 prompt cache 命中率);二是多模态接入,目前聚焦在文本 Agent 的稳定性治理,RAG 侧我有另一个项目的深度实战。
>
> 这是 SpringClaw,欢迎追问。

---

## 二、Python RAG 第二项目(被问到知识库/RAG 时切入,~3 分钟)

> 我还有一个项目更对口知识库——**企业智能知识助手**,Python + FastAPI + LangChain + RAGFlow + MongoDB。
>
> 做的是企业内部知识查询:入职培训、请假报销、采购流程、部门文档检索。我搭了 **意图识别 → Query Rewrite → RAG 检索 → 答案生成 → 会话落库** 五段链路,知识库问答和通用聊天两类路由。
>
> 关键点:
> - **多格式文档处理**:PDF 按段落、Word 按章节、Markdown 按标题,chunk 384 token、overlap 64。
> - **Query Rewrite**:对"还有吗""它是什么"类追问做上下文补全,补全代词、省略信息。复杂业务意图识别准确率从 75% 提到 94%。
> - **会话隔离**:session_id 隔离,MongoDB 持久化 + 内存降级。
> - **渠道适配**:抽象统一消息模型和 session/user 映射,复用同一套 RAG 链路扩展飞书、企业微信。
> - **流式**:FastAPI SSE。
>
> 这个项目让我对 RAG 工程化——分块、检索增强、多轮、降级——有完整认知。和 SpringClaw 合起来,正好是"Agent 编排引擎 + 知识库 RAG"的完整能力。

---

## 三、Agent 开发核心考点速查(背骨架,被追问时往上贴)

### ① Agent Loop 考点
- **三类引擎**:Simplified 单次 / OPAR Plan-Act-Reflect / Autonomous 自主循环。路由靠 `ChatRoutingPolicyService` 关键词评分。
- **步数 clamp**:OPAR≤6、Autonomous≤15(`Math.max/min` clamp,防配置写错变死循环)。
- **假完成验证**:`AutonomousExecutionTracker` 追踪真实副作用,无副作用拒绝 TASK_COMPLETE。
- **结构化输出**:Plan 用 `BeanOutputConverter<PlanResult>` 强约束。

### ② Function Calling 稳定性考点(最重点)
六故障域骨架:
1. **工具暴露** → `@ToolPackDescriptor` 元数据 + 四层过滤裁最小子集(`ToolOrchestrator.java:52`)
2. **写操作风险** → proposal 双 CAS + argsHash sha256 + git HEAD baseline + targetPaths 越界 rollback(`ToolRuntimeAspect.java:131`)
3. **循环重试** → 工具零重试(副作用幂等性)+ LLM 三级 failover(关 RetryTemplate 上移控制权)
4. **输出污染** → MetaGuard yml 热更词表 + DOTALL 正则清洗 XML 幻觉(`ChatResponsePolicyService.java:85`)
5. **权限越权** → 角色×工具名策略表 + 双通配加权打分(`ToolPermissionServiceImpl.java:55`)
6. **限流过载** → Redis 固定窗口 + 本地滑窗降级(`RedisToolGuardService.java:50`)
- 传输故障 → 三级 failover + 30s 冷却 + provider 熔断(`ModelCallExecutor.java:101`)
- 审计闭环 → AOP 唯一拦截点 + message_event 五状态审计(`ToolRuntimeAspect.java:66`)

### ③ 记忆与上下文考点
- **双轨**:MySQL message_event(短期可审计)+ Redis VectorStore(长期语义召回)+ 进程内 ConcurrentMap(第三层兜底)
- **双路召回**:recallBySession(同会话优先)+ recallByUser(跨会话补充)+ filterExpression 按会话隔离
- **统一组装**:ContextAssembler 织三路成 prompt(不走 Advisor 路线,召回逻辑更细)
- **幂等**:eventKey 唯一键,7 复合索引

### ④ 模型治理考点
- 多 provider 注册表 + 运行时热切 + Redis 持久化 active 指针
- 三级 failover:同 model 冷却 30s → 同 provider 换 model → 跨 provider
- 故障分级:socket 读超时走阈值 vs 5xx/429 立即熔断
- 本地技能降级:模型全挂仍可服务

### ⑤ 容错降级考点(五段降级链)
1. ModelCallExecutor:同 model 重试 → 同 provider 换 model → 跨 provider
2. 全挂:ChatServiceImpl 捕获 → LocalSkillFallbackService 规则路由本地 ToolPack
3. MetaGuard:身份/XML 检测 → repair prompt 重试 → 本地兜底 → 用户友好文案
4. DB 不可用:`initialization-fail-timeout:0` 仍启动 + 降级本地 skill
5. 向量挂:进程内 ConcurrentMap 第三层

### ⑥ 可观测/审计考点
- AOP 唯一拦截点,五状态审计(DENIED/START/SUCCESS/PENDING/FAILED)
- 审计复用 message_event 事件流(不另建表),按 sessionKey/requestId 串完整链
- 三套事件按事务边界分工:message_event(同步审计,不用 ApplicationEvent)/ `@TransactionalEventListener(AFTER_COMMIT)` / RabbitMQ

### ⑦ 流式/异步考点
- 真流式:`ChatClient.stream()` + `doOnNext` 即时推送,首 token≈TTFT
- 异步:RabbitMQ 30ms 返回 QUEUED + Redis(24h)+ Caffeine 兜底 + 死信队列 + 消费端 11 项校验
- 引擎分真流式(BasicStream/ModelLedStream)和切片打字机两档

### ⑧ 缓存/并发考点
- ChatClient per-provider-per-model 缓存(`ConcurrentMap.computeIfAbsent`)
- ConcurrentMap(状态容器,不可淘汰)vs Caffeine(纯缓存,有 TTL)的语义区分
- 工具结果 Caffeine:weather 10min/exchange 30min/news 5min

### ⑨ 设计模式考点
- 策略:引擎路由/渠道/Skill executor 三处
- 工厂:AiProviderRuntimeFactory/ContextSnapshotFactory
- AOP:ToolRuntimeAspect 拦 @Tool + 两个 HandlerInterceptor
- 适配器:ChannelAdapter + Feishu/Telegram/Wechat
- 观察者:三套事件机制按事务边界分工
- 建造者:OpenAiChatModel→ChatClient 链

---

## 四、核心数字(必背,被追问)

| 项 | 值 | 出处 |
|---|---|---|
| failover 冷却 | **30 秒**(代码兜底 90,yml 配 30) | `application.yml:220` |
| provider 熔断阈值 | 连续 2 次 | `application.yml:221` |
| max-failover-attempts | 2 | `application.yml:93` |
| same-model-retry-attempts | 0 | `application.yml:94` |
| spring.ai.retry.max-attempts | 1(显式关内置重试) | `application.yml:57` |
| OPAR 步数上限 | 6(clamp) | `OparLoopEngine.java` |
| Autonomous 步数上限 | 15(clamp) | `AutonomousLoopEngine.java` |
| 引擎数量 | 6 个 | impl/*Engine.java |
| 异步结果 TTL | 24h | `AsyncChatResultStore` |
| 工具限流 | 60/min/工具 | `RedisToolGuardService` |
| embedding 维度 | 1024(text-embedding-v4) | `EmbeddingConfig` |
| 请求超时 | 60s | `AiProviderRuntimeFactory.java:70` |

---

## 五、高频追问预案

**Q: 为什么用 Spring AI 又关掉它的自动配置?**
> 它的 OpenAI 自动配置只支持单一 provider 静态配置,给不了多 provider 运行时切换和跨 provider failover。我用它的 API 零件(@Tool/ChatClient/VectorStore),自建 provider 治理面。retry 也一样——内置 RetryTemplate 是同模型 HTTP 重试,我关掉换应用层跨 provider failover。

**Q: 冷却时间多少?**
> 配置 30 秒(`application.yml:220`),代码兜底常量是 90,线上跑 30。per-model 和 per-provider 共用。

**Q: 多实例怎么共享冷却状态?**
> 冷却状态目前进程内,单实例为主,多实例共享是演进方向,会迁 Redis。所以讲"生产级架构,为分布式预留"。

**Q: 记忆层为什么不用 Spring AI 的 MessageChatMemoryAdvisor / QuestionAnswerAdvisor?**
> 用自研 ContextAssembler 统一组装。召回逻辑比 Advisor 细——要会话级 + 用户级双路、要和 MemoryCoordinator 帧层一致、要去重截断。Advisor 的窗口语义和单路召回承载不了,是设计选择。

**Q: agent loop 怎么保证不无限跑 / 不假完成?**
> 步数硬 clamp(OPAR≤6,Autonomous≤15)+ AutonomousExecutionTracker 副作用验证,模型说完成但没真实副作用就拒绝完成注回 prompt 继续。

**Q: 工具调用失败怎么处理?**
> 分层:工具层零重试(写工具副作用不幂等,重试=重复写入),错误回传模型自我纠正;LLM 层三级 failover(同 model 冷却→同 provider 换 model→跨 provider)。故障分级:socket 超时走阈值,5xx/429 立即熔断。

**Q: AI 调写工具怎么保证安全?**
> proposal 二阶段审批:参数 sha256 指纹 + git HEAD baseline + targetPaths,执行前双 CAS 二次校验 + 越界 rollback。审批窗口期内篡改/调包/越界都 fail-closed。

**Q: 延迟多少?有 P99 吗?**
> 真流式首 token 延迟≈模型 TTFT。端到端 P50/P99 监控是演进方向,会加 OpenTelemetry 全链路 tracing。目前靠流式、缓存、异步、热路径零重建。

**Q: 这是个人项目还是团队?**
> 我主导设计与核心实现。

**Q: 和 LangChain 比优势?**
> LangChain 偏编排链、Python 生态;我做 Java 企业级后端,治理内置——权限/审计/限流/事务一致性/写操作审批,按事务边界分三套事件机制。

**Q: 有没有重复造轮子?**
> Spring AI 我重度使用——@Tool/ChatClient/VectorStore/EmbeddingModel 都用。重复的地方我评估过:工具治理 AOP、多 provider failover、OPAR 循环这些 Spring AI 没有,是必要自研;记忆组装走 ContextAssembler 不走 Advisor,是设计选择——召回逻辑更细,Advisor 装不下。

**Q: 多模态有吗?**
> SpringClaw 聚焦文本 Agent 稳定性治理;多格式文档(PDF/Word/Markdown)在我另一个 RAG 项目有深度实战。视觉/语音是演进方向。

**Q: 模型全挂怎么办?**
> 三级 failover 都失败后,ChatServiceImpl 捕获异常,降级到 LocalSkillFallbackService 规则路由本地 ToolPack——绕过模型直接 Java 调,"模型降级但工具不降级",用户还能拿确定性答案。DB 不可用也能启动(`initialization-fail-timeout:0`)。
