# CHANGELOG

记录项目最近一轮关键变更，重点覆盖：

1. 改了什么
2. 出现了什么问题
3. 最后怎么解决

最近记录按日期倒序维护。

## 2026-05-06

### 今日完成

1. 清理本地运行产物
   - 删除本地 `target/`
   - 删除本地 `frontend/node_modules/`
   - 删除本地 `frontend/dist/`
   - 删除本地 `frontend/.npm-cache/`
   - 删除本地 `.playwright-cli/`
   - 删除本地 `cp.txt`
2. 删除已被替代的模型控制旧链路
   - 删除 `ModelControlIntentService`
   - 删除两个 Agent 引擎中已无调用的 `tryAiAssistedModelControl` / `dispatchAiModelControlCommand`
   - 模型查询/切换继续由规则式 `LocalSkillModelControlSupport` 承担
3. 删除误放和过期文件
   - 删除根目录误放的 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
   - 删除过期的 Superpowers 临时设计/实施计划文档
4. 修正忽略规则
   - 根 `.gitignore` 增加 `logs/` 和 `*.pid`
   - `frontend/.gitignore` 改为面向 frontend 子目录的正确规则
5. 修复本地测试环境稳定性
   - 新增 Mockito mock maker 配置，避免测试依赖 JDK attach 权限
   - Markdown skill 导入测试改为注入 fake `HttpClient`，不再依赖本地开端口
   - final record 类型测试改为构造真实对象，不再强行 mock
6. 借鉴 Hermes Agent 继续收口 skill 体系
   - `SKILL.md` 支持无 frontmatter 的 OpenClaw/Hermes 风格 skill 目录
   - 如果目录下存在 `scripts/*.py`，会自动推断为 Python script skill
   - 从一级标题和 `## Description` 自动提取名称与描述
   - 新增 `inspectScriptSkill`，按需查看单个 skill 说明和支持文件列表
   - 新增 `runScriptSkillChain`，支持 `a -> b -> c` 顺序执行并传递上一步结果
   - 新增安全模板创建服务 `SkillScaffoldService`
   - Python skill 执行环境同时提供 `SPRINGCLAW_*` 和 `OPENCLAW_*` 变量
   - 定时任务执行层支持 `executorType=python`
   - 默认工具权限开始支持 `ScriptSkillToolPack.*` 这种通配符
7. 更新 skill 使用文档
   - 重写 `docs/SCRIPT_SKILL_GUIDE.md`
   - 明确 skill 是“说明书 + 附件包 + 可选脚本入口”，不是继续堆 Java 方法
   - 明确哪些能力适合放 skill，哪些应该留在平台底座
8. 继续向 Hermes Agent 的 skill 模式收口
   - 新增 `SkillLibraryToolPack`
   - 暴露 Hermes 风格工具名：`skills_list`、`skill_view`、`skill_view_file`
   - `skills_list` 只返回摘要，`skill_view` 才加载单个 `SKILL.md`
   - `skill_view_file` 只允许读取 `references/templates/scripts/assets` 下的支持文件
   - `ToolOrchestrator` 在用户询问 skill 列表/查看 skill 时自动加入 skill library 工具
   - `SkillCatalogService` 支持 `springclaw.skills.external-roots`
   - `repo_inspector` 增强中文领域提示，对“skill 体系怎么实现”这类泛问能命中主代码
9. 按 OpenClaw4J 思路补入真实可执行 skill
   - 新增 `system_status`，查看 CPU、内存、磁盘和进程概览
   - 新增 `content_summarizer`，对文本、工作区文件或网页做轻量摘要
   - 新增 `rss_blog_watcher`，管理 RSS/Atom 订阅并扫描最新文章
   - 新增 `crypto_price`，查询 BTC、ETH 等币价，并支持 `dryRun` 离线预检
   - 这些 skill 都走 SpringClaw 标准目录化 skill 结构，避免自动安装依赖和强绑外部账号
