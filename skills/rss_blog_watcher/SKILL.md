---
name: RSS 博客观察
displayName: RSS Blog Watcher
description: 管理 RSS/Atom 订阅并扫描最新文章，参考 OpenClaw4J blogwatcher
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: content
tier: core
inputHint: 传入 action=list|add|remove|scan，add 需要 name 和 url
priority: 36
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - RSS
  - 博客
  - 订阅
  - 最新文章
triggerExamples:
  - 查看我订阅了哪些博客
  - 扫描 RSS 最新文章
---

# RSS Blog Watcher

本地 RSS/Atom 订阅 skill，用于保存博客订阅并扫描最新文章。

支持动作：

- `list`：列出订阅。
- `add`：添加订阅，需要 `name` 和 `url`。
- `remove`：移除订阅，需要 `name`。
- `scan`：扫描订阅源最新文章，可选 `limit`。

订阅数据保存在本 skill 的 `data/blogs.json`，不写入普通聊天历史。
