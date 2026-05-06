---
name: 系统状态
displayName: System Status
description: 查看本机 CPU、内存、磁盘和进程概览，参考 OpenClaw4J system-status
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: runtime
tier: core
inputHint: 传入 goal，可查看 CPU、内存、磁盘、进程数量
priority: 30
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - 系统状态
  - CPU
  - 内存
  - 磁盘
  - 进程
triggerExamples:
  - 查看系统状态
  - 当前电脑 CPU 和内存怎么样
---

# System Status

本地系统状态 skill，用于查看 CPU、内存、磁盘和进程概览。

设计取舍：

- 优先使用 Python 标准库，避免运行时自动安装依赖。
- 如果本机已经有 `psutil`，会使用更准确的指标。
- 没有 `psutil` 时自动降级到 `vm_stat`、`/proc/meminfo`、`ps`、`shutil.disk_usage` 等系统能力。