10. 继续收口 script skill 调用方式
   - `LocalSkillFallbackService` 新增通用高置信 script skill 自动路由
   - 新增 skill 后只要 `SKILL.md` 的名称/关键词命中，就能自动执行对应 Python skill
   - 不再要求每个新 skill 都在 Java 本地兜底里手写一个分支
   - 自动路由会过滤 `Traceback`、非零 `exitCode`、`status=failed` 等失败输出，避免坏脚本污染回答
11. 借鉴 Hermes `skill_usage.py` 增加 skill 使用侧写
   - 新增 `SkillUsageService`
   - 使用记录写入 `skills/.usage.json`，不污染 `SKILL.md`
   - `skill_view` / `skill_view_file` 会记录 `view_count`
   - Python skill 真实执行时会记录 `use_count`
   - `SkillLibraryToolPack` 新增 `skills_status`，可查看使用次数、查看次数和最近活动
   - 为后续 curator 清理、归档、合并 skill 留出 `state`、`pinned`、`patch_count` 字段
12. 继续收口 skill 执行入口
   - 新增 `SkillRuntimeService`
   - 定时任务执行 skill 时不再直接判断 `python` / `builtin` / `prompt`
   - `python`、历史兼容 `script`、`builtin` 都通过统一 runtime 分发
   - `prompt` 类型保留为说明型 skill，不允许被当作可执行任务直接运行
13. 完成 skill 命名和执行边界收口
   - `SkillPackageCatalogService` 更名为 `SkillCatalogService`
   - `SkillPackageScaffoldService` 更名为 `SkillScaffoldService`
   - `ScriptSkillToolPack` 和本地兜底脚本执行都改走 `SkillRuntimeService`
   - 新增边界测试，防止业务代码绕过 runtime 直接调用脚本执行器

### 今日验证

1. `mvn -q -DskipTests compile` 通过
2. `mvn -q -Dtest=SimplifiedOparEngineTest,LocalSkillFallbackServiceTest,ApplicationYamlPolicyTest test` 通过
3. `mvn -q test` 通过
4. `git diff --check` 通过
5. `mvn -q -Dtest=SkillCatalogServiceTest,SkillScaffoldServiceTest,ScriptSkillToolPackTest,TaskExecutionServiceTest,ToolPermissionServiceImplTest test` 通过
6. `mvn -q -Dtest=SkillCatalogServiceTest,SkillLibraryToolPackTest,ToolOrchestratorTest test` 通过
7. 真实运行 `repo_inspector` Python skill，确认可执行并能定位 skill 主链路文件
8. `mvn -q -Dtest=OpenClawRealSkillsSmokeTest test` 先失败后通过，确认新 skill 能被 catalog 发现并经 Java executor 执行
9. `mvn -q -Dtest=LocalSkillFallbackServiceTest#shouldAutoRouteHighConfidenceScriptSkillWithoutHardcodedJavaBranch test` 先失败后通过，确认自然语言能直接触发新 Python skill
10. `mvn -q -Dtest=SkillLibraryToolPackTest#shouldTrackSkillViewAndRunUsageInSidecar test` 先失败后通过，确认 view/run 会写入 sidecar 并通过 `skills_status` 展示
11. `mvn -q -Dtest=SkillRuntimeServiceTest test` 先失败后通过，确认统一 skill runtime 是真实入口
12. `mvn -q -Dtest=SkillRuntimeServiceTest,TaskExecutionServiceTest,SkillCatalogServiceTest,SkillLibraryToolPackTest,OpenClawRealSkillsSmokeTest,LocalSkillFallbackServiceTest test` 通过

## 2026-05-03

### 今日完成

1. 修复本地文件访问边界误判
   - 系统提示词增加“本地文件访问边界”
   - 明确当前不是只能读取项目目录
   - 注入 Local Files 授权根目录说明
   - 普通用户默认允许读取/搜索授权目录，仍禁止系统命令、文件写入和脚本执行
