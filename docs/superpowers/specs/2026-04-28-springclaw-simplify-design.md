# SpringClaw 简化与 Skills 系统重构设计

日期: 2026-04-28

## 目标

1. Skills 系统按需加载（类 Claude Code 模式）：请求时匹配最相关 skill，注入 content 到 system prompt
2. 删除配置冗余，统一命名体系
3. 全局重命名 openclaw → springclaw
4. ChatServiceImpl 拆分
5. 安全加固与代码卫生

## Section 1: Skills 系统统一

### 新架构

```
skills/packages/<name>/SKILL.md    ← 唯一入口
SkillPackageCatalogService         ← 启动时扫描，解析 frontmatter，构建 SkillBundleDefinition
SkillRegistryService               ← 统一注册中心，keyword 匹配 + 注入
```

### SKILL.md 格式

```markdown
---
name: repo_inspector
displayName: 代码仓库检查
description: 分析项目中某个功能在哪实现
type: script
keywords: [找文件, 搜代码, search file]
priority: 20
enabled: true
agentVisible: true
---

# 行为指令内容
...注入到 system prompt 的内容正文...
```

### 执行流程

1. 请求到达 → `ChatServiceImpl.initializeChatContext()`
2. `SkillRegistryService.matchAgentVisibleDefinitions()` 用 keyword 匹配
3. 匹配的 skill 的 SKILL.md body 内容注入到 system prompt
4. 对于 script 类型：LLM 调用 `ScriptSkillToolPack.runScriptSkill()`
5. `ScriptSkillExecutorService` 执行 Python 进程

### 删除项

- `SkillRegistryService` 的 legacy 构造函数
- `BuiltinSkillCatalogService`、`ScriptSkillCatalogService`、`MarkdownSkillCatalogService` 独立类
- 旧 skills 根目录文件（已标记删除）
- `application.yml` 中 `openclaw.skill.*` 的 trigger keywords（迁移到 SKILL.md frontmatter）

### 保留项

- `@Tool` 注解工具类全部不变
- `ScriptSkillExecutorService` 执行链路不变
- `SkillServiceImpl` 权限策略不变

## Section 2: 配置层收敛

### 统一为 springclaw.skills.*

```yaml
springclaw:
  skills:
    root: ${user.dir}/skills/packages
    enabled: true
    python: python3
    timeout-seconds: 8
    max-output-chars: 3000
    allowed: "*"
    markdown-enabled: true
```

### 删除

- `openclaw.tools.script.*` 配置段
- `openclaw.skill.*` trigger keywords 配置
- `@Value` 中所有 `${a:${b:default}}` 嵌套回退

## Section 3: 全局命名重命名

| 层面 | 旧 → 新 |
|------|---------|
| 环境变量 | OPENCLAW_* → SPRINGCLAW_* |
| 配置键 | openclaw.* → springclaw.* |
| Java 包 | com.openclaw → com.springclaw |
| Maven groupId/artifactId | com.openclaw / openclaw-java → com.springclaw / springclaw |
| 应用名 / 主类 | openclaw-java / OpenClawJavaApplication → springclaw / SpringClawApplication |
| Redis key 前缀 | openclaw:* → springclaw:* |
| 属性类 | OpenClawXxx → SpringClawXxx |
| 文档中引用 | openclaw → springclaw |

## Section 4: ChatServiceImpl 拆分

```
ChatServiceImpl                 编排入口 (~150行)
  ChatContextFactory            构建上下文
  ChatResultPersister           持久化结果
  MetaGuardExecutor             元话术检测与重试
  StreamChatHandler             SSE 流式处理
```

## Section 5: 安全与代码卫生

- CORS 默认收紧
- 500 异常不暴露内部 detail
- Token 只从 Authorization header 读取
- MyBatis SQL 日志默认关闭
- 删除 .DS_Store
- Health check 打开 DB/Redis

## 验收标准

1. `mvn clean package -DskipTests` 成功
2. `mvn test` 全部通过
3. Skills 正常匹配和注入
4. 旧配置键全部清理
5. 所有 Java 文件中无残留 openclaw 命名
