---
name: BOSS授权采集器
displayName: Boss Authorized Collector
description: 使用授权 headers/cookie 采集 BOSS 职位列表页，并导出职位数据集
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: research
tier: core
inputHint: 传入 goal，并带上 config JSON 路径，或直接带本地 HTML 文件路径
priority: 16
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - BOSS授权采集
  - 职位列表采集
  - 解析职位列表HTML
  - 导出职位数据
triggerExamples:
  - 用 boss_authorized_collector 运行 data/boss/boss_collect_config.json
---

# Boss Authorized Collector

用于授权采集 BOSS 职位列表页，并导出结构化数据集。
