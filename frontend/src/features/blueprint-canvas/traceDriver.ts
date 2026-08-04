/**
 * traceDriver —— 真实 AgentTraceEvent → 画布驱动的纯映射核心
 *
 * 后端 /api/chat/stream 已用 event:trace 推 AgentTraceEvent。本模块是把这条真实事件流
 * 接到画布的"事件 → 帧推进 + 节点真实日志丰富"的纯函数,可独立单测(不依赖后端/DOM)。
 *
 * 策略:每个 trace 事件推进画布一帧(画布拓扑是范式的 curated 形状,frames 已定义执行顺序);
 * 并把事件的真实 detail/loc 写入该帧主节点,使 X-Ray 背面展示真实日志而非 demo 硬编码。
 */
import type { AgentTraceEvent } from '../../types';
import type { CanvasNode } from './types';

/**
 * 用真实 trace 事件丰富节点(X-Ray 背面展示真实日志)。
 * 优先用事件字段,缺失则保留节点原值;状态置 live。
 */
export function enrichNodeFromTrace(node: CanvasNode, event: AgentTraceEvent): CanvasNode {
  const loc = traceLoc(event);
  return {
    ...node,
    detail: event.detail && event.detail.trim() ? event.detail : node.detail,
    loc: loc || node.loc,
    code: event.action ? `${event.action}${event.target ? '(' + event.target + ')' : ''}` : node.code,
    status: event.status === 'failed' ? 'failed' : 'live'
  };
}

/** 从 trace 事件拼出源码定位(stepSchema · category · action)。 */
export function traceLoc(event: AgentTraceEvent): string {
  const parts: string[] = [];
  if (event.stepSchema) parts.push(event.stepSchema);
  if (event.category) parts.push(event.category);
  if (event.action && event.action !== event.category) parts.push(event.action);
  return parts.join(' · ');
}

/**
 * 一个 trace 事件推进一帧,封顶在最后一帧。
 * frameIndex 从 -1 起步,首个事件落到 frame 0(入口节点)。
 */
export function advanceFrame(frameIndex: number, total: number): number {
  if (total <= 0) return 0;
  return Math.min(frameIndex + 1, total - 1);
}

export type TraceRoute = { type: 'advance' | 'overflow'; frameIndex: number };

/**
 * Fork B 路由:一个 trace 事件该"推进新帧"还是"溢出到当前节点 X-Ray"。
 * 未到末帧 → advance(推进); 已在末帧 → overflow(不截断,溢出到末帧主节点)。
 * frameIndex 从 -1 起步。
 */
export function routeTraceEvent(frameIndex: number, total: number): TraceRoute {
  if (total <= 0) return { type: 'overflow', frameIndex: 0 };
  if (frameIndex < total - 1) return { type: 'advance', frameIndex: frameIndex + 1 };
  return { type: 'overflow', frameIndex: total - 1 };
}

/** 把一个 trace 事件格式化为 X-Ray 终端的一行日志 */
export function traceToLogLine(event: AgentTraceEvent): string {
  const head = [event.stepName, event.type ? `[${event.type}]` : '', event.status ? `(${event.status})` : '']
    .filter(Boolean).join(' ');
  return `${head}${event.detail ? ` — ${event.detail}` : ''}`;
}

/** 该帧的主节点 id(用于丰富真实日志 / 溢出接收);并行帧取第一个。 */
export function primaryNodeIdOf(frameNodeIds: string[]): string | undefined {
  return frameNodeIds[0];
}
