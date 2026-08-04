import { describe, expect, it } from 'vitest';
import type { AgentTraceEvent } from '../../types';
import type { CanvasNode } from './types';
import { advanceFrame, enrichNodeFromTrace, primaryNodeIdOf, routeTraceEvent, traceLoc, traceToLogLine } from './traceDriver';

const baseNode: CanvasNode = { id: 'a1', kind: 'action', label: 'Attempt 1', x: 10, y: 10, status: 'idle', detail: 'demo 文案', loc: 'demo.java:1' };

describe('traceDriver', () => {
  it('advanceFrame 从 -1 起步,首事件落 frame 0,之后逐帧,封顶', () => {
    expect(advanceFrame(-1, 5)).toBe(0);
    expect(advanceFrame(0, 5)).toBe(1);
    expect(advanceFrame(3, 5)).toBe(4);
    expect(advanceFrame(4, 5)).toBe(4); // 封顶
    expect(advanceFrame(10, 5)).toBe(4);
    expect(advanceFrame(0, 0)).toBe(0); // 空 frames 防御
  });

  it('enrichNodeFromTrace 用真实 detail/loc 覆盖 demo 文案,状态随事件', () => {
    const ev: AgentTraceEvent = { stepName: 'search', type: 'tool', status: 'success', detail: '命中 6 个 AgentEngine 实现', stepSchema: 'WorkspaceSearchToolPack', category: 'search', action: 'search("AgentEngine")' };
    const enriched = enrichNodeFromTrace(baseNode, ev);
    expect(enriched.detail).toBe('命中 6 个 AgentEngine 实现');
    expect(enriched.loc).toBe('WorkspaceSearchToolPack · search · search("AgentEngine")');
    expect(enriched.code).toContain('search');
    expect(enriched.status).toBe('live');
  });

  it('enrichNodeFromTrace 失败事件 → failed;空 detail 保留原值', () => {
    const ok = enrichNodeFromTrace(baseNode, { stepName: 's', type: 'tool', status: 'failed', detail: '' });
    expect(ok.status).toBe('failed');
    expect(ok.detail).toBe('demo 文案'); // 空保留
  });

  it('traceLoc 拼接 stepSchema · category · action(去重)', () => {
    expect(traceLoc({ stepName: 's', type: 'tool', status: 'success', stepSchema: 'A', category: 'B', action: 'C' })).toBe('A · B · C');
    expect(traceLoc({ stepName: 's', type: 'tool', status: 'success', category: 'B', action: 'B' })).toBe('B'); // action==category 去重
    expect(traceLoc({ stepName: 's', type: 'tool', status: 'success' })).toBe('');
  });

  it('primaryNodeIdOf 取并行帧首节点', () => {
    expect(primaryNodeIdOf(['w1', 'w2', 'w3'])).toBe('w1');
    expect(primaryNodeIdOf([])).toBeUndefined();
  });

  it('routeTraceEvent: 未到末帧 advance,末帧 overflow(不截断)', () => {
    // 5 帧,frameIndex -1 起步
    expect(routeTraceEvent(-1, 5)).toEqual({ type: 'advance', frameIndex: 0 });
    expect(routeTraceEvent(2, 5)).toEqual({ type: 'advance', frameIndex: 3 });
    expect(routeTraceEvent(3, 5)).toEqual({ type: 'advance', frameIndex: 4 }); // 到末帧
    // 已在末帧 → overflow(停在末帧,不溢出成 5)
    expect(routeTraceEvent(4, 5)).toEqual({ type: 'overflow', frameIndex: 4 });
    expect(routeTraceEvent(10, 5)).toEqual({ type: 'overflow', frameIndex: 4 });
    // 单帧拓扑:首事件 advance 到 frame 0,之后 overflow
    expect(routeTraceEvent(-1, 1)).toEqual({ type: 'advance', frameIndex: 0 });
    expect(routeTraceEvent(0, 1)).toEqual({ type: 'overflow', frameIndex: 0 });
    expect(routeTraceEvent(0, 0)).toEqual({ type: 'overflow', frameIndex: 0 });
  });

  it('traceToLogLine 拼出终端日志行', () => {
    expect(traceToLogLine({ stepName: 'search', type: 'tool', status: 'success', detail: '6 命中' }))
      .toBe('search [tool] (success) — 6 命中');
    expect(traceToLogLine({ stepName: 'plan', type: 'agent', status: 'started' }))
      .toBe('plan [agent] (started)');
  });
});
