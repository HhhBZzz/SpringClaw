---
name: 项目分析技能
displayName: Repo Inspector
description: 扫描当前工作区，定位实现文件、配置文件和关键代码片段
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: workspace
tier: core
inputHint: 传入 goal，自然语言描述你想分析的功能或类名
priority: 10
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - 项目分析
  - 找实现
  - 定位代码
  - 找文件
  - 工作区
triggerExamples:
  - 用 repo_inspector 帮我找 Spring AI 相关文件
  - 分析 ChatServiceImpl 的核心实现在哪
---

# Repo Inspector

定位当前工作区里的实现文件、配置文件和关键代码片段。