2. 增加确定性本地文件边界兜底
   - 新增 `LOCAL_FILES_BOUNDARY` 本地路线
   - 用户询问“授权目录/本地文件/是不是只能看当前项目”时，不再完全依赖模型判断
   - 回答会直接列出授权根目录和敏感路径限制
3. 优化聊天前端体验
   - Vue Agent 页面改为调用 `/api/chat/stream`
   - 静态 `/agent` 页面同步改为流式接口
   - 回复气泡增加轻量 Markdown 清洗与结构化渲染
   - 不再把 `**标题**`、分隔线等 Markdown 符号原样露给用户
4. 优化响应速度
   - 移除主链路前置的“AI 二次模型切换意图分类”调用
   - 模型切换/查询继续由本地规则控制面处理
   - 普通问答减少一次额外模型调用
5. 流式接口修正
   - `/api/chat/stream` 改为先返回 `SseEmitter`
   - 后台异步执行 Agent
   - 支持 `status/token/error/done` 事件

### 今日问题与处理

#### 问题 1：前端仍显示“只能读取当前项目目录”

- 现象：
  - 用户刷新后仍看到旧式回答
- 排查：
  - 直接用全新登录会话调用后端 `/api/chat/send`
  - 后端已能回答“能读取授权本地目录 `/Users/hanbingzheng`”
  - 数据库 `tool_permission / skill_descriptor / skill_policy` 均为空，不是 DB 残留策略导致
- 判断：
  - 主要原因是旧会话上下文污染和前端仍走 `/api/chat/send`
- 处理：
  - 增加确定性 `LOCAL_FILES_BOUNDARY`
  - 前端切到 `/api/chat/stream`
  - 建议用户对这类边界问题新开会话确认

#### 问题 2：回复像 Markdown 原文，不像成熟 Agent 产品

- 现象：
  - 页面直接展示 `**结论**`、`---` 等符号
- 处理：
  - 前端增加轻量 Markdown block parser
  - 标题、段落、列表、代码块分别渲染
  - 行内 `**`、反引号等符号清洗掉

#### 问题 3：流式接口名义存在，但前端没有使用

- 现象：
  - 后端已有 `/api/chat/stream`
  - Vue 和静态页面都仍调用 `/api/chat/send`
- 处理：
  - Vue `streamChat` 使用 fetch body reader 解析 SSE
  - 静态页面也改为解析 SSE
  - 后端 stream 方法改成异步执行，避免连接建立前被 Agent 执行阻塞

#### 问题 4：本地兜底过多会拖慢主链路

- 保留：
  - 模型查询/切换规则兜底
  - 本地文件边界
  - 天气、汇率、新闻
  - 网页抓取 skill
  - 项目代码分析和项目审查
  - 会话记忆查询
- 调整：
  - 删除主链路前置 AI 二次模型切换意图分类
  - 理由是它会额外消耗一次模型请求，且本地规则已经能覆盖主要控制意图

### 今日验证

1. `npm run build` 通过
2. `mvn -q -DskipTests compile` 通过
3. `mvn -q -Dtest=LocalSkillFallbackServiceTest#shouldAnswerAuthorizedLocalFileBoundaryDeterministically,SoulPromptServiceTest,ApplicationYamlPolicyTest test` 通过
4. 完整 `mvn -q test` 需要非沙箱运行，当前审批超时，未能完成 fresh verification
5. 重启当前 18080 后端需要停止旧进程，当前审批超时，未能自动重启

## 2026-03-21

### 今日完成

1. 补齐大模型调用用量统计
   - 新增 `llm_usage_record` 持久化表
   - 主回答、计划、行动、本地讲述、模型控制意图识别都开始记录调用用量
   - 支持 `prompt/completion/total tokens` 与 `usageKnown` 标记
2. 管理后台增加模型用量视图
   - 新增用量汇总卡片
   - 新增最近调用列表
   - 后台可直接查看按 provider / model / user 的统计结果
3. 收口用量统计的运行态验证
   - 真机发起聊天请求
   - 验证 MySQL 写入 `llm_usage_record`
   - 验证 `/api/admin/manage/llm-usage/*` 接口和后台页面联动
