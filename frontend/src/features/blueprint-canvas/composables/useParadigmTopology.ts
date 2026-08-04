/**
 * useParadigmTopology —— 范式 → 蓝图的纯函数大脑
 *
 * 所有拓扑差异（环 / 线性 / 分叉 / hub-spoke）收敛到这里，
 * State 契约本身范式无关。cloneBlueprint 返回独立副本并把 status 全部重置为 idle，
 * 这样回放期间的运行时突变不会污染内置 demo 源数据。
 */
import { AGENT_PARADIGMS, type AgentParadigm } from '../../../types';
import type { CanvasNode, ParadigmBlueprint } from '../types';
import { PARADIGM_ACCENTS, PARADIGM_BLUEPRINTS } from '../demo/paradigmBlueprints';

/** 按产品愿景固定的 7 范式顺序（驱动选择器与图例） */
export function listParadigms() {
  return AGENT_PARADIGMS;
}

export function paradigmAccent(paradigm: AgentParadigm): string {
  return PARADIGM_ACCENTS[paradigm];
}

export function getBlueprint(paradigm: AgentParadigm): ParadigmBlueprint {
  return PARADIGM_BLUEPRINTS[paradigm];
}

/** 深拷贝蓝图并把所有节点 status 重置为 idle,供运行时安全突变。 */
export function cloneBlueprint(paradigm: AgentParadigm): ParadigmBlueprint {
  const src = PARADIGM_BLUEPRINTS[paradigm];
  const nodes: CanvasNode[] = src.nodes.map((n) => ({ ...n, status: 'idle' }));
  const edges = src.edges.map((e) => ({ ...e }));
  const frames = src.frames.map((f) => ({ ...f, edgeIds: [...f.edgeIds], nodeIds: [...f.nodeIds] }));
  return { ...src, nodes, edges, frames };
}
