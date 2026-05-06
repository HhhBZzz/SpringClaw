---
name: 本地项目审查
displayName: Workspace Review
description: 审查当前本地项目源码、架构边界、风险点、冗余代码和建议阅读顺序
version: 1.0.0
skillId: workspace-review
type: builtin
category: workspace
tier: core
inputHint: 传入用户审查目标；只读取当前项目根目录，敏感配置只报告路径和值已隐藏
priority: 8
enabled: true
agentVisible: true
toolPacks:
  - workspace
  - file
  - script
preferredMode: opar
contextPolicy: workspace-only
triggerKeywords:
  - 审查项目
  - 项目审查
  - 审查源码
  - 源码审查
  - 架构审查
  - 代码审查
  - review 项目
  - review 代码
  - 冗余代码
  - 垃圾代码
  - 技术债
  - 安全风险
triggerExamples:
  - 请审查这个项目源码，看看架构是否合理
  - 帮我看看项目里有没有冗余垃圾代码
  - 评估当前 Agent 项目的模块边界和风险点
---

# Workspace Review

用于让 Agent 像本地代码助手一样读取当前工作区并输出项目审查报告。

执行原则：
- 只读当前项目根目录，不读取系统目录或其他项目。
- 跳过 `.git`、`target`、`node_modules`、`dist`、`build` 等依赖和生成产物。
- 遇到 `.env`、secret、password、token、api key 等敏感内容时，只报告文件和行号，不输出实际值。
- 输出应包含项目概览、技术栈、关键入口、风险发现和下一步阅读顺序。
