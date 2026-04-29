---
name: 运行诊断技能
displayName: Runtime Probe
description: 查看本机 Java 进程、端口占用和常见启动信息，适合排查本地启动失败
version: 1.0.0
type: python
entrypoint: scripts/run.py
category: runtime
tier: core
inputHint: 传入 goal，可写端口号、启动失败现象或诊断目标
priority: 20
enabled: true
agentVisible: true
toolPacks:
  - script
preferredMode: simplified
contextPolicy: session-only
triggerKeywords:
  - 运行诊断
  - 端口占用
  - JVM
  - 启动失败
  - 排障
triggerExamples:
  - 用 runtime_probe 看看 18080 端口被谁占用了
  - 帮我诊断一下当前 JVM 运行状态
---

# Runtime Probe

用于本机 Java 运行状态和端口占用诊断。
