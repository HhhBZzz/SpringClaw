---
name: 办公文件工具
displayName: Office File Tools
description: 读取工作区内 txt/md/csv/json 文件，统计行数、字数、字符数，并可生成 Markdown 报告
type: python
entrypoint: scripts/run.py
category: office
tier: core
inputHint: 传入 inputFile/file，可选 action=summarize|stats|report、outputFile、dryRun
priority: 41
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - 办公文件
  - 文件统计
  - Markdown报告
  - CSV统计
  - JSON摘要
triggerExamples:
  - 统计这个 Markdown 文件的字数
  - 给这个 CSV 生成一个简短报告
---

# Office File Tools

轻量处理 workspace 内的 `.txt`、`.md`、`.csv`、`.json` 文件，返回摘要统计，或生成 Markdown 报告。

## Safety

- 只读取和写入当前 workspace 内的文件。
- 不接入账号，不调用第三方办公平台。
- 默认输出报告到工作区 `data/skills/office_file_tools/`。
