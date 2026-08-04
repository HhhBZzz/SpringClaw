/**
 * 7 范式内置 demo 蓝图
 *
 * 节点坐标 0..100 舞台百分比；Java 定位/代码片段沿用 agent-trace-preview.html 的真实内容。
 * 每个范式一种拓扑形状族：
 *   REACT/REFLECTION/AUTONOMOUS_LOOP = 环(loop) ｜ OPAR/SINGLE_TURN = 线性(flow)
 *   PLAN_EXECUTE = 线性+replan 分叉 ｜ MULTI_AGENT = hub-spoke(并行 spawn/aggregate)
 */
import type { AgentParadigm } from '../../../types';
import type { ParadigmBlueprint } from '../types';

export const PARADIGM_ACCENTS: Record<AgentParadigm, string> = {
  SINGLE_TURN: '#9da6b4',
  OPAR: '#a371f7',
  AUTONOMOUS_LOOP: '#ff5c5c',
  REACT: '#58a6ff',
  PLAN_EXECUTE: '#ff7a59',
  REFLECTION: '#d29922',
  MULTI_AGENT: '#38d979'
};

export const PARADIGM_BLUEPRINTS: Record<AgentParadigm, ParadigmBlueprint> = {
  // ───────────────────────── REACT · Thought→Action→Observation 环 ─────────────────────────
  REACT: {
    paradigm: 'REACT',
    label: 'ReAct',
    description: 'Thought-Action-Observation 推理循环',
    color: PARADIGM_ACCENTS.REACT,
    nodes: [
      { id: 't1', kind: 'thought', label: 'Thought #1', x: 13, y: 34, status: 'idle',
        detail: '用户要查项目 Agent 链路。需要搜索代码理解结构。',
        loc: 'ReActEngine.java:294',
        code: 'explicitToolExecutioner.execute(thought, tools, reqId) // 手动循环' },
      { id: 'a1', kind: 'action', label: 'Action · search', x: 40, y: 16, status: 'idle',
        detail: '定位 Agent 入口 → 调 search 搜索 "AgentEngine implements"。',
        loc: 'ExplicitToolExecutioner.java:40',
        code: 'findActionLine(thought) → invokeTool(actionLine) // via CGLIB proxy' },
      { id: 'o1', kind: 'observation', label: 'Observation · 6 结果', x: 67, y: 34, status: 'idle',
        detail: 'AgentEngine + 6 实现 + ReAct/Plan-Execute/Reflexion/Multi-Agent。',
        loc: 'ToolRuntimeAspect.java:130',
        code: 'coordinator.toolSucceeded(runId, at) // emit TOOL_SUCCEEDED' },
      { id: 't2', kind: 'thought', label: 'Thought #2', x: 40, y: 60, status: 'idle',
        detail: '已找到引擎清单,无更多 Action → 产出最终答案。',
        loc: 'ReActEngine.java:328',
        code: 'if (!hasToolCall) return finalResult(...) // 终止' },
      { id: 'f', kind: 'final', label: '最终答案', x: 84, y: 70, status: 'idle',
        detail: 'Controller → ChatServiceImpl → EngineSelector.select(ctx, paradigm) → 7 引擎。',
        loc: 'ReActEngine.java:492',
        code: 'return new ChatExecutionResult(observe, plan, action, reflect, true)' }
    ],
    edges: [
      { id: 'e1', from: 't1', to: 'a1', kind: 'flow' },
      { id: 'e2', from: 'a1', to: 'o1', kind: 'flow' },
      { id: 'e3', from: 'o1', to: 't2', kind: 'loop' },
      { id: 'e4', from: 't2', to: 'f', kind: 'flow' }
    ],
    frames: [
      { edgeIds: [], nodeIds: ['t1'], phase: 'THOUGHT #1' },
      { edgeIds: ['e1'], nodeIds: ['a1'], phase: 'ACTION · search' },
      { edgeIds: ['e2'], nodeIds: ['o1'], phase: 'OBSERVATION · 6 结果' },
      { edgeIds: ['e3'], nodeIds: ['t2'], phase: 'THOUGHT #2 · loop', note: 'Observation 回灌历史,再次推理' },
      { edgeIds: ['e4'], nodeIds: ['f'], phase: 'FINAL' }
    ]
  },

  // ───────────────────────── OPAR · Observe→Plan→Act→Reflect 线性 ─────────────────────────
  OPAR: {
    paradigm: 'OPAR',
    label: 'OPAR',
    description: 'Observe-Plan-Act-Reflect 深度链路',
    color: PARADIGM_ACCENTS.OPAR,
    nodes: [
      { id: 'o', kind: 'observe', label: 'Observe 观察', x: 11, y: 34, status: 'idle',
        detail: '组装上下文 + 记忆召回,观察用户意图(深度分析)。',
        loc: 'OparLoopEngine.java:298', code: 'commit(current, copy(...), CONTEXT_READY, at)' },
      { id: 'p', kind: 'plan', label: 'Plan 规划', x: 37, y: 20, status: 'idle',
        detail: 'LLM 生成结构化 PlanResult(CONTINUE/READY + steps)。',
        loc: 'OparLoopEngine.java:320',
        code: 'executeChat(..., "plan", ...).responseEntity(PlanResult.class)' },
      { id: 'a', kind: 'act', label: 'Act 执行(.tools())', x: 63, y: 34, status: 'idle',
        detail: 'Spring AI .tools() 挂载,LLM 自主调工具(原生 tool-calling)。',
        loc: 'OparLoopEngine.java:401', code: 'requestSpec = requestSpec.tools(tools)' },
      { id: 'r', kind: 'reflect', label: 'Reflect 反思', x: 88, y: 20, status: 'idle',
        detail: '评估结果,plan.ready() → 终止。',
        loc: 'OparLoopEngine.java:230', code: 'if (plan.ready()) break' }
    ],
    edges: [
      { id: 'e1', from: 'o', to: 'p', kind: 'flow' },
      { id: 'e2', from: 'p', to: 'a', kind: 'flow' },
      { id: 'e3', from: 'a', to: 'r', kind: 'flow' },
      { id: 'e4', from: 'r', to: 'o', kind: 'loop', cp: { x: 50, y: 82 } }
    ],
    frames: [
      { edgeIds: [], nodeIds: ['o'], phase: 'OBSERVE' },
      { edgeIds: ['e1'], nodeIds: ['p'], phase: 'PLAN' },
      { edgeIds: ['e2'], nodeIds: ['a'], phase: 'ACT · .tools()' },
      { edgeIds: ['e3'], nodeIds: ['r'], phase: 'REFLECT', note: 'plan.ready() → 终止' }
    ]
  },

  // ───────────────── PLAN_EXECUTE · 线性 + replan 分叉 ──────────────
  PLAN_EXECUTE: {
    paradigm: 'PLAN_EXECUTE',
    label: '规划执行',
    description: '先规划全部 + 逐步执行 + Replan',
    color: PARADIGM_ACCENTS.PLAN_EXECUTE,
    nodes: [
      { id: 'p', kind: 'plan', label: 'Plan 分解(3 步)', x: 9, y: 30, status: 'idle',
        detail: 'Coordinator 一次生成 List<PlanStep>(搜索/分析/验证)。',
        loc: 'PlanExecuteEngine.java:198', code: 'beanOutputConverter.convert(text).steps()' },
      { id: 'x1', kind: 'execute', label: 'step 1 · search', x: 31, y: 16, status: 'idle',
        detail: '逐步执行,LLM 据 stepText 选工具。',
        loc: 'ExplicitToolExecutioner.java:40', code: 'explicitToolExecutioner.execute(thought, tools, reqId)' },
      { id: 'x2', kind: 'execute', label: 'step 2 · analyze', x: 53, y: 16, status: 'idle',
        detail: 'Observation 反馈进 history,继续下一步。',
        loc: 'PlanExecuteEngine.java:296', code: 'history += buildStepHistory(step, thought, obs)' },
      { id: 'rp', kind: 'replan', label: 'Replan · step 失败', x: 53, y: 54, status: 'idle',
        detail: '带反馈重新 plan(max-replan=2)。',
        loc: 'PlanExecuteEngine.java:361', code: 'plan = runPlan(ctx, client, tools, "上次失败:...")' },
      { id: 'x3', kind: 'execute', label: 'step 3 · verify', x: 75, y: 38, status: 'idle',
        detail: '重执行验证步骤,通过。',
        loc: 'PlanExecuteEngine.java:296', code: 'history += buildStepHistory(step, thought, obs)' },
      { id: 'ag', kind: 'aggregate', label: 'Coordinator 聚合', x: 92, y: 66, status: 'idle',
        detail: '所有步完成,LLM 综合结果 → 最终答案。',
        loc: 'PlanExecuteEngine.java:320', code: 'String answer = aggregate(ctx, results, client)' }
    ],
    edges: [
      { id: 'e1', from: 'p', to: 'x1', kind: 'flow' },
      { id: 'e2', from: 'x1', to: 'x2', kind: 'flow' },
      { id: 'e3', from: 'x2', to: 'rp', kind: 'replan' },
      { id: 'e4', from: 'rp', to: 'x3', kind: 'flow' },
      { id: 'e5', from: 'x3', to: 'ag', kind: 'flow' }
    ],
    frames: [
      { edgeIds: [], nodeIds: ['p'], phase: 'DECOMPOSE · 3 步' },
      { edgeIds: ['e1'], nodeIds: ['x1'], phase: 'EXECUTE · step 1' },
      { edgeIds: ['e2'], nodeIds: ['x2'], phase: 'EXECUTE · step 2' },
      { edgeIds: ['e3'], nodeIds: ['rp'], phase: 'REPLAN', note: 'step 失败 → 带反馈重规划' },
      { edgeIds: ['e4'], nodeIds: ['x3'], phase: 'EXECUTE · step 3' },
      { edgeIds: ['e5'], nodeIds: ['ag'], phase: 'AGGREGATE' }
    ]
  },

  // ───────────────────── REFLECTION · 执行-反思-改进重试 环 ─────────────────────
  REFLECTION: {
    paradigm: 'REFLECTION',
    label: '反思',
    description: '执行-反思-改进重试(verbal RL)',
    color: PARADIGM_ACCENTS.REFLECTION,
    nodes: [
      { id: 'a1', kind: 'attempt', label: 'Attempt 1', x: 13, y: 30, status: 'idle',
        detail: 'LLM 据 memory(空)执行单次工具。',
        loc: 'ReflexionEngine.java:267', code: 'callLlmForAttempt(ctx, memory, tools, client, reqId)' },
      { id: 'r1', kind: 'reflect', label: 'Reflect 1 · 未达成', x: 38, y: 16, status: 'idle',
        detail: 'lesson:需更精确搜索。memory 累积。',
        loc: 'ReflexionEngine.java:283', code: 'memory += "尝试 1 反思: " + reflection.lesson()' },
      { id: 'a2', kind: 'attempt', label: 'Attempt 2 · memory', x: 64, y: 30, status: 'idle',
        detail: 'LLM 据 memory(含 lesson 1)改进执行。',
        loc: 'ReflexionEngine.java:267', code: '// memory 闭环 — verbal RL' },
      { id: 'r2', kind: 'reflect', label: 'Reflect 2 · 达成', x: 64, y: 60, status: 'idle',
        detail: 'success=true + 守护通过 → 终止。',
        loc: 'ReflexionEngine.java:311',
        code: 'if (success && tracker.satisfiesCompletionCondition(risk)) return finalResult(...)' },
      { id: 'f', kind: 'final', label: '改进后答案', x: 88, y: 34, status: 'idle',
        detail: 'memory 累积反思,迭代逼近目标 → 最终答案。',
        loc: 'ReflexionEngine.java:311', code: 'return finalResult(memory, answer, true)' }
    ],
    edges: [
      { id: 'e1', from: 'a1', to: 'r1', kind: 'flow' },
      { id: 'e2', from: 'r1', to: 'a2', kind: 'flow' },
      { id: 'e3', from: 'a2', to: 'r2', kind: 'loop' },
      { id: 'e4', from: 'r2', to: 'f', kind: 'flow' }
    ],
    frames: [
      { edgeIds: [], nodeIds: ['a1'], phase: 'ATTEMPT 1' },
      { edgeIds: ['e1'], nodeIds: ['r1'], phase: 'REFLECT 1', note: '未达成 · lesson 累积' },
      { edgeIds: ['e2'], nodeIds: ['a2'], phase: 'ATTEMPT 2 · memory' },
      { edgeIds: ['e3'], nodeIds: ['r2'], phase: 'REFLECT 2', note: 'success=true · 守护通过' },
      { edgeIds: ['e4'], nodeIds: ['f'], phase: 'FINAL' }
    ]
  },

  // ───────────────────── MULTI_AGENT · Coordinator-Worker 并行 hub-spoke ─────────────────────
  MULTI_AGENT: {
    paradigm: 'MULTI_AGENT',
    label: '多智能体',
    description: 'Coordinator-Worker 并行协作',
    color: PARADIGM_ACCENTS.MULTI_AGENT,
    nodes: [
      { id: 'c', kind: 'decompose', label: 'Coordinator 分解(3)', x: 12, y: 42, status: 'idle',
        detail: 'TaskDecomposition → 3 子任务并行。',
        loc: 'MultiAgentEngine.java:276', code: 'List<SubTask> subTasks = decompose(ctx, tools, client)' },
      { id: 'w1', kind: 'worker', label: 'Worker 1 · 搜索', x: 42, y: 16, status: 'idle',
        detail: 'CompletableFuture + ExplicitToolExecutioner。',
        loc: 'MultiAgentEngine.java:299', code: 'CompletableFuture.supplyAsync(() -> runWorker(ctx, t, tools, ...))' },
      { id: 'w2', kind: 'worker', label: 'Worker 2 · 分析', x: 42, y: 42, status: 'idle',
        detail: '共享 AutonomousExecutionTracker(并发安全)。',
        loc: 'MultiAgentEngine.java:387', code: 'ToolExecutionContextHolder.setTracker(sharedTracker) // 各线程' },
      { id: 'w3', kind: 'worker', label: 'Worker 3 · 验证', x: 42, y: 68, status: 'idle',
        detail: 'allOf().join() 等所有完成。',
        loc: 'MultiAgentEngine.java:303', code: 'CompletableFuture.allOf(futures).join()' },
      { id: 'ag', kind: 'aggregate', label: 'Coordinator 聚合', x: 86, y: 42, status: 'idle',
        detail: 'LLM 综合 3 worker 结果 → 最终答案。',
        loc: 'MultiAgentEngine.java:320', code: 'String answer = aggregate(ctx, results, client)' }
    ],
    edges: [
      { id: 's1', from: 'c', to: 'w1', kind: 'spawn' },
      { id: 's2', from: 'c', to: 'w2', kind: 'spawn' },
      { id: 's3', from: 'c', to: 'w3', kind: 'spawn' },
      { id: 'g1', from: 'w1', to: 'ag', kind: 'aggregate' },
      { id: 'g2', from: 'w2', to: 'ag', kind: 'aggregate' },
      { id: 'g3', from: 'w3', to: 'ag', kind: 'aggregate' }
    ],
    frames: [
      { edgeIds: [], nodeIds: ['c'], phase: 'DECOMPOSE · 3 子任务' },
      { edgeIds: ['s1', 's2', 's3'], nodeIds: ['w1', 'w2', 'w3'], phase: 'WORKERS · 并行', note: 'CompletableFuture 三路并行' },
      { edgeIds: ['g1', 'g2', 'g3'], nodeIds: ['ag'], phase: 'AGGREGATE' }
    ]
  },

  // ───────────────────── AUTONOMOUS_LOOP · 自主多步循环(写/副作用) ─────────────────────
  AUTONOMOUS_LOOP: {
    paradigm: 'AUTONOMOUS_LOOP',
    label: '自主循环',
    description: '自主多步循环(写/副作用任务)',
    color: PARADIGM_ACCENTS.AUTONOMOUS_LOOP,
    nodes: [
      { id: 'g', kind: 'goal', label: 'Goal 目标', x: 10, y: 40, status: 'idle',
        detail: '确定任务目标(riskLevel = write / side_effect)。',
        loc: 'AutonomousLoopEngine.java:142', code: 'risk = riskClassifier.classify(goal); guard.allow(risk)' },
      { id: 's1', kind: 'step', label: 'Step 自主步 #1', x: 33, y: 22, status: 'idle',
        detail: 'LLM + 工具自主执行(workspaceWriteFile / RunCommand)。',
        loc: 'AutonomousLoopEngine.java:211', code: 'workspaceWriteFile.apply(path, content)' },
      { id: 'o1', kind: 'observation', label: 'Observation', x: 57, y: 22, status: 'idle',
        detail: '工具结果反馈,假完成守护验证证据。',
        loc: 'ToolRuntimeAspect.java:130', code: 'coordinator.toolSucceeded(runId, at) // 证据落库' },
      { id: 's2', kind: 'step', label: 'Step 自主步 #2', x: 57, y: 58, status: 'idle',
        detail: '基于 Observation 继续自主执行下一动作。',
        loc: 'AutonomousLoopEngine.java:211', code: 'RunCommand.apply(cmd) // side-effect' },
      { id: 'c', kind: 'complete', label: 'TASK_COMPLETE', x: 88, y: 66, status: 'idle',
        detail: '验证通过(有写/命令证据),标记完成。',
        loc: 'AutonomousLoopEngine.java:330', code: 'if (tracker.satisfiesCompletionCondition(risk)) complete()' }
    ],
    edges: [
      { id: 'e1', from: 'g', to: 's1', kind: 'flow' },
      { id: 'e2', from: 's1', to: 'o1', kind: 'flow' },
      { id: 'e3', from: 'o1', to: 's2', kind: 'loop' },
      { id: 'e4', from: 's2', to: 'c', kind: 'flow' }
    ],
    frames: [
      { edgeIds: [], nodeIds: ['g'], phase: 'GOAL · risk=write' },
      { edgeIds: ['e1'], nodeIds: ['s1'], phase: 'STEP #1 · writeFile' },
      { edgeIds: ['e2'], nodeIds: ['o1'], phase: 'OBSERVATION · 证据' },
      { edgeIds: ['e3'], nodeIds: ['s2'], phase: 'STEP #2 · loop', note: 'Observation 回灌,继续自主步' },
      { edgeIds: ['e4'], nodeIds: ['c'], phase: 'TASK_COMPLETE' }
    ]
  },

  // ───────────────────── SINGLE_TURN · Function-Calling 单轮 线性 ─────────────────────
  SINGLE_TURN: {
    paradigm: 'SINGLE_TURN',
    label: '单轮',
    description: 'Function-Calling 单轮问答',
    color: PARADIGM_ACCENTS.SINGLE_TURN,
    nodes: [
      { id: 'q', kind: 'question', label: '接收问题', x: 14, y: 42, status: 'idle',
        detail: '用户提问,Function-Calling 单轮处理。',
        loc: 'ChatController.java:64', code: 'chatService.send(chatRequest)' },
      { id: 't', kind: 'tool', label: '工具调用(可选)', x: 50, y: 22, status: 'idle',
        detail: 'LLM 自主调用工具,得结果。',
        loc: 'ToolRuntimeAspect.java:108', code: 'coordinator.toolStarted(runId, at) // emit TOOL_STARTED' },
      { id: 'a', kind: 'answer', label: '返回答案', x: 86, y: 42, status: 'idle',
        detail: '综合工具结果,直接回答。',
        loc: 'ChatServiceImpl.java:320', code: 'return new ChatResponse(sessionKey, answer, model, now)' }
    ],
    edges: [
      { id: 'e1', from: 'q', to: 't', kind: 'flow' },
      { id: 'e2', from: 't', to: 'a', kind: 'flow' }
    ],
    frames: [
      { edgeIds: [], nodeIds: ['q'], phase: 'QUESTION' },
      { edgeIds: ['e1'], nodeIds: ['t'], phase: 'TOOL · function-calling' },
      { edgeIds: ['e2'], nodeIds: ['a'], phase: 'ANSWER' }
    ]
  }
};
