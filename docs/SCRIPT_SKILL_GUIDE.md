# Script Skill Guide

## 结论

当前项目的可执行 skill 已统一到 package 结构：

1. 一个 skill = 一个目录
2. 目录里至少有一个 `SKILL.md`
3. Python 脚本放在 `scripts/run.py`
4. Java 主链路不再以 `skills/*.py + *.skill.json` 为 canonical 结构

现在的标准位置：

- `skills/packages/<skillId>/SKILL.md`
- `skills/packages/<skillId>/scripts/run.py`

## 当前项目里的 skill 层次

项目现在统一按 package skill 管理，主要差别在执行器类型：

1. package builtin skill
作用：高频、强确定性、平台内建能力
例子：`code-analysis`、`log-diagnostics`、`web-crawl`
说明：定义也在 `skills/packages/<skillId>/SKILL.md`，只是执行仍由 Java 内建执行器负责。

2. package script skill
作用：外挂、实验性、诊断型、采集型能力
位置：`skills/packages/<skillId>/`
说明：定义在 `SKILL.md`，执行器通常是 Python `scripts/run.py`

package script skill 的发现和执行分别由：

- [ScriptSkillCatalogService.java](/Users/hanbingzheng/springclaw/src/main/java/com/openclaw/service/skill/script/ScriptSkillCatalogService.java)
- [ScriptSkillExecutorService.java](/Users/hanbingzheng/springclaw/src/main/java/com/openclaw/service/skill/script/ScriptSkillExecutorService.java)
- [ScriptSkillToolPack.java](/Users/hanbingzheng/springclaw/src/main/java/com/openclaw/tool/pack/ScriptSkillToolPack.java)

Markdown / ClawHub 导入 skill 也统一落到：

- `skills/packages/<slug>/SKILL.md`

对应服务：

- [MarkdownSkillCatalogService.java](/Users/hanbingzheng/springclaw/src/main/java/com/openclaw/service/skill/markdown/MarkdownSkillCatalogService.java)

统一 skill 定义聚合在：

- [BuiltinSkillCatalogService.java](/Users/hanbingzheng/springclaw/src/main/java/com/openclaw/service/skill/impl/BuiltinSkillCatalogService.java)
- [ScriptSkillCatalogService.java](/Users/hanbingzheng/springclaw/src/main/java/com/openclaw/service/skill/script/ScriptSkillCatalogService.java)
- [SkillRegistryService.java](/Users/hanbingzheng/springclaw/src/main/java/com/openclaw/service/skill/impl/SkillRegistryService.java)

## package skill 目录结构

最小结构：

```text
skills/packages/my_skill/
├── SKILL.md
└── scripts/
    └── run.py
```

可选结构：

```text
skills/packages/my_skill/
├── SKILL.md
├── scripts/
│   └── run.py
├── templates/
├── references/
└── assets/
```

模板目录：

- [SKILL.md](/Users/hanbingzheng/springclaw/skills/templates/package_skill/SKILL.md)
- [run.py](/Users/hanbingzheng/springclaw/skills/templates/package_skill/scripts/run.py)

## SKILL.md 里最重要的字段

当前 SpringClaw 认这些字段：

```md
---
name: 示例技能
description: 说明这个 skill 是干什么的
version: 1.0.0
metadata:
  openclaw:
    springclaw:
      skillId: example_skill
      executor:
        type: python
        entrypoint: scripts/run.py
      category: general
      tier: utility
      inputHint: 传入 goal
      priority: 100
      agentVisible: true
      toolPacks:
        - script
      preferredMode: simplified
      contextPolicy: session-only
      triggerKeywords:
        - 示例技能
      triggerExamples:
        - 用 example_skill 做个演示
---
```

最关键的是：

1. `skillId`
2. `executor.type`
3. `executor.entrypoint`
4. `category`
5. `triggerKeywords`
6. `triggerExamples`

当前支持的执行器类型：

1. `builtin`
2. `python`
3. `node`（结构已预留，执行链待补齐）
4. `prompt`

## Python skill 输入输出约定

运行时会调用：

```bash
python3 skills/packages/my_skill/scripts/run.py '{"goal":"...","workspaceRoot":"...","skillName":"..."}'
```

环境变量会提供：

1. `OPENCLAW_WORKSPACE_ROOT`
2. `OPENCLAW_SCRIPT_ROOT`
3. `OPENCLAW_SKILL_ROOT`
4. `OPENCLAW_SKILL_NAME`

建议输出规则：

1. 先给结论
2. 再给证据或列表
3. 输出纯文本
4. 控制长度
5. 不输出交互式提示

## 新增一个 skill

### 第一步：复制模板

复制：

- [SKILL.md](/Users/hanbingzheng/springclaw/skills/templates/package_skill/SKILL.md)
- [run.py](/Users/hanbingzheng/springclaw/skills/templates/package_skill/scripts/run.py)

到：

1. `skills/packages/my_skill/SKILL.md`
2. `skills/packages/my_skill/scripts/run.py`

### 第二步：填写元数据

重点填：

1. `skillId`
2. `name`
3. `description`
4. `category`
5. `inputHint`
6. `triggerKeywords`
7. `triggerExamples`

### 第三步：重载并查看

```http
POST /api/admin/manage/script-skills/reload
GET /api/admin/manage/script-skills
GET /api/admin/manage/skills/registry
```

## 删除一个 skill

删除目录即可：

1. 删除 `skills/packages/my_skill/`
2. 调一次 reload 接口

## 当前配置

新配置统一到：

```yaml
openclaw:
  skills:
    enabled: true
    root: ${user.dir}/skills/packages
    allowed-skills: "*"
    runners:
      python-command: python3
    execution:
      timeout-seconds: 8
      max-output-chars: 3000
```

## 适合放 package skill 的能力

优先放 package skill 的情况：

1. 本地诊断
2. 项目分析
3. 网页抓取
4. 数据采集与清洗
5. 试验性能力

不要先放 package skill 的情况：

1. 高频核心控制面
2. 必须强确定命中的平台能力
3. 需要复杂权限治理的底座逻辑

这些更适合做成 `executor.type=builtin` 的 package skill，或者继续留在底层 ToolPack。
