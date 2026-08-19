// @vitest-environment happy-dom
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import RunFlowCard from './RunFlowCard.vue';
import type { AgentTraceEvent } from '../types';

const events: AgentTraceEvent[] = [
  { stepName: 'Route', type: 'route', status: 'success', detail: 'picked ReAct', stepSchema: 'ChatRoutingPolicy' },
  { stepName: 'search code', type: 'tool', status: 'started', detail: 'querying AgentEngine' },
  { stepName: 'analyze', type: 'model', status: 'failed', detail: 'timeout', durationMs: 1200 }
];

describe('RunFlowCard', () => {
  it('动态:每个真实 trace 事件 → 一个节点(非写死),按 status 点亮', () => {
    const w = mount(RunFlowCard, { props: { events } });
    const steps = w.findAll('.run-flow-step');
    expect(steps).toHaveLength(3); // 3 个事件 → 3 节点
    expect(steps[0].classes()).toContain('is-done');   // success → done
    expect(steps[1].classes()).toContain('is-live');   // started → live
    expect(steps[2].classes()).toContain('is-failed'); // failed → failed
    expect(w.text()).toContain('Route');
    expect(w.text()).toContain('search code');
  });

  it('空 trace 不渲染(无写死占位)', () => {
    const w = mount(RunFlowCard, { props: { events: [] } });
    expect(w.find('.run-flow').exists()).toBe(false);
  });

  it('不同任务(不同事件)→ 不同节点', () => {
    const other: AgentTraceEvent[] = [
      { stepName: 'plan', type: 'agent', status: 'success' },
      { stepName: 'execute', type: 'tool', status: 'success' }
    ];
    const w = mount(RunFlowCard, { props: { events: other } });
    const steps = w.findAll('.run-flow-step');
    expect(steps).toHaveLength(2);
    expect(w.text()).toContain('plan');
    expect(w.text()).not.toContain('Route'); // 不含上一个任务的节点
  });

  it('点击节点展开真实 detail', async () => {
    const w = mount(RunFlowCard, { props: { events } });
    expect(w.find('.run-flow-detail').exists()).toBe(false);
    await w.findAll('.run-flow-step')[0].trigger('click');
    expect(w.find('.run-flow-detail').exists()).toBe(true);
    expect(w.find('.run-flow-detail').text()).toContain('picked ReAct');
  });

  // === canonical turn/step 分组渲染 ===

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

  const canonicalEvents: AgentTraceEvent[] = [
    canonical('run.created'),
    canonical('turn.started'),
    canonical('step.started', { detail: JSON.stringify({ stepIndex: 0, stepKind: 'react' }) }),
    canonical('model.called', { detail: 'deepseek-v4-pro' }),
    canonical('tool.started', { detail: 'workspace-read' }),
    canonical('step.completed', { detail: JSON.stringify({ stepIndex: 0, stepKind: 'react', outcome: 'terminal' }) }),
    canonical('turn.completed')
  ];

  it('canonical 流:turn 分组渲染,组头标 turn,step 作为组内节点,kind 徽章用 stepKind', () => {
    const w = mount(RunFlowCard, { props: { events: canonicalEvents } });
    // 1 个 lead(run.created) + 1 个 turn 组
    const turns = w.findAll('.run-flow-turn');
    expect(turns).toHaveLength(1);
    // turn 组内:1 个 step 节点(内层事件折叠在 step 下)
    const steps = w.findAll('.run-flow-step');
    expect(steps).toHaveLength(2); // lead 的 run.created + turn 内的 step
    expect(w.text()).toContain('react'); // stepKind 徽章
    // step.outcome 显示
    expect(w.text()).toContain('terminal');
  });

  it('step 节点可展开,显示内层真实事件(model/tool)', async () => {
    const w = mount(RunFlowCard, { props: { events: canonicalEvents } });
    const stepNode = w.findAll('.run-flow-step').find((n) => n.text().includes('react'));
    expect(stepNode).toBeTruthy();
    await stepNode!.trigger('click');
    const detail = w.find('.run-flow-detail');
    expect(detail.exists()).toBe(true);
    expect(detail.text()).toContain('deepseek-v4-pro');
    expect(detail.text()).toContain('workspace-read');
  });

  it('未闭合 step(running)以 live 态渲染', () => {
    const streaming: AgentTraceEvent[] = [
      canonical('turn.started'),
      canonical('step.started', { detail: JSON.stringify({ stepIndex: 0, stepKind: 'opar' }) }),
      canonical('model.called', { status: 'started' })
    ];
    const w = mount(RunFlowCard, { props: { events: streaming } });
    const stepNode = w.findAll('.run-flow-step').find((n) => n.text().includes('opar'));
    expect(stepNode!.classes()).toContain('is-live');
  });
});
