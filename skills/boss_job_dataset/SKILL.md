---
name: BOSS职位数据集
displayName: Boss Job Dataset
description: 解析本地保存的 BOSS 职位列表 HTML/CSV/JSON，并导出论文数据采集可用的清洗数据集
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: research
tier: core
inputHint: 传入 goal，并带上本地文件或目录路径，例如 data/boss/list.html 或 data/boss/raw/
priority: 18
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - BOSS直聘
  - 职位列表
  - 论文数据采集
  - 岗位数据清洗
  - 导出职位数据集
triggerExamples:
  - 用 boss_job_dataset 解析 data/boss/list.html
  - 帮我把 data/boss/raw/ 里的职位列表页面整理成论文数据集
---

# Boss Job Dataset

清洗本地职位列表页面，输出论文采集可用的数据集。
