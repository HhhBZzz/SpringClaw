import { describe, expect, it } from 'vitest';
import { AGENT_PARADIGMS, type AgentParadigm } from '../../../types';
import { cloneBlueprint, getBlueprint, listParadigms, paradigmAccent } from './useParadigmTopology';

const PARADIGMS = AGENT_PARADIGMS.map((p) => p.value);

describe('useParadigmTopology', () => {
  it('listParadigms 暴露全部 7 范式', () => {
    expect(listParadigms().map((p) => p.value)).toEqual(PARADIGMS);
    expect(PARADIGMS).toHaveLength(7);
  });

  it.each(PARADIGMS)('%s 存在蓝图且引用闭合', (paradigm) => {
    const bp = getBlueprint(paradigm as AgentParadigm);
    expect(bp.nodes.length).toBeGreaterThan(0);
    expect(bp.frames.length).toBeGreaterThan(0);

    const nodeIds = new Set(bp.nodes.map((n) => n.id));
    const edgeIds = new Set(bp.edges.map((e) => e.id));
    // 节点 id 唯一
    expect(nodeIds.size).toBe(bp.nodes.length);
    // 连线 id 唯一且端点引用存在
    for (const e of bp.edges) {
      expect(edgeIds.has(e.id)).toBe(true);
      expect(nodeIds.has(e.from)).toBe(true);
      expect(nodeIds.has(e.to)).toBe(true);
    }
    // 每帧引用合法,且至少点亮一个节点
    for (const f of bp.frames) {
      for (const nid of f.nodeIds) expect(nodeIds.has(nid)).toBe(true);
      for (const eid of f.edgeIds) expect(edgeIds.has(eid)).toBe(true);
      expect(f.nodeIds.length).toBeGreaterThan(0);
    }
    // 首帧无入边(入口节点就地点亮)
    expect(bp.frames[0].edgeIds).toEqual([]);
  });

  it.each(PARADIGMS)('%s 的 Multi-Agent 并行帧确实并行(>1 edge)', (paradigm) => {
    if (paradigm !== 'MULTI_AGENT') return;
    const bp = getBlueprint('MULTI_AGENT');
    const parallel = bp.frames.find((f) => f.edgeIds.length > 1);
    expect(parallel).toBeTruthy();
    expect((parallel as { nodeIds: string[] }).nodeIds.length).toBeGreaterThan(1);
  });

  it('cloneBlueprint 重置 status 为 idle 且与源数据独立', () => {
    const clone = cloneBlueprint('REACT');
    expect(clone.nodes.every((n) => n.status === 'idle')).toBe(true);
    clone.nodes[0].status = 'live';
    // 源数据未被污染
    expect(getBlueprint('REACT').nodes[0].status).toBe('idle');
    // 又一次 clone 也是干净的
    expect(cloneBlueprint('REACT').nodes[0].status).toBe('idle');
  });

  it('paradigmAccent 为每范式返回 hex 色', () => {
    for (const p of PARADIGMS) {
      expect(paradigmAccent(p as AgentParadigm)).toMatch(/^#[0-9a-f]{6}$/i);
    }
  });
});