4. 新增聊天链路策略
   - 默认仍以 `simplified` 作为主链路
   - 管理员后台新增“默认聊天链路 + 自动升级”设置
   - 支持复杂任务自动升级到 `opar`
   - 仅 `ADMIN/DEVELOPER` 可通过“深度分析：”/“普通回答：”显式覆盖本次请求
5. 后台增加聊天链路控制区
   - 新增 `/api/admin/manage/chat-routing` 读写接口
   - 管理员页可直接查看和修改当前默认链路
   - 快照卡片同步展示当前默认链路与自动升级状态

### 今日问题与处理

#### 问题 1：后台想看 token 用量，但系统之前没有调用级记录

- 现象：
  - 后台能看活跃 token 会话
  - 但看不到每次模型调用的 token 消耗
- 风险：
  - 无法判断谁在用模型、哪个模型消耗高、哪些链路没有 usage
- 处理：
  - 新增 `llm_usage_record`
  - 每次模型调用后记录：
    - `requestId`
    - `sessionKey`
    - `channel`
    - `userId`
    - `providerId`
    - `model`
    - `source`
    - `prompt/completion/total tokens`

#### 问题 2：不是所有模型调用都在主回答链路，后台统计会漏数

- 现象：
  - 之前主回答、OPAR 计划/行动已经有埋点
  - 但“模型控制意图识别”这种辅助调用还没统计
- 风险：
  - 后台总调用数和真实情况不一致
  - 管理员会误判哪个链路最耗模型
- 处理：
  - 给 `ModelControlIntentService` 增加统一 usage 记录
  - 记录来源 `source=model-control-intent`
  - 与主链路保持相同落库结构

#### 问题 3：统计要能运行，但不能强依赖数据库成功

- 现象：
  - 系统支持 `db-enabled=false`
  - 也存在数据库偶发写入失败场景
- 风险：
  - 一旦写库失败，用量视图直接失效
- 处理：
  - `LlmUsageRecordServiceImpl` 保留本地回退缓存
  - DB 失败时降级本地内存，后台仍可查看最近样本

#### 问题 4：链路只有启动配置，管理员无法在运行期调整默认策略

- 现象：
  - `simplified/opar` 只能靠配置 + 重启切换
  - 普通用户也不应该直接看到技术模式名
- 风险：
  - 运维成本高
  - 默认链路无法按当前稳定性需要快速调整
  - 用户侧容易接触到底层实现细节
- 处理：
  - 新增 `ChatRoutingStateService`
  - 用 Redis/DB 审计保存当前默认链路和自动升级开关
  - 后台提供管理员专用接口和页面控件

#### 问题 5：复杂任务需要更深链路，但不该让所有请求都默认走 `opar`

- 现象：
  - 全局默认 `opar` 太重
  - 全局只走 `simplified` 又会让多步复杂任务能力不足
- 风险：
  - 稳定性和智能性互相掣肘
- 处理：
  - 新增 `ChatRoutingPolicyService`
  - 复杂问题按关键词、长度、多步特征自动升级到 `opar`
  - `ADMIN/DEVELOPER` 支持“深度分析：”和“普通回答：”手动覆盖
  - 普通用户继续只看到业务语义，不暴露技术模式名

### 今日结果

1. 后台现在能看真正的模型 token 用量，而不只是登录 token 会话
2. 用量统计已覆盖主回答链路和模型控制辅助链路
3. 管理员可以直接在 `/admin` 看到：
   - 总调用次数
   - 总 tokens
   - top provider / top model
4. 管理员现在可以在后台控制默认聊天链路，不再只能靠改配置
5. 系统开始具备“默认稳定、复杂任务自动加深”的运行时策略
   - 最近用量记录
4. 这轮改动已经具备提交和持续记录条件

### 今日追加修复

#### 问题 4：日志/代码分析类追问在简化模式下仍可能整体退成 `Read timed out`

