---
name: 网页抓取
displayName: Web Crawl
description: 读取指定 URL 的网页正文、标题和主要内容，适合让 agent 真正打开链接而不是只做关键词搜索
version: 1.0.0
type: builtin
category: builtin
tier: core
inputHint: 遇到带明确 URL 的网页读取、抓取、正文提取请求时，优先使用受控 Python web_crawler skill；不要退回 Java 抓取正文
priority: 25
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
  - 提取正文
  - 打开链接
  - 总结网页
highConfidenceRequiresUrl: true
highConfidenceKeywords:
  - 抓取
  - 爬取
  - 读取
  - 打开
  - 提取
  - 总结
  - 网页
  - 页面
  - 链接
  - 网址
  - url
  - 正文
triggerExamples:
  - 读取这个网页 https://example.com
  - 帮我抓取这个链接的正文
---

# Web Crawl

遇到网页正文抓取类请求时，优先走 builtin 网页抓取执行器，再由它调受控 Python `web_crawler` skill 完成真实抓取。
