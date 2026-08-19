/**
 * traceGrouping —— canonical turn/step 边界事件的分组纯映射
 *
 * 后端(Phase 1 事件地基)已在 canonical run 事件日志发射 turn.started/step.started/
 * step.completed/turn.completed 边界,经 AgentRunTraceService.toTraceEvent 投影为
 * AgentTraceEvent(action=wireName,category="runtime",source="canonical",detail=payload JSON)。
 * 本模块把这条平铺事件流结构化成 turn → step → inner 三层分组,供 timeline
 * (AgentView/RunFlowCard)与 blueprint-canvas 消费;纯函数,可独立单测。
 *
 * 容错契约:legacy 流(无 canonical 边界)落单一 lead 组原样保序;payload 不可解析
 * 时字段置 null 不丢事件;未闭合 step 产出 running 态。
 */
import type { AgentTraceEvent } from '../../types';

const TURN_STARTED = 'turn.started';
const TURN_COMPLETED = 'turn.completed';
const STEP_STARTED = 'step.started';
const STEP_COMPLETED = 'step.completed';

/** canonical 边界事件的分类。 */
export type CanonicalEventKind = 'turn-started' | 'turn-completed' | 'step-started' | 'step-completed';

/**
 * 识别 canonical turn/step 边界事件。判定标准:source === 'canonical'
 * 且 action 为四个边界 wire 名之一。legacy 事件即使 stepName 撞名也不算
 * (它们没有 turn/step 语义,平铺渲染)。
 */
export function canonicalEventKind(event: AgentTraceEvent): CanonicalEventKind | null {
  if (event.source !== 'canonical') return null;
  switch (event.action) {
    case TURN_STARTED: return 'turn-started';
    case TURN_COMPLETED: return 'turn-completed';
    case STEP_STARTED: return 'step-started';
    case STEP_COMPLETED: return 'step-completed';
    default: return null;
  }
}

export function isBoundaryEvent(event: AgentTraceEvent): boolean {
  return canonicalEventKind(event) !== null;
}

/** step 边界 payload(RunCoordinator appendStructuredObservation 的 JSON)。 */
interface StepBoundaryPayload {
  stepIndex?: number;
  stepKind?: string;
  outcome?: string;
}

/** 一个 step 组:边界对 + 之间的内层事件。 */
export interface TraceStepGroup {
  /** payload.stepKind(如 "react"/"opar"),缺省 'step'。 */
  kind: string;
  /** payload.stepIndex。 */
  index: number;
  /** step.completed 的 outcome(terminal/failed/ok/...);未闭合为 null。 */
  outcome: string | null;
  /** 未闭合(step.started 后流截断)→ true。 */
  running: boolean;
  /** 边界对之间的内层事件(model.called/tool.* 等)。 */
  innerEvents: AgentTraceEvent[];
  /** 原始边界事件(含 payload,durationMs 在 completed 上)。 */
  startedEvent: AgentTraceEvent;
  completedEvent: AgentTraceEvent | null;
}

/** 一个 turn 组:turn.started 到 turn.completed 之间的一切,内部再按 step 分组。 */
export interface TraceTurnGroup {
  kind: 'turn';
  events: AgentTraceEvent[];
  turnStarted: AgentTraceEvent;
  turnCompleted: AgentTraceEvent | null;
  steps: TraceStepGroup[];
  /** step 对之外的事件(verification/answer 等),含 turn 边界自身。 */
  ungrouped: AgentTraceEvent[];
}

/** 无 turn 边界的前导/legacy 段。 */
export interface TraceLeadGroup {
  kind: 'lead';
  events: AgentTraceEvent[];
}

export type TraceGroup = TraceTurnGroup | TraceLeadGroup;

function parseStepPayload(event: AgentTraceEvent): StepBoundaryPayload {
  const raw = (event.detail || '').trim();
  if (!raw.startsWith('{')) return {};
  try {
    return JSON.parse(raw) as StepBoundaryPayload;
  } catch {
    return {};
  }
}

/**
 * 把平铺事件流分组:turn.started 开新 turn 组;组内 step.started/step.completed
 * 对聚合为 step(其间事件为 inner);无 turn 边界的连续段落 lead 组(含纯 legacy 流)。
 */
export function groupTraceByTurn(events: AgentTraceEvent[]): TraceGroup[] {
  const groups: TraceGroup[] = [];
  let lead: TraceLeadGroup | null = null;
  let turn: TraceTurnGroup | null = null;
  let openStep: TraceStepGroup | null = null;

  const ensureLead = () => {
    if (!lead) {
      lead = { kind: 'lead', events: [] };
      groups.push(lead);
    }
    return lead;
  };

  for (const event of events) {
    const kind = canonicalEventKind(event);
    if (kind === 'turn-started') {
      turn = {
        kind: 'turn',
        events: [event],
        turnStarted: event,
        turnCompleted: null,
        steps: [],
        ungrouped: [event]
      };
      groups.push(turn);
      openStep = null;
      continue;
    }
    if (kind === 'turn-completed') {
      if (turn) {
        turn.events.push(event);
        turn.ungrouped.push(event);
        turn.turnCompleted = event;
      } else {
        ensureLead().events.push(event);
      }
      turn = null;
      openStep = null;
      continue;
    }
    if (kind === 'step-started') {
      if (turn) {
        const payload = parseStepPayload(event);
        openStep = {
          kind: payload.stepKind || 'step',
          index: typeof payload.stepIndex === 'number' ? payload.stepIndex : turn.steps.length,
          outcome: null,
          running: true,
          innerEvents: [],
          startedEvent: event,
          completedEvent: null
        };
        turn.steps.push(openStep);
        turn.events.push(event);
      } else {
        ensureLead().events.push(event);
      }
      continue;
    }
    if (kind === 'step-completed') {
      if (turn) {
        turn.events.push(event);
        if (openStep) {
          const payload = parseStepPayload(event);
          openStep.running = false;
          openStep.outcome = payload.outcome || null;
          openStep.completedEvent = event;
          openStep = null;
        } else {
          turn.ungrouped.push(event);
        }
      } else {
        ensureLead().events.push(event);
      }
      continue;
    }
    // 非边界事件
    if (turn) {
      turn.events.push(event);
      if (openStep) {
        openStep.innerEvents.push(event);
      } else {
        turn.ungrouped.push(event);
      }
    } else {
      ensureLead().events.push(event);
    }
  }
  return groups;
}
