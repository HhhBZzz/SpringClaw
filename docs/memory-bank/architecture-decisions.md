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