- 现象：
  - 用户先贴一段异常或工具日志，再追问 `用代码分析分析他`
  - 简化模式没有把这类模糊跟进问题接到本地上下文分析
  - 工具可能先执行成功，但最后远程模型整理答案时超时，最终只回一句失败
- 根因：
  - `SimplifiedOparEngine` 没有像 OPAR `Act` 阶段那样绑定 `ToolExecutionContext`
  - 工具审计因此落到 `tool-session/tool-user`
  - `OparContextAwareSupport` 只识别“怎么回事/现在呢”这类失败追问，不识别“分析分析他”这类日志诊断追问
- 处理：
  - 在简化模式的工具调用外层补上真实 `ToolExecutionContext`
  - 新增“最近日志/报错/代码跟进分析”本地解释路线
  - 把 `Read timed out` / `SocketTimeoutException` 纳入最近失败识别
- 结果：
  - 同类追问会优先本地解释，不再先走远程模型总结
  - 工具审计会正确落到真实 `sessionKey/userId/requestId`

#### 问题 5：工具已经执行成功，但简化模式最后一步总结超时后仍然整轮失败

- 现象：
  - 本地工具已经成功，例如 `FileToolPack.listFiles`
  - 但远程模型在整理最终答复时超时
  - 用户最终只看到一句失败，而不是已经拿到的工具结果
- 根因：
  - 简化模式在主回答异常后只会尝试结构化本地技能兜底
  - 没有把同一 `requestId` 下的工具审计结果拿来直接生成答复
- 处理：
  - 在 `SimplifiedOparEngine` 中新增基于 `requestId + sessionKey` 的工具审计兜底
  - 如果工具已有 `SUCCESS` 结果，就直接把工具结果组织成用户可读答复
  - 补充 `SimplifiedOparEngineTest` 锁住该行为
- 结果：
  - 以后即使远程模型最后一步超时，只要工具已经成功，用户仍能拿到部分有效结果

### 今日继续重构：统一 Skill 结构

#### 今日完成

1. 引入统一 `SkillDefinition`
   - 新增统一运行时 skill 模型
   - 先把 builtin skill 和 script skill 收到同一个注册中心
2. 新增第一批显式 builtin skill
   - `code-analysis`
   - `log-diagnostics`
3. 新增 `SkillRegistryService`
   - 统一聚合 builtin skill 与脚本 skill
   - 后续接 ClawHub `SKILL.md` 时有统一落点
4. 本地兜底开始优先执行显式 builtin skill
   - “代码分析”
   - “日志诊断”
   不再只是散落在关键词规则里
5. Prompt 层去掉脚本技能重复拼接
   - `SoulPromptService` 不再单独拼 “core script skills / script skills”
   - 统一由 `SkillService` 输出显式 skill + 基础能力
6. 路由层开始识别 skill 的 `preferredMode`
   - 命中 `code-analysis/log-diagnostics` 时，即使问题不长，也可自动升级到 `opar`
7. 后台新增统一 skill registry 接口
   - `/api/admin/manage/skills/registry`

#### 这轮主要问题

##### 问题 1：项目里同时有多套“像 skill 的东西”

- 现象：
  - `tool pack`
  - `skill_descriptor / skill_policy`
  - `skills/*.py + *.skill.json`
  - 本地 fallback 里的隐式能力
- 风险：
  - 同一类能力会在多个入口重复实现
  - Prompt、后台、路由各自维护一份能力描述
  - 后续接 ClawHub `SKILL.md` 会更乱
- 处理：
  - 先不推翻现有工具层
  - 新增统一 `SkillDefinition`
  - 先把 builtin 和 script 两类 skill 收编到 `SkillRegistryService`

##### 问题 2：Prompt 层重复描述脚本能力和技能能力

- 现象：
  - `SkillService` 描述一份
  - `ScriptSkillCatalogService` 又描述一份
  - `SoulPromptService` 同时注入两段
