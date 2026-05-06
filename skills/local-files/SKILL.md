---
name: 本地授权文件
displayName: Local Files
description: 浏览、搜索和读取用户明确授权的本地电脑目录，适合查找简历、论文、文档和本机项目资料
version: 1.0.0
skillId: local-files
type: builtin
category: filesystem
tier: core
inputHint: 先列授权根目录，再按 root1/root2 搜索或读取；禁止读取授权目录外路径和敏感目录
priority: 9
enabled: true
agentVisible: true
toolPacks:
  - file
preferredMode: opar
contextPolicy: authorized-local-files
triggerKeywords:
  - 本地文件
  - 电脑文件
  - 授权文件
  - 授权目录
  - 本机文件
  - 桌面
  - 下载
  - 文档
  - 简历
  - 论文
triggerExamples:
  - 帮我在本地电脑授权文件里找一下简历相关文件
  - 读取 Documents 里的论文开题报告并总结
  - 搜索授权目录里包含 Spring AI 的文件
---

# Local Files

用于让 Agent 浏览用户明确授权的本地目录。

执行原则：
- 先调用 `listAuthorizedRoots` 确认当前授权根目录。
- 搜索未知文件时优先调用 `searchAuthorizedFiles`。
- 读取内容时使用 `readAuthorizedTextFile(rootRef, relativeFilePath)`。
- 不读取授权根目录之外的路径。
- 不读取 `.ssh`、`.gnupg`、Keychains、浏览器 Profile、`.env` 等敏感路径。
