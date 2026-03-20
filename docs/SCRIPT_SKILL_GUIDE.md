# Script Skill Guide

## 结论

当前项目新增/删除 `script skill` 已经比较轻：

1. 新增一个技能，通常只要两份文件
2. 删除一个技能，删掉这两份文件再 reload
3. 不需要改 `application.yml`
4. 不需要改 Java 主流程

前提是你新增的是“外挂脚本技能”，不是“核心 Java ToolPack”

## 当前项目里的技能结构

项目现在有两层技能：

1. Java ToolPack
作用：高频、强确定性、需要稳定控制的能力
例子：天气、新闻、汇率、系统命令、工作区检索

2. Script Skill
作用：外挂、实验性、诊断型、项目分析型能力
位置：`skills/*.py + skills/*.skill.json`

Script Skill 由下面两部分组成：

1. 目录扫描
文件：[ScriptSkillCatalogService.java](/Users/hanbingzheng/springclaw/src/main/java/com/openclaw/service/skill/script/ScriptSkillCatalogService.java)

2. 受控执行
文件：[ScriptSkillToolPack.java](/Users/hanbingzheng/springclaw/src/main/java/com/openclaw/tool/pack/ScriptSkillToolPack.java)

## 什么时候用 Script Skill

优先用 Script Skill 的情况：

1. 本地诊断
2. 项目分析
3. 小型自动化
4. 试验性能力
5. 未来可能删掉或替换的功能

不要先用 Script Skill 的情况：

1. 高频核心功能
2. 必须稳定命中的控制面
3. 需要很强权限治理的能力
4. 需要复杂 Tool 编排的核心链路

这类能力更适合直接写成 Java ToolPack。

## 新增一个 Skill

### 第一步：复制模板

模板目录：

- [example_skill.py](/Users/hanbingzheng/springclaw/skills/templates/example_skill.py)
- [example_skill.skill.json](/Users/hanbingzheng/springclaw/skills/templates/example_skill.skill.json)

复制到 `skills/` 根目录，并改成你的技能名：

1. `skills/my_skill.py`
2. `skills/my_skill.skill.json`

注意：

1. 文件名必须一致
2. `my_skill.py` 对应 `my_skill.skill.json`
3. 技能名默认取 `.py` 文件名

### 第二步：填写元数据

`my_skill.skill.json` 里最重要的字段：

1. `displayName`
用户可读名称

2. `category`
建议用现有类别之一：
- `workspace`
- `runtime`
- `weather`
- `debug`
- `general`

3. `description`
一句话说清做什么

4. `inputHint`
告诉模型应该传什么

5. `keywords`
命中关键词

6. `exampleQuestions`
示例问法

## Python Skill 的输入输出约定

运行时，Java 会这样调用：

```bash
python3 skills/my_skill.py '{"goal":"...","workspaceRoot":"...","skillName":"..."}'
```

你在脚本里应该做的事情：

1. 读取 `sys.argv[1]`
2. 解析 JSON
3. 从 `goal` 里拿自然语言任务
4. 输出纯文本结果到 stdout

环境变量会提供：

1. `OPENCLAW_WORKSPACE_ROOT`
2. `OPENCLAW_SCRIPT_ROOT`
3. `OPENCLAW_SKILL_NAME`

建议输出规则：

1. 先给结论
2. 再给证据或列表
3. 纯文本
4. 控制长度
5. 不要输出交互式提示

## 让系统识别到新 Skill

当前配置已经允许扫描全部脚本技能：

- [application.yml](/Users/hanbingzheng/springclaw/src/main/resources/application.yml#L168)

关键配置：

```yaml
openclaw:
  tools:
    script:
      enabled: true
      allowed-skills: ${OPENCLAW_SCRIPT_ALLOWED_SKILLS:*}
```

也就是说现在默认白名单是 `*`。

新增文件后，调用重载接口：

```http
POST /api/admin/manage/script-skills/reload
```

查看结果：

```http
GET /api/admin/manage/script-skills
```

对应接口：

- [AdminManageController.java](/Users/hanbingzheng/springclaw/src/main/java/com/openclaw/controller/ops/AdminManageController.java#L183)
- [AdminManageController.java](/Users/hanbingzheng/springclaw/src/main/java/com/openclaw/controller/ops/AdminManageController.java#L206)

## 删除一个 Skill

删除很直接：

1. 删除 `skills/my_skill.py`
2. 删除 `skills/my_skill.skill.json`
3. 调一次 reload 接口

如果只是暂时不想让它运行，也可以用白名单控制：

```bash
OPENCLAW_SCRIPT_ALLOWED_SKILLS=repo_inspector,runtime_probe
```

## 当前这套设计的优点

1. 新增快
2. 风险隔离
3. 不动主链路
4. 有元数据，模型和本地规则都能感知
5. 可热重载

## 当前这套设计的边界

1. 不是完全插件化
2. 不是所有 skill 都能自动高质量命中
3. 高频核心能力仍然更适合 Java ToolPack
4. 复杂控制逻辑不能只靠脚本 skill

## 建议的项目约束

以后新增 skill，按这条标准选：

1. 如果是实验性、小工具、诊断、项目分析
放 `skills/`

2. 如果是核心能力、强确定性、高频控制面
写成 Java ToolPack

## 最小检查清单

新增 skill 后，至少检查：

1. 文件名是否一致
2. `.skill.json` 是否合法 JSON
3. `reload` 后是否出现在 `/api/admin/manage/script-skills`
4. `goal` 输入是否能正常解析
5. 输出是否是短而稳定的纯文本
