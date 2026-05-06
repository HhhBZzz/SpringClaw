# Script Skill Guide

## 结论

SpringClaw 的 skill 收口为一种标准目录结构：

1. 一个 skill = `skills/<skillId>/` 下的一个目录
2. `SKILL.md` 是唯一说明入口，负责描述能力、触发词、执行方式和使用边界
3. 可执行代码放在 `scripts/`，默认入口优先识别 `scripts/run.py`
4. 长说明、模板、静态资源按需放在 `references/`、`templates/`、`assets/`
5. Agent 不应该一次性把所有 skill 全塞进 prompt，而是先看列表，需要时再打开具体 skill

这套形态参考了 Claude Code / Codex / Hermes 的 skill 原理：skill 更像“可被模型按需读取的能力说明书 + 附件包”，不是单纯 Java 方法别名。

## Hermes 式加载方式

当前项目已经把 skill 工具调整成接近 Hermes 的三层 progressive disclosure：

```text
Level 0: skills_list()
  只列出 skillId、名称、类型、分类和短描述

Level 1: skill_view(name)
  打开一个 skill 的完整 SKILL.md，并列出 supporting files

Level 2: skill_view_file(name, filePath)
  读取 references/templates/scripts/assets 下的指定支持文件
```

这样做的意义是：模型先知道“有哪些能力”，真正要用哪个时再打开哪个，不把所有 skill 的长说明一次性灌进上下文。

## 标准目录

最小结构：

```text
skills/my_skill/
├── SKILL.md
└── scripts/
    └── run.py
```

推荐结构：

```text
skills/my_skill/
├── SKILL.md
├── scripts/
│   └── run.py
├── references/
│   └── README.md
├── templates/
└── assets/
```

当前已支持 OpenClaw/Hermes 风格的简化 skill 目录：如果 `SKILL.md` 没有 frontmatter，但目录下存在 `scripts/*.py`，系统会按目录名注册 skill，并从一级标题和 `## Description` 提取名称与描述。

额外 skill 目录也可以通过配置挂进来：

```yaml
springclaw:
  skills:
    external-roots: /path/to/skills-a,/path/to/skills-b
```

## SKILL.md 推荐写法

```md
---
name: 示例技能
description: 说明这个 skill 是干什么的
skillId: example_skill
type: python
entrypoint: scripts/run.py
category: general
tier: utility
inputHint: 传入 goal 或 JSON 参数
priority: 100
enabled: true
agentVisible: true
preferredMode: simplified
contextPolicy: session-only
toolPacks:
  - script
triggerKeywords:
  - 示例技能
triggerExamples:
  - 用 example_skill 做个演示
---

# 示例技能

## Description
说明这个 skill 的用途、边界和输入输出。
```

关键字段：

1. `skillId`：稳定 ID，定时任务、注册中心和调用都用它
2. `type`：`python`、`builtin`、`prompt`，`node` 目前只预留识别
3. `entrypoint`：可执行 skill 的入口脚本
4. `triggerKeywords`：帮助 agent 匹配任务
5. `triggerExamples`：给模型几个真实用法
6. `toolPacks`：声明需要的工具包权限

## 当前 Skill 类型

定义扫描入口：`SkillCatalogService`。
对外执行入口：`SkillRuntimeService`。
查看入口：`SkillLibraryToolPack`。
脚本适配器：`ScriptSkillExecutorService`，只供 runtime 和 builtin adapter 内部使用。

调用方只传入 `SkillDefinition + inputPayload`，或者传入 `skillId + allowedToolPacks + inputPayload`，不用自己判断 `python`、`builtin` 或 `prompt`。

1. `python` script skill
作用：低耦合外挂能力，例如项目检查、网页抓取、数据采集、运行时探针。
执行：`SkillRuntimeService -> ScriptSkillExecutorService` 调 Python 脚本。

2. `builtin` skill
作用：高频、强确定性的 Java 内建能力，例如代码分析、日志诊断、本地文件边界说明。
执行：`SkillRuntimeService -> BuiltinSkillExecutionService`。

3. `prompt` skill
作用：只提供说明和流程，不直接跑脚本，适合 ClawHub/Markdown 类技能。
执行：进入 prompt/路由上下文；不允许被定时任务当作可执行 skill 直接运行。

## OpenClaw4J 风格真实 Skill

当前已经按 OpenClaw4J 的思路补入一组可执行 skill，但实现上做了 SpringClaw 化：

1. `system_status`
   - 参考 OpenClaw4J `system-status`
   - 查看 CPU、内存、磁盘、进程概览
   - 优先用本机已有 `psutil`，否则用标准库和系统命令降级

2. `content_summarizer`
   - 参考 OpenClaw4J `summarize` / `chat-summary`
   - 对文本、工作区文件或普通网页做抽取式摘要
   - 不调用远程模型，避免和主 Agent 模型链路重复

3. `rss_blog_watcher`
   - 参考 OpenClaw4J `blogwatcher`
   - 管理 RSS/Atom 订阅并扫描最新文章
   - 订阅数据保存在 `skills/rss_blog_watcher/data/blogs.json`

4. `crypto_price`
   - 参考 OpenClaw4J `crypto-price`
   - 查询 BTC、ETH 等币价
   - 不依赖 `ccxt`，使用 CoinGecko 公共 API；`dryRun=true` 可离线测试

没有直接搬 OpenClaw4J 里需要邮箱、Notion、Trello、Twitter、语音模型、视频处理等账号或重依赖的 skill。原因是这些能力如果没有凭证、权限边界和依赖管理，容易变成“看起来有，实际一跑就坏”的花架子。

## Python 输入输出约定

运行时调用形式：

