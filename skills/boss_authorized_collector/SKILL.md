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

安全与合规边界：
- 仅在你已经获得网站/项目组/数据负责人明确授权时使用。
- 授权 Cookie/Header 不写入仓库文件；推荐通过 `BOSS_AUTH_COOKIE` 环境变量或受控 secrets 管理。
- 输入的 config、headers、local HTML 路径必须位于当前 workspace 内，脚本会拒绝路径穿越和越界符号链接。
- 输出只包含职位列表数据，不应写入 Cookie、Authorization 等凭据。
- 数据采集前应记录授权来源、采集范围、保留周期和删除策略。

示例配置使用：
- `cookieEnv: "BOSS_AUTH_COOKIE"` 表示从环境变量读取授权 Cookie。
- `headersPath`、`localHtmlFiles` 只允许指向 workspace 内的 `.json/.html/.htm` 文件。
