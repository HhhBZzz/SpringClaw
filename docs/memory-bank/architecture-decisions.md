# Architecture Decisions

## Engine

当前不合并、不删除、不重命名 `AgentRuntimeEngine`、`AutonomousLoopEngine`、`OparLoopEngine`。

产品层用 `quick_answer`、`agent_analysis`、`execution_task` 解释模式，不把内部 engine 类名暴露给普通用户。

## Memory

Memory Bank 是当前非 RAG 记忆主线。

向量记忆可以保留为 fallback 或语义召回能力，但不应该作为项目长期记忆的唯一主线。

## Knowledge

Wiki.js / docs / README 属于 Project Knowledge，不属于用户 Long-term Memory。

项目知识应该单独建 Knowledge Source，避免污染用户记忆。

当前先使用 Markdown Knowledge Source review/list + snapshot preview + status 治理：只有 `status: active/approved` 的项目知识进入 snapshot，未审核、disabled、rejected 文档默认过滤；Runtime Console 展示来源状态、context impact、reviewedAt/reviewReason，并允许人工写入 review reason 调整状态；status 变更写入 MessageEvent 结构化审计事件，但不把 snapshot 自动注入运行时 prompt。

## Self Evolution

自进化不是让模型自动改代码或无限写记忆，而是从执行失败、反例和验证结果中提炼可审阅规则。

当前最小闭环：

`AgentRunTraceEvent failed -> AgentLearningService -> docs/memory-bank/agent-learnings.md -> MemoryBankService -> 下一轮 Context`

learning 条目先使用 `active/approved/disabled/rejected/superseded` 这类轻量状态做治理，不在当前阶段引入新的审批框架。

Obsidian / Wiki.js 后续应作为 Markdown knowledge source 接入，优先复用这个可审阅文件模型、status 治理和 snapshot preview，而不是直接扩成 RAG。
