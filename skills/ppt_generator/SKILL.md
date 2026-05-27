---
name: PPT 生成器
displayName: PPT Generator
description: 把文本、大纲或工作区文件生成 PPTX，本地输出到 data/skills/ppt_generator
type: python
entrypoint: scripts/run.py
category: office
tier: core
inputHint: 传入 text/content/goal 或 inputFile，可选 title、filename、slides、outputFile、dryRun
priority: 43
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - PPT
  - PPTX
  - 演示文稿
  - 生成幻灯片
triggerExamples:
  - 把这份大纲生成 PPT
  - 根据 notes.md 生成演示文稿
---

# PPT Generator

把文本、大纲或工作区文件生成 `.pptx`。默认输出到工作区 `data/skills/ppt_generator/`。

## Safety

- 只读取和写入当前 workspace 内的文件。
- 不自动安装 Python 依赖。
- 缺少 `python-pptx` 时返回 `missingDependency` 和安装建议。
