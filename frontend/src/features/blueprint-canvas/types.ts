/**
 * Blueprint Canvas · 数据契约
 *
 * 设计原则：State 本身范式无关。ReAct(环) 与 Plan-Execute(线性+分叉) 的差异
 * 全部收敛到 {@link ParadigmBlueprint} 的 nodes/edges 拓扑里，由 useParadigmTopology 提供。
 * 后端真实 AgentTraceEvent 可经 buildLinearBlueprint() 适配为同一契约，即插即用。
 *
 * 坐标系：x/y 均为 0..100 的舞台百分比（宽 / 高各自映射），SVG 与 HTML 节点共享该空间。
 */
import type { AgentParadigm, AgentTraceEvent } from '../../types';

/** 节点语义种类 —— 决定徽标文案与图标，跨范式统一词表 */
export type NodeKind =
  | 'question' | 'answer'
  | 'model' | 'thought' | 'action' | 'observation'
  | 'observe' | 'plan' | 'act' | 'reflect'
  | 'goal' | 'step' | 'complete'
  | 'execute' | 'replan' | 'aggregate'
  | 'attempt'
  | 'decompose' | 'worker'
  | 'tool' | 'skill' | 'final';

/** 节点运行时状态：idle → live（光梭触达）→ done / failed */
export type NodeStatus = 'idle' | 'live' | 'done' | 'failed';

/** 连线语义：flow 直进 / loop 回环 / replan 失败重规划 / spawn 并行派发 / aggregate 汇聚 */
export type EdgeKind = 'flow' | 'loop' | 'replan' | 'spawn' | 'aggregate';

export interface CanvasNode {
  id: string;
  kind: NodeKind;
  /** 简短标题，如 "Thought #1" */
  label: string;
  /** 一句话说明（X-Ray 背面展示） */
  detail?: string;
  /** 源码定位，如 "ReActEngine.java:294"（X-Ray 背面展示） */
  loc?: string;
  /** 代码片段，支持简易高亮 span（X-Ray 背面展示） */
  code?: string;
  /** X-Ray 背面终端日志行(溢出的真实 trace 事件 append 到此) */
  log?: string[];
  /** 连击/重试计数(live 模式同一节点被反复命中时 ++,≥2 显示 ×N 徽章) */
  combo?: number;
  /** 舞台横坐标 0..100（百分比） */
  x: number;
  /** 舞台纵坐标 0..100（百分比） */
  y: number;
  /** 运行时状态，蓝图数据不带（默认 idle），由回放驱动 */
  status?: NodeStatus;
}

export interface CanvasEdge {
  id: string;
  from: string;
  to: string;
  kind: EdgeKind;
  /** 二次贝塞尔控制点（舞台百分比）；省略则按起终点自动计算柔顺弧线 */
  cp?: { x: number; y: number };
}

/**
 * 一帧回放：并行遍历 edgeIds 的光梭，抵达后将 nodeIds 点亮为 live。
 * Multi-Agent 的 fan-out/fan-in 即"一帧多条 edge"实现并行光梭。
 */
export interface CanvasRunFrame {
  edgeIds: string[];
  nodeIds: string[];
  /** 帧徽标，回放时显示在运行条，如 "THOUGHT"、"EXECUTE step 1"、"WORKERS (parallel)" */
  phase: string;
  note?: string;
}

/** 一个范式的完整蓝图：拓扑（节点+连线）+ 回放帧序列 + 语义色 */
export interface ParadigmBlueprint {
  paradigm: AgentParadigm;
  label: string;
  description: string;
  /** 范式语义色（节点 live 光晕、连线高亮采用） */
  color: string;
  nodes: CanvasNode[];
  edges: CanvasEdge[];
  frames: CanvasRunFrame[];
}

/** 真实 trace → 蓝图的轻量适配器（契约预留，供后端接入） */
export function buildLinearBlueprint(
  paradigm: AgentParadigm,
  events: AgentTraceEvent[],
  meta?: { label: string; description: string; color: string }
): ParadigmBlueprint {
  const nodes: CanvasNode[] = events.map((e, i) => ({
    id: `n${i}`,
    kind: (e.type as CanvasNode['kind']) ?? 'model',
    label: e.stepName,
    detail: e.detail,
    loc: e.stepSchema,
    x: 8 + (84 / Math.max(1, events.length - 1)) * i,
    y: 50,
    status: 'idle'
  }));
  const edges: CanvasEdge[] = nodes.slice(1).map((n, i) => ({
    id: `e${i}`,
    from: `n${i}`,
    to: n.id,
    kind: 'flow'
  }));
  const frames: CanvasRunFrame[] = nodes.map((n, i) => ({
    edgeIds: i === 0 ? [] : [`e${i - 1}`],
    nodeIds: [n.id],
    phase: n.label
  }));
  return {
    paradigm,
    label: meta?.label ?? paradigm,
    description: meta?.description ?? '',
    color: meta?.color ?? '#f6c945',
    nodes,
    edges,
    frames
  };
}