- 风险：
  - 提示词冗余
  - 同一个脚本能力在 prompt 里重复出现
- 处理：
  - `SoulPromptService` 改成只依赖 `SkillService`
  - `SkillServiceImpl` 统一输出：
    - 显式技能
    - 基础能力

##### 问题 3：builtin skill 只有定义，没有进入执行语义

- 现象：
  - 即使定义了 `code-analysis`
  - 实际仍主要靠散落关键词和旧路由逻辑工作
- 风险：
  - skill 只是“新名字”，不是新结构
- 处理：
  - 新增 `BuiltinSkillExecutionService`
  - `LocalSkillFallbackService` 先尝试显式 builtin skill
  - `ChatRoutingPolicyService` 识别 builtin skill 的 `preferredMode`

##### 问题 4：后台看不到统一后的运行时 skill 视图

- 现象：
  - 后台只能看 DB skill 和 script skill
  - 看不到“当前真正可路由的统一 skill 集合”
- 风险：
  - 重构效果无法被管理端验证
- 处理：
  - `AdminManageController` 新增 runtime skill registry 视图
  - dashboard 增加 `runtimeSkills`

#### 这轮结果

1. 现在 skill 不再只是 `toolPack` 的别名
2. `code-analysis` 和 `log-diagnostics` 已经成为第一批显式 skill
3. Prompt、路由、本地执行开始共享同一套 skill 定义
4. 后续接 ClawHub `SKILL.md` 时，不需要再新造第四套 skill 结构

### 今日继续重构：Markdown Skill 导入与认证补强

#### 今日完成

1. 新增 `MarkdownSkillCatalogService`
   - 支持导入远程 `SKILL.md`
   - 支持 GitHub raw/blob/tree URL 归一化
   - 支持 ClawHub 页面里的 `readme:"..."` 提取
2. 统一 runtime skill registry
   - builtin / script / markdown 三类 skill 都进入 `SkillRegistryService`
   - `ChatRoutingPolicyService` 和 `SoulPromptService` 开始直接使用 runtime skill 定义
3. 后台增加 runtime skill 管理区
   - 新增 Markdown skill 导入入口
   - 后台直接展示 runtime skill 列表和来源统计
4. 完成一次真实 ClawHub skill 导入验证
   - 导入 `https://clawhub.ai/steipete/summarize`
   - 已成功落地为本地 `SKILL.md`
   - 问答链路已完成一次带 `summarize` 关键词的实际调用验证
5. 修复认证链路对账号状态校验不足的问题
   - `authenticateToken()` 现在会回查账号是否仍存在且为 `ACTIVE`
   - 活跃 token 会话列表也不再把已删除/禁用账号算进去

#### 今日问题与处理

##### 问题 1：ClawHub 页面不是原始 Markdown，第一次导入会把整页 HTML 当成 skill

- 现象：
  - 导入接口能成功
  - 但写入本地的 `SKILL.md` 是整页 HTML
  - runtime skill 的 `name/description/instructions` 都退化了
- 根因：
  - ClawHub 页面把真实 skill 内容嵌在页面脚本里的 `readme:"..."` 字段
  - 初版导入逻辑虽然尝试提取，但对页面内容的实际解析不稳定
- 处理：
  - 收紧 `tryExtractClawhubReadme(...)`
  - 优先提取脚本里的 `readme:"..."` 内容，再做转义还原
  - 用真实页面做回归测试
- 结果：
  - 现在导入 `steipete/summarize` 会生成真实 `SKILL.md`
  - runtime registry 中的 Markdown skill 已能显示正确 `name/description/instructions`

##### 问题 2：导入成功后，skill 只是“挂在后台里”，还没进入实际路由

- 现象：
  - 如果 runtime skill 只展示不参与 prompt 和路由，那它对 agent 没有真正价值
- 处理：
  - `ChatServiceImpl` 初始化上下文时会按问题匹配 runtime skill
  - 命中的 skill 会参与：
    - 路由模式选择
    - 系统 prompt 注入
