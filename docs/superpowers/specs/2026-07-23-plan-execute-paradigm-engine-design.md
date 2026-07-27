# Plan-Execute 范式引擎 — 设计

**Date:** 2026-07-23
**Status:** Design(brainstorming 产出,待 writing-plans)
**Parent:** 范式级切换地基(spec `2026-07-21-agent-paradigm-switching-foundation-design.md`,PR1-3 + ReAct 已在 main)

## 1. 目标

实现 **Plan-Execute 范式引擎**——先规划再执行:Plan 阶段 LLM 一次生成完整多步 plan,Execute 阶段逐步执行(每步手动工具循环),失败时 Replan。接入范式地基,使用户可选 `PLAN_EXECUTE` 范式。

这是范式可切换产品愿景的**第二个增量新范式引擎**(地基 spec §8.2,首个是 ReAct)。区别于 ReAct(每步自由推理,无预 plan):Plan-Execute **plan-driven**(先一次 plan 全部,再逐步执行)。

## 2. 现状

**范式地基(main)**:
- `AgentParadigm` 枚举,`PLAN_EXECUTE("先规划再执行")` 占位,`isImplemented()` 目前 `SINGLE_TURN/OPAR/AUTONOMOUS_LOOP/REACT` 才 true。
- `EngineSelector.select(ctx, paradigm)`、`ChatRequest/ChatContext.paradigm`、`RunState/RunEvent` 记 paradigm、timeline 透出。
- ReAct 已合 main(`ReActEngine implements StreamableAgentEngine`,`paradigm()=REACT`,手动循环为主路径)。

**ReAct 手动循环(main,复用基础)**:
ReAct 在 #56 重构后是手动循环为主(所有模型):LLM 输出 `Thought + Action` 文本 → 引擎手动执行工具(`executeExplicitAction`/`findActionLine`/`splitAction`/`findToolMethod`/`bindArguments`/`rawActionContent`/`stripMarkdownLinePrefix`,反射 invoke on CGLIB proxy → `ToolRuntimeAspect` 自动审计/tracker/TOOL_*)→ Observation 反馈 → 下一步。**Plan-Execute 的 Execute 阶段复用这套手动循环**。

**OparLoop PlanResult 参考**:`OparLoopEngine` 用 `BeanOutputConverter<PlanResult>` + `.responseEntity(PlanResult.class)` 做 LLM 结构化 plan 输出。Plan-Execute 的 Plan 阶段复用这个模式。