```bash
python3 skills/my_skill/scripts/run.py '{"goal":"...","workspaceRoot":"...","skillName":"..."}'
```

脚本可读取这些环境变量：

1. `SPRINGCLAW_WORKSPACE_ROOT`
2. `SPRINGCLAW_SCRIPT_ROOT`
3. `SPRINGCLAW_SKILL_ROOT`
4. `SPRINGCLAW_SKILL_NAME`
5. `OPENCLAW_WORKSPACE_ROOT`
6. `OPENCLAW_SCRIPT_ROOT`
7. `OPENCLAW_SKILL_ROOT`
8. `OPENCLAW_SKILL_NAME`

`OPENCLAW_*` 是兼容旧脚本和 OpenClaw 风格 skill 目录的别名。

建议输出规则：

1. 先给结论
2. 再给证据或列表
3. 输出纯文本或 JSON，不做交互式输入
4. 控制长度，避免把网页或日志全量喷出来

## Agent 可用工具

`ScriptSkillToolPack` 当前提供：

1. `listScriptSkills`：只列 skill 的名称、描述、输入提示和示例
2. `inspectScriptSkill`：按需查看某个 skill 的 `SKILL.md` 和支持文件列表
3. `runScriptSkill`：按 JSON 参数执行某个脚本 skill
4. `runScriptSkillByGoal`：用自然语言目标执行某个脚本 skill
5. `runScriptSkillChain`：按 `a -> b -> c` 顺序执行多个脚本 skill，并把上一步结果传给下一步
6. `createPythonSkillTemplate`：创建一个安全的 Python skill 模板

`SkillLibraryToolPack` 当前提供：

1. `skills_list`：Hermes 风格 skill 摘要列表
2. `skill_view`：查看单个 skill 的完整 `SKILL.md`
3. `skill_view_file`：读取单个 skill 的支持文件
4. `skills_status`：查看 skill 的使用次数、查看次数和最近活动

这就是 Hermes 值得借鉴的核心：列表只给索引，真正要用哪个 skill 时再打开哪个，避免 prompt 被所有技能说明污染。

## Skill 使用记录

参考 Hermes `skill_usage.py`，SpringClaw 现在把运行态使用记录写到 sidecar：

```text
skills/.usage.json
```

这个文件只保存运行态数据，不修改 `SKILL.md`：

1. `view_count`：`skill_view` / `skill_view_file` 被查看次数
2. `use_count`：Python skill 被真实执行次数
3. `patch_count`：预留给后续 skill 管理/编辑
4. `last_viewed_at` / `last_used_at` / `last_patched_at`
5. `state` / `pinned`：预留给后续 curator 清理、归档、保护

这样后续要做 Hermes 的 curator 模式时，可以按真实使用情况判断：

1. 哪些 skill 常用，应该提升为 core
2. 哪些 skill 长期不用，可以标记 stale
3. 哪些 skill 被合并或废弃，可以进 archive
4. 哪些 skill 被用户 pinned，不能自动动

## 本地兜底自动路由

现在新增 script skill 不需要再为每个能力写 Java 分支。

本地兜底链路会在天气、汇率、项目分析等确定性路线之后，按 `SKILL.md` 里的名称、关键词、分类做一次高置信匹配：

```text
用户问题
-> ScriptSkillCatalogService.matchBestDefinition(question, minScore=3)
-> 命中可见 Python skill
-> SkillRuntimeService.executeBySkillId(skillId, question, allowedToolPacks)
```

例如：

1. “查看系统状态”可以直接命中 `system_status`
2. “BTC 和 ETH 价格怎么样”可以直接命中 `crypto_price`
3. “总结这段文本”可以直接命中 `content_summarizer`

为了避免误触发，只有分数达到 3 的明确命中才会自动执行。脚本输出如果包含 `Traceback`、非零 `exitCode`、`status=failed` 等失败信号，会被识别为失败，不当作正常兜底回答。

## 新增一个 Skill

方式一：手动创建目录

1. 新建 `skills/my_skill/SKILL.md`
2. 新建 `skills/my_skill/scripts/run.py`
3. 填好 frontmatter
4. 调 `/api/admin/manage/script-skills/reload` 或重启服务

方式二：让系统生成模板

通过 `ScriptSkillToolPack.createPythonSkillTemplate` 创建模板。它只生成安全骨架，不提供任意 patch/delete，避免把 agent 放大成无边界文件编辑器。

## 定时任务中的 Skill

定时任务可以把 `targetType` 设置为 `skill`，`targetRef` 设置为 skillId，例如：

```json
{
  "targetType": "skill",
  "targetRef": "web_crawler",
  "inputPayload": "抓取 https://example.com 并总结标题"
}
```

现在定时任务统一调用 `SkillRuntimeService`。`python` 和历史兼容的 `script` executor 都能执行；`builtin` 也通过同一个入口执行；`prompt` 类型只作为说明，不直接运行。

## 权限边界

1. 普通用户默认不应直接执行 `ScriptSkillToolPack.*`
2. 默认权限配置支持 `ScriptSkillToolPack.*` 这种通配符
3. 管理员可创建和调试 skill
4. 未 trusted 的远程脚本不应该直接进入定时任务
5. 任意代码修改、删除、远程下载能力不放进 v1 skill 管理工具

## 适合做成 Skill 的能力

优先放进 skill：

1. 网页抓取与清洗
2. 数据采集脚本
3. 项目诊断脚本
4. 格式转换
5. 可替换、可低耦合的实验能力

不优先放进 skill：

1. 登录、权限、审计、定时任务调度这类平台底座
2. 高频核心 Java 服务
3. 强事务、强权限、强一致性的业务逻辑
4. 必须长期稳定维护的核心 API