- 结果：
  - 导入的 Markdown skill 已不再是静态配置，而是进入实际对话链路

##### 问题 3：删除或禁用账号后，旧 token 仍可能继续通过认证

- 现象：
  - token 认证之前只信 Redis/local token 缓存
  - 不回查账号状态
- 风险：
  - 已删除/禁用账号的旧 token 仍可能继续访问后台或聊天接口
- 处理：
  - `authenticateToken()` 增加账号存在性和 `ACTIVE` 状态校验
  - `listActiveSessions()` / `tokenRuntimeStats()` 也同步过滤失效账号
- 结果：
  - token 缓存不再天然高于账号状态
  - 后台看到的活跃会话更接近真实可用会话

## 2026-03-20

### 今日完成

1. 收敛项目对外描述
   - 重写 `README.md`
   - 重写 `docs/PROJECT_STATUS.md`
   - 补充 GitHub 仓库、当前分支、后台能力、飞书上下文策略说明
2. 建立持续记录基线
   - 初始化 git 仓库
   - 新建 GitHub 远端并推送到 `codex/bootstrap-github`
   - 清理本地敏感配置，避免把运行密钥提交到仓库
3. 收敛飞书群聊上下文边界
   - 私聊与群聊分作用域
   - 群聊保留群共享记忆
   - 群聊不再补召回用户私聊/跨场景个人长期记忆
   - 群上下文增加说话人标识，便于做群总结
4. 管理后台继续产品化
   - 后台改成 `ADMIN only`
   - 展示活跃 token 会话、用户活跃度、模型与运行状态

### 今日问题与处理

#### 问题 1：项目还不是 git 仓库，且本地配置里有真实密钥

- 现象：
  - 项目目录没有 `.git`
  - `.run/` 和 `.idea/workspace.xml` 里存在真实 API key、数据库密码
  - `application.yml` 里还有飞书 `app-secret` 默认值
- 风险：
  - 直接上传 GitHub 会泄漏生产/本地敏感配置
- 处理：
  - 扩充 `.gitignore`
  - 忽略 `.run/`、`.idea/`、`cp.txt`、`docs/PROJECT_MEMORY.md`
  - 去掉 `application.yml` 里的飞书敏感默认值
  - 保留本地运行配置，但不纳入版本控制

#### 问题 2：主应用需要重启，但重启链路不能破坏当前环境

- 现象：
  - 需要让飞书群聊作用域和最新后台逻辑在 `18080` 生效
  - 但当前进程依赖多组本地环境变量
- 风险：
  - 粗暴重启容易把 `coding-plan`、`deepseek`、embedding、MySQL/Redis 配置丢掉
- 处理：
  - 先读取正在监听 `18080` 的 Java 进程环境
  - 按原有环境重新拉起新进程
  - 补生成 `cp.txt`
  - 健康检查确认 `UP` 后再继续验证

#### 问题 3：飞书群聊需要“像在群里听大家说话”，但不能吃进私聊记忆

- 现象：
  - 原实现只按 `feishu:chatId` 记忆
  - 群里能共享短期上下文，但也可能带进用户私聊或跨场景个人长期记忆
- 风险：
  - 做群总结时会混入私人记忆
  - 群助手像“偷看了私聊历史”，不是正常群机器人行为
- 处理：
  - 新增 `ConversationScopeSupport`
  - 飞书会话显式分 `p2p/group`
  - 群聊只保留群会话记忆，不再补召回用户跨场景长期记忆
  - 群上下文显示 `USER(ou_xxx)` 说话人标识

#### 问题 4：GitHub 远端推送缺少认证

- 现象：
  - 本机没有 `gh`
  - 也没有现成 GitHub SSH key
- 处理：
  - 引导生成 SSH key
  - 添加 GitHub 公钥
  - 验证 `ssh -T git@github.com`
  - 成功后把仓库推到 `HhhBZzz/SpringClaw`

### 今日结果

