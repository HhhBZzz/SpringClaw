---
name: PDF 生成器
displayName: PDF Generator
description: 把文本、Markdown 或工作区文件生成 PDF，本地输出到 data/skills/pdf_generator
type: python
entrypoint: scripts/run.py
category: office
tier: core
inputHint: 传入 text/content/goal 或 inputFile，可选 title、filename、outputFile、dryRun
priority: 42
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - PDF
  - 生成PDF
  - 转成PDF
  - 文档生成
triggerExamples:
  - 把这段文字生成 PDF
  - 把 docs/report.md 转成 PDF
---

# PDF Generator

把纯文本、Markdown 或工作区内文件生成 PDF。默认输出到工作区 `data/skills/pdf_generator/`。

## Safety

- 只读取和写入当前 workspace 内的文件。
- 不自动安装 Python 依赖。
- 缺少 `reportlab` 时返回 `missingDependency` 和安装建议。
