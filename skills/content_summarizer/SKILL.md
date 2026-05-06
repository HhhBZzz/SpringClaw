---
name: 内容摘要
displayName: Content Summarizer
description: 对文本、工作区文件或网页内容做轻量抽取式摘要，参考 OpenClaw4J summarize/chat-summary
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: content
tier: core
inputHint: 传入 text、file 或 url，可选 length=short|medium|long
priority: 35
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - 摘要
  - 总结
  - 提炼
  - 文章
triggerExamples:
  - 总结这段文本
  - 帮我摘要 README.md
---

# Content Summarizer

本地内容摘要 skill，用于把文本、工作区文件或普通网页转成短摘要。

设计取舍：

- 不调用远程模型，避免和主 Agent 的模型链路重复。
- 文件读取限制在授权工作区内，避免越权读取。
- 网页抓取只做轻量文本提取，复杂爬虫继续交给专门 crawler skill。