1. 项目描述和状态文档已经更新到今天
2. GitHub 已接入，可按提交记录看每日变更
3. 主应用已重启到最新代码
4. 飞书群聊的记忆边界已经调整到更符合业务直觉的版本

## 2026-03-17 ~ 2026-03-19

### 本轮主要完成

1. 启动链路收口
   - 关闭 Spring AI 1.1.x 中项目没用到的自动模型装配
   - 修正旧属性名和升级后的配置差异
   - 解决 `speech` 自动装配强制要求 OpenAI key 导致的启动失败
2. 模型 provider 链路收口
   - 修复“配置写了环境变量占位，但运行进程没带 key”的问题
   - 明确当前可用 provider
   - 补接 `deepseek`
   - 支持 `coding-plan` / `deepseek` 切换
3. 长期记忆收口
   - 独立接入 embedding 配置
   - 使用 `text-embedding-v4`
   - 打通 Redis Vector Store
4. 对话上下文收口
   - `memory-window-size=8` 从“近似 8 条消息”修正为“8 轮”
   - 清理 `[OBSERVE]` 回写污染
   - 历史读取兼容旧脏数据
5. 权限与后台收口
   - 登录态接入主业务链路
   - `/api/admin/**` 等敏感接口接角色控制
   - 后台页从演示页改成可登录、可操作的管理台

### 本轮问题与处理

#### 问题 1：Spring AI 升级后，应用启动时错误创建无关模型 Bean

- 现象：
  - `openAiAudioSpeechModel` 创建失败
  - 应用提示必须配置 OpenAI API key
- 根因：
  - Spring AI 1.1.x 开始按模型家族自动创建 `chat / embedding / audio / image / moderation` Bean
  - 项目实际没用这些自动 Bean
- 处理：
  - 显式关闭不用的 model family 自动配置
  - 保留自建 provider 运行时链路

#### 问题 2：模型 key 配置看似在 `application.yml`，实际进程里是空的

- 现象：
  - 页面和日志显示“未配置有效 API Key”
- 根因：
  - `application.yml` 用的是环境变量占位符，不是真实写死 key
  - 启动进程没带对应环境变量
- 处理：
  - 修正 IntelliJ 运行配置
  - 明确当前活动 provider
  - 用进程环境核查真实运行态

#### 问题 3：长期记忆看起来可用，但实际没真正持久化到 Redis 向量索引

- 现象：
  - 记忆能召回，但 Redis 没有向量索引
- 根因：
  - Redis Stack 容器启动方式不对，RediSearch 没加载
  - embedding 链路也没有真正打通
- 处理：
  - 修正 `docker-compose` 的 Redis Stack 启动方式
  - 独立接入 embedding 配置
  - 使用 `text-embedding-v4`
  - 验证索引、写入、重启后召回

#### 问题 4：上下文“看起来很短”，实际是历史里有大量重复污染

- 现象：
  - 模型像记不住历史
  - `USER CHAT` 里写入了整段 `[OBSERVE]`
- 根因：
  - 运行时上下文被回写进持久化历史
  - 下一轮再读出来时出现上下文套娃
- 处理：
  - 持久化只保留原始用户消息
  - 读取链路兼容旧污染历史
  - 放宽短期窗口解释为“8 轮”

#### 问题 5：权限模块有雏形，但没有接到主业务入口

- 现象：
  - 有用户、角色、token、工具权限设计
  - 但聊天接口和管理接口没有真正按登录身份收口
- 处理：
  - 接入 token 鉴权拦截
  - 敏感接口做角色校验
  - 聊天链路不再信任手填 `userId`
  - 后台只允许管理员访问

### 本轮结果

1. 应用启动链路稳定下来
2. `coding-plan` 与 `deepseek` 可用
3. Redis 长期记忆真实落地
4. 上下文污染问题显著缓解
5. 后台和权限从“半成品”变成了可用闭环

## 后续记录规则

从下一轮开始，按这个格式持续追加：

1. 日期
2. 今日完成
3. 今日问题与处理
4. 当前结果 / 未决项
