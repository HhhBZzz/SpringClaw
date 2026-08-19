import { describe, expect, it } from 'vitest';
import {
  canonicalEventKind,
  groupTraceByTurn,
  isBoundaryEvent,
  type TraceTurnGroup
} from './traceGrouping';
import type { AgentTraceEvent } from '../../types';

/** 后端 canonical 投影(toTraceEvent)的 wire 名事件:action=wireName,category=runtime。 */
function canonical(action: string, overrides: Partial<AgentTraceEvent> = {}): AgentTraceEvent {
  return {
    stepName: action,
    type: 'agent',
    status: 'success',
    category: 'runtime',
    action,
    source: 'canonical',
    ...overrides
  };
}

/** 后端 step/turn 边界事件的 payload JSON(RunCoordinator appendStructuredObservation)。 */
function stepPayload(extra: Record<string, unknown> = {}): string {
  return JSON.stringify({ stepIndex: 0, stepKind: 'react', ...extra });
}

describe('canonicalEventKind', () => {
  it('识别 turn/step 边界 wire 名', () => {
    expect(canonicalEventKind(canonical('turn.started'))).toBe('turn-started');
    expect(canonicalEventKind(canonical('turn.completed'))).toBe('turn-completed');
    expect(canonicalEventKind(canonical('step.started'))).toBe('step-started');
    expect(canonicalEventKind(canonical('step.completed'))).toBe('step-completed');
  });

  it('非边界 canonical 事件 → null(不是 turn/step 边界)', () => {
    expect(canonicalEventKind(canonical('model.called'))).toBeNull();
    expect(canonicalEventKind(canonical('tool.succeeded'))).toBeNull();
    expect(canonicalEventKind(canonical('run.completed'))).toBeNull();
  });

  it('legacy 事件(无 canonical source)一律 null,不误判', () => {
    expect(canonicalEventKind({
      stepName: 'turn.started',
      type: 'agent',
      status: 'success'
    } as AgentTraceEvent)).toBeNull();
  });
});

describe('isBoundaryEvent', () => {
  it('step.started/step.completed/turn.started/turn.completed 是边界', () => {
    for (const wire of ['turn.started', 'step.started', 'step.completed', 'turn.completed']) {
      expect(isBoundaryEvent(canonical(wire))).toBe(true);
    }
  });
});

describe('groupTraceByTurn', () => {
  it('turn.started 开新组,后续事件归属该组;无 turn 边界的流落进未分组前导区', () => {
    const events = [
      canonical('run.created'),
      canonical('decision.made'),
      canonical('turn.started'),
      canonical('step.started'),
      canonical('model.called'),
      canonical('step.completed'),
      canonical('turn.completed')
    ];
    const groups = groupTraceByTurn(events);
    // 前导(无 turn 边界的事件) + 1 个 turn 组
    expect(groups.length).toBe(2);
    expect(groups[0].kind).toBe('lead');
    expect(groups[0].events.length).toBe(2);
    expect(groups[1].kind).toBe('turn');
    expect(groups[1].events.map((e) => e.action)).toEqual([
      'turn.started', 'step.started', 'model.called', 'step.completed', 'turn.completed'
    ]);
  });

  it('step 边界对在组内聚合:step.started 到 step.completed 之间的事件构成一个 step', () => {
    const events = [
      canonical('turn.started'),
      canonical('step.started', { detail: stepPayload({ stepIndex: 0, stepKind: 'react' }) }),
      canonical('model.called'),
      canonical('tool.started'),
      canonical('tool.succeeded'),
      canonical('step.completed', { detail: stepPayload({ stepIndex: 0, outcome: 'terminal' }) }),
      canonical('turn.completed')
    ];
    const groups = groupTraceByTurn(events);
    const turn = groups.find((g) => g.kind === 'turn') as TraceTurnGroup;
    expect(turn.steps.length).toBe(1);
    const step = turn.steps[0];
    expect(step.kind).toBe('react');
    expect(step.index).toBe(0);
    expect(step.innerEvents.map((e) => e.action)).toEqual([
      'model.called', 'tool.started', 'tool.succeeded'
    ]);
    expect(step.outcome).toBe('terminal');
  });

  it('未闭合的 step(started 无 completed)产出 running 态 step', () => {
    const events = [
      canonical('turn.started'),
      canonical('step.started', { detail: stepPayload() }),
      canonical('model.called')
    ];
    const groups = groupTraceByTurn(events);
    const turn = groups.find((g) => g.kind === 'turn') as TraceTurnGroup;
    expect(turn.steps.length).toBe(1);
    expect(turn.steps[0].running).toBe(true);
    expect(turn.steps[0].outcome).toBeNull();
  });

  it('两个 turn → 两组,各自聚合自己的 step', () => {
    const mk = (idx: number, kind: string) => [
      canonical('turn.started'),
      canonical('step.started', { detail: JSON.stringify({ stepIndex: idx, stepKind: kind }) }),
      canonical('model.called'),
      canonical('step.completed', { detail: JSON.stringify({ stepIndex: idx, stepKind: kind, outcome: 'ok' }) }),
      canonical('turn.completed')
    ];
    const groups = groupTraceByTurn([...mk(0, 'react'), ...mk(1, 'opar')]);
    const turns = groups.filter((g) => g.kind === 'turn');
    expect(turns.length).toBe(2);
    expect((turns[0] as TraceTurnGroup).steps[0].kind).toBe('react');
    expect((turns[1] as TraceTurnGroup).steps[0].kind).toBe('opar');
  });

  it('step 边界外(组内但 step 对之外)的事件保留在 turn.ungrouped,不丢失', () => {
    const events = [
      canonical('turn.started'),
      canonical('verification.started'),
      canonical('answer.composed'),
      canonical('turn.completed')
    ];
    const groups = groupTraceByTurn(events);
    const turn = groups.find((g) => g.kind === 'turn') as TraceTurnGroup;
    expect(turn.steps.length).toBe(0);
    expect(turn.ungrouped.map((e) => e.action)).toEqual([
      'turn.started', 'verification.started', 'answer.composed', 'turn.completed'
    ]);
  });

  it('纯 legacy 流(无任何 canonical 边界)→ 单一 lead 组,原样保序', () => {
    const legacy: AgentTraceEvent[] = [
      { stepName: 'Route', type: 'route', status: 'success' },
      { stepName: 'search', type: 'tool', status: 'success' },
      { stepName: 'analyze', type: 'model', status: 'success' }
    ];
    const groups = groupTraceByTurn(legacy);
    expect(groups.length).toBe(1);
    expect(groups[0].kind).toBe('lead');
    expect(groups[0].events.length).toBe(3);
  });

  it('step.completed 的 payload 不可解析时仍聚合(容错,outcome=null)', () => {
    const events = [
      canonical('turn.started'),
      canonical('step.started'),
      canonical('model.called'),
      canonical('step.completed', { detail: 'not-json' })
    ];
    const groups = groupTraceByTurn(events);
    const turn = groups.find((g) => g.kind === 'turn') as TraceTurnGroup;
    expect(turn.steps.length).toBe(1);
    expect(turn.steps[0].outcome).toBeNull();
  });
});
