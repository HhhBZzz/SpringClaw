---
name: 加密货币价格
displayName: Crypto Price
description: 查询 BTC、ETH 等加密货币价格，参考 OpenClaw4J crypto-price
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: finance
tier: optional
inputHint: 传入 symbols，例如 BTC,ETH；dryRun=true 可离线测试
priority: 37
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - BTC
  - ETH
  - 加密货币
  - 币价
  - crypto
triggerExamples:
  - 查一下 BTC 和 ETH 价格
  - 当前加密货币市场怎么样
---

# Crypto Price

加密货币价格查询 skill。

设计取舍：

- 不依赖 `ccxt`，避免运行时自动安装第三方包。
- 默认使用 CoinGecko 公共 API 查询 `usd` 和 `cny`。
- `dryRun=true` 时只解析输入并返回计划，适合离线测试和定时任务预检。
