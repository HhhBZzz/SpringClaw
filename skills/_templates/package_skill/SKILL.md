---
name: 示例技能
displayName: Example Skill
description: 演示 package skill 的最小结构
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: general
tier: utility
inputHint: 传入 goal
priority: 100
enabled: false
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

# Example Skill

这是一个最小 package skill 模板。
