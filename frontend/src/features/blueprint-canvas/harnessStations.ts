/**
 * harness 装配流水线 —— 一次 Run 穿过的 10 层 harness 外壳工位
 *
 * 工位 = SpringClaw 包裹 agent 的基础设施层(治理/failover/meta-guard/...)。
 * 每工位对应真实 Java 类;traceType 标后端 trace 事件类型(可 live 驱动),null=暂无离散事件用 demo。
 * 范式核心(core)工位嵌入 AgentCanvas(minimal)。
 */
import type { AgentTraceEvent } from '../../types';

export type HarnessGroup = 'pre' | 'core' | 'post';

export interface HarnessStation {
  id: string;
  /** 生命周期序号 1..10 */
  layer: number;
  label: string;
  /** 真实 Java 类(终端展示) */
  cls: string;
  /** 对应后端 trace 事件 type;null=无离散事件,用 demo */
  traceTypes: AgentTraceEvent['type'][];
  group: HarnessGroup;
  /** 工位简述 */
  desc: string;
}

/** 顺序 = 请求生命周期:鉴权 → 路由 → 上下文 → 模型 → [范式核心] → 工具治理 → meta-guard → 技能降级 → 校验 → SSE */
export const HARNESS_STATIONS: HarnessStation[] = [
  { id: 'transport', layer: 1, label: 'Transport · Auth', cls: 'ModelTransportGuardService · TokenAuthenticationInterceptor', traceTypes: [], group: 'pre', desc: '请求鉴权 + 模型传输守卫' },
  { id: 'route', layer: 2, label: 'Route · Decide', cls: 'AgentDecisionService · ExecutionDecision', traceTypes: ['route', 'decision'], group: 'pre', desc: '意图路由 + 范式/引擎选择' },
  { id: 'context', layer: 3, label: 'Context · Memory Inject', cls: 'ChatContextFactory · ContextAssembler', traceTypes: [], group: 'pre', desc: '双轨记忆组装上下文(MySQL 事件流 + Redis 向量召回)' },
  { id: 'model', layer: 4, label: 'Model · Failover', cls: 'ModelCallExecutor · AiProviderService', traceTypes: ['model', 'fallback'], group: 'pre', desc: '多 provider 调用 + 自动 failover' },
  { id: 'core', layer: 5, label: 'Paradigm Core', cls: 'ReAct / OPAR / Plan-Execute / Reflexion / MultiAgent', traceTypes: ['agent'], group: 'core', desc: '范式核心引擎(嵌入动态沙盘)' },
  { id: 'tool', layer: 6, label: 'Tool Governance', cls: 'ToolRuntimeAspect · ExplicitToolExecutioner', traceTypes: ['tool'], group: 'post', desc: 'AOP 治理:权限/风险/限流/审计 + 手动工具循环' },
  { id: 'metaguard', layer: 7, label: 'Meta-guard', cls: 'ChatResponsePolicyService · MetaGuardExecutor', traceTypes: [], group: 'post', desc: '身份/拒答泄露检测 + 自动重试' },
  { id: 'skill', layer: 8, label: 'Local Skill Fallback', cls: 'LocalSkillFallbackService', traceTypes: ['skill'], group: 'post', desc: '模型不可用时本地技能降级' },
  { id: 'verify', layer: 9, label: 'Verify · Eval Gate', cls: 'MemoryProviderEvaluationHarnessService · redline', traceTypes: ['verification'], group: 'post', desc: '记忆有效性校验 + 评估门' },
  { id: 'sse', layer: 10, label: 'Project · SSE Bridge', cls: 'RunResultProjector · SseEventBridge', traceTypes: [], group: 'post', desc: 'Run 状态投影 + trace 流 emit' }
];

/** trace 事件 type → 工位 id(用于 live 驱动点亮) */
export const TRACE_TO_STATION: Partial<Record<string, string>> = Object.fromEntries(
  HARNESS_STATIONS.flatMap((s) => s.traceTypes.map((t) => [t, s.id]))
);
