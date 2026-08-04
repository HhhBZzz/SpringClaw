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
});