**DeepSeek 兼容已删(#56)**:全仓不再有 `DeepSeekChatCompatibility`/`supportsNativeToolCalling` 守卫。所有引擎直接用 `.tools()`(OparLoop/Simplified/ModelLed)或手动循环(ReAct)。Plan-Execute 同样不迁就(手动循环,模型无关)。

## 3. 方案

新建 `PlanExecuteEngine implements StreamableAgentEngine`,`paradigm()=PLAN_EXECUTE`。Plan 阶段 `BeanOutputConverter` 生成结构化多步 plan;Execute 阶段逐步手动工具循环(**复用 ReAct 的手动循环代码——提取为共享**);失败 Replan。流式 SSE + 假完成守护复用 AutonomousLoop 模式。

**关键:提取共享手动循环**:ReAct 的手动循环代码(`executeExplicitAction`/`findActionLine`/`splitAction`/`findToolMethod`/`bindArguments`/`rawActionContent`/`stripMarkdownLinePrefix`/`parseValue`/`splitArgs`/`ParsedAction`/`ToolMethod`)+ 反射(`getTargetClass`/`renderToolList` 的 @Tool scan)提取为共享工具类(`ExplicitToolExecutioner` + `ToolReflectionSupport`),ReAct 重构复用 + Plan-Execute 复用。一次提取,清 ReAct final review 的"反射重复第三消费者"Follow-up,避免 Plan-Execute 复制。

## 4. 设计

### 4.1 `PlanExecuteEngine` 类

- `implements StreamableAgentEngine`(SSE 流式)。
- `name()="plan-execute-loop"`、`paradigm()=PLAN_EXECUTE`、`priority()=6`(react-loop=4 之后,simplified=10 之前)。
- `supports(ChatContext ctx)`:返回 `ctx.paradigm() == AgentParadigm.PLAN_EXECUTE`。
- 构造函数复用 ReAct/AutonomousLoop 的 11 bean 依赖 + `@Value("${springclaw.chat.max-replan:2}")` maxReplan。

### 4.2 Plan 阶段(一次 LLM 调用)

`runPlan(ctx, ...)`:LLM 生成结构化多步 plan:
- 用 `BeanOutputConverter<List<PlanStep>>`(参考 `OparLoopEngine.runPlan` L298-353 的 `PlanResult` 模式)。
- `PlanStep` record:`{String stepText}`(高层步骤描述,如"1. 搜索 X 2. 读取 Y 3. 综合")。
- 系统 prompt 要求 LLM 分解问题为有序步骤,每步是一个可执行子目标。
- 经 `ModelCallExecutor.executeChat`(emit `MODEL_CALLED` + failover)。

### 4.3 Execute 阶段(逐步,手动循环复用 ReAct)

`runExecute(ctx, plan, emitter, ...)`:遍历 plan 步骤,每步:
1. LLM 据 `stepText` + 历史(plan 进度 + 前 step 的 Observation)→ 输出 `Thought + Action` 文本(系统 prompt 要求执行当前 plan 步)。
2. `ExplicitToolExecutioner.execute(thought, tools, requestId)`(提取自 ReAct):`findActionLine` 判定 → `splitAction`/`findToolMethod`/`bindArguments` → 反射 invoke on CGLIB proxy → via `ToolRuntimeAspect` 审计/tracker/TOOL_*。
3. Observation = 工具结果 → 进历史 → 下一个 plan 步。
4. 工具范围:`ToolOrchestrator.selectAutonomousTools(channel, userId, decision)`(intent/riskLevel 限定)。
5. 每步经 `ToolExecutionContextHolder.open(toolContext)` scope(权限/审计/TOOL_* 生效)+ `AutonomousExecutionTracker`(假完成守护)。

plan 细化到每步 ~1 工具调用(Plan prompt 引导 LLM 分解到工具级步骤)。

### 4.4 Replan

某 plan 步失败(工具异常/无进展/LLM 判无法继续):
1. LLM 带失败反馈(失败步 + 原因)重新 `runPlan` → 新 plan。
2. 重 Execute(从新 plan 开头,或从失败步之后——writing-plans 定;推荐从头,简单)。
3. `max-replan=2` 限制(达上限降级返回当前最佳结果)。

### 4.5 安全(AutonomousLoop 模式,复用)

- 工具范围:`selectAutonomousTools` 按 intent/riskLevel。
- 假完成守护:`AutonomousExecutionTracker` + `satisfiesCompletionCondition`/`renderFakeCompletionRejection`(写/副作用任务验证工具证据)。Execute 完成 plan 后,write 类任务校验证据才完成。

### 4.6 流式 SSE(复用 ReAct/AutonomousLoop)

`stream(...)` 模仿 `AutonomousLoopEngine` L131-167 / `ReActEngine` stream:start trace → runPlanExecute → answerChunks → persist → releaseLockOnce → completeEmitter;catch → fallback。每步 emit Thought/Action/Observation trace(教学透明)。

### 4.7 timeline

- emit `MODEL_CALLED`(Plan + Execute 每步 LLM)+ `TOOL_*`(Execute 工具,经 Aspect 自动)。`RunEvent` 带 `paradigm=PLAN_EXECUTE`(PR3 event() 焊接)。
- 前端(codex):按 paradigm=PLAN_EXECUTE 语义化(plan/execute/replan 阶段)。

### 4.8 接入地基

1. `AgentParadigm.isImplemented()` 加 `PLAN_EXECUTE`。
2. `EngineSelector.LEGACY_RANK` 注册 `"plan-execute-loop"`(如 80,不与 react-loop=70 冲突)。
3. `ChatServiceImpl`:Plan-Execute 走 `StreamableAgentEngine.stream()` 分发(已有)。
4. `ChatRoutingPolicyService.paradigmToExecutionMode`:PLAN_EXECUTE→`"opar"`(已由"其余→opar"覆盖;supports 不依赖 executionMode)。

## 5. 不做(YAGNI)

- 并排对比 UI(后续子项目)。
- Execute 每步子 ReAct 多工具循环(plan 细化到每步 1 工具,而非每步子 ReAct)。
- Plan 的工具级精确调用(PlanStep 只有高层 stepText,Execute LLM 选工具)。
- 框架级切换 / 范式矩阵(愿景 2/3 阶段)。
- Reflexion / 多智能体(各自独立 spec)。

## 6. 向后兼容

- PLAN_EXECUTE 之前是占位(`isImplemented()` false,选时抛"未实现")。加 `isImplemented` 后,用户显式选 PLAN_EXECUTE 才触发 PlanExecuteEngine;不选时现有引擎/路由零变更。
- PlanExecuteEngine 是新类,不影响现有引擎。
- 提取共享(`ExplicitToolExecutioner`/`ToolReflectionSupport`)重构 ReAct,但 ReAct 行为不变(手动循环逻辑相同,只是移到共享类)。ReAct 测试调整(用共享类)。

## 7. 验收

- 用户可选 PLAN_EXECUTE(API `paradigm=PLAN_EXECUTE` / 前端选择器),run 由 PlanExecuteEngine 执行。
- Plan 阶段:LLM 生成结构化多步 plan(`List<PlanStep>`)。
- Execute 阶段:逐步手动工具循环(复用 ReAct 的 ExplicitToolExecutioner),Observation 反馈,plan 逐步推进。
- Replan:某步失败 → 重新 plan → 重 Execute(max-replan=2)。
- 写/副作用任务假完成守护(无证据不完成)。
- timeline 流式 emit MODEL_CALLED/TOOL_*,带 paradigm=PLAN_EXECUTE。
- 不选 PLAN_EXECUTE 时,现有行为不变(全量测试绿)。
- ReAct 重构(用共享 ExplicitToolExecutioner)后,ReAct 行为不变(ReAct 测试绿)。

## 8. 实现切片建议(writing-plans 细化)

- **切片 1**(提取共享 + ReAct 重构):把 ReAct 的手动循环代码(`executeExplicitAction`/`findActionLine`/`splitAction`/`findToolMethod`/`bindArguments`/`rawActionContent`/`stripMarkdownLinePrefix`/`parseValue`/`splitArgs`/`ParsedAction`/`ToolMethod`)+ 反射(`getTargetClass`/`renderToolList`)提取为 `ExplicitToolExecutioner` + `ToolReflectionSupport`。ReAct 重构用共享(行为不变,测试调整)。
- **切片 2**(PlanExecuteEngine 骨架 + 接入):`PlanExecuteEngine implements StreamableAgentEngine`(name/paradigm/priority/supports/execute 占位/stream 占位)+ `isImplemented` 加 PLAN_EXECUTE + `LEGACY_RANK` 注册 + 单测。
- **切片 3**(Plan 阶段):`runPlan`(`BeanOutputConverter<List<PlanStep>>`)+ `PlanStep` record + prompt + 单测。
- **切片 4**(Execute 阶段):`runExecute`(遍历 plan,每步手动循环复用 `ExplicitToolExecutioner`)+ Observation 反馈 + 假完成守护 + 流式 SSE + Replan + 单测。
- **切片 5**(结果 + trace):`ChatExecutionResult` + `resolveFinalAnswer` + AgentStep/trace + 全量。

---

## 风险与注意

- **提取共享重构 ReAct**:切片 1 把 ReAct 手动循环移到共享类。ReAct 行为须不变(手动循环逻辑相同)。ReAct 测试调整(用共享类)+ 全量绿。这是前置(Plan-Execute 复用)。
- **BeanOutputConverter List 泛型**:`BeanOutputConverter<List<PlanStep>>` 的 Spring AI 支持(参考 OparLoop PlanResult 的单对象, List 可能需 wrapper record 如 `Plan{List<PlanStep> steps}`)。writing-plans 确认。
- **Replan 从头 vs 从失败步**:推荐从头(简单),writing-plans 定。
- **碰引擎核心 + 流式 SSE**:PlanExecuteEngine 新引擎(不改正文现有),但 implements StreamableAgentEngine 需正确 SSE 生命周期(参考 ReAct/AutonomousLoop)。每切片全量测试(带全套 env)。
- **shell 空 MYSQL_* env**:全量测试命令必须显式传全套 env。
