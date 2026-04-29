---
name: 代码分析
displayName: Code Analysis
description: 分析项目结构、类、文件和实现位置，适合定位功能实现与阅读代码
version: 1.0.0
type: builtin
category: builtin
tier: core
inputHint: 优先使用 workspace/file/script 能力分析项目实现位置与代码结构；若已有明显证据，直接给出定位结论
priority: 10
enabled: true
agentVisible: true
toolPacks:
  - workspace
  - file
  - script
preferredMode: opar
contextPolicy: session-only
triggerKeywords:
  - 代码分析
  - 分析代码
  - 分析项目
  - 找实现
  - 定位代码
  - 看看类
  - 看看文件
triggerExamples:
  - 用代码分析分析 ChatServiceImpl
  - 帮我找这个功能在哪实现
---

# Code Analysis

遇到代码定位、实现查找、结构梳理类问题时，优先调 builtin 执行器做确定性分析，再根据需要调用 workspace/file/script 能力补证据。
