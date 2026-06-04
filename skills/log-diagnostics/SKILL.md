---
name: 日志诊断
displayName: Log Diagnostics
description: 分析日志、报错、堆栈和启动失败现象，适合排查运行时问题
version: 1.0.0
type: builtin
category: builtin
tier: core
inputHint: 优先解释最近错误与日志证据，必要时调用 runtime/workspace/script 能力辅助判断，不要空泛总结
priority: 20
enabled: true
agentVisible: true
toolPacks:
  - script
  - workspace
  - file
preferredMode: opar
contextPolicy: session-only
triggerKeywords:
  - 日志诊断
  - 分析日志
  - 分析报错
  - 看看报错
  - 堆栈分析
  - 启动失败
  - 端口占用
  - 排查异常
highConfidenceKeywords:
  - 日志诊断
  - 分析日志
  - 分析报错
  - 启动失败
  - 端口占用
  - 堆栈分析
  - 排查异常
triggerExamples:
  - 分析这个报错
  - 帮我看看这段日志怎么回事
---

# Log Diagnostics

遇到日志、报错、堆栈、启动失败类问题时，优先走 builtin 日志诊断执行器，再按需调用 runtime/workspace/script 能力补充证据。
