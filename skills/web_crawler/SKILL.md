---
name: 网页抓取技能
displayName: Web Crawler
description: 读取指定 URL 的网页标题与正文内容，适合抓取链接而不是只做搜索
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: web
tier: core
inputHint: 传入 goal，问题里直接带上 http(s) 链接
priority: 15
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - 抓取网页
  - 爬取网页
  - 读取网页
  - 读取链接
  - 网页正文
  - 页面正文
triggerExamples:
  - 读取这个网页 https://example.com
  - 帮我抓取这个链接的正文 https://example.com/docs
---

# Web Crawler

读取网页标题和正文，适合受控网页抓取。
