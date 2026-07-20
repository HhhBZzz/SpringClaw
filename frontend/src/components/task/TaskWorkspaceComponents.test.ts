// @vitest-environment happy-dom
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import TaskApprovalCard from './TaskApprovalCard.vue';
import DeveloperDetailsToggle from './DeveloperDetailsToggle.vue';
import TaskLoginGate from './TaskLoginGate.vue';
import TaskStatusCard from './TaskStatusCard.vue';

describe('task workspace components', () => {
  it('renders a safe approval summary and emits the selected decision', async () => {
    const wrapper = mount(TaskApprovalCard, {
      props: {
        title: '需要确认的工具操作',
        summary: '写入用户已授权的文件',
        riskLevel: 'write',
        targetPaths: ['src/A.java'],
        submitting: false,
        approveLabel: '确认执行'
      }
    });

    expect(wrapper.text()).toContain('确认前不会执行。');
    expect(wrapper.text()).toContain('src/A.java');
    expect(wrapper.text()).not.toContain('proposal');
    await wrapper.get('button.btn-primary').trigger('click');
    expect(wrapper.emitted('approve')).toHaveLength(1);
    await wrapper.get('button.btn-subtle').trigger('click');
    expect(wrapper.emitted('reject')).toHaveLength(1);
  });

  it('offers status recovery actions without encoding status only in color', async () => {
    const wrapper = mount(TaskStatusCard, {
      props: {
        phase: 'status_unknown',
        title: '暂时无法确认最终状态',
        detail: '系统不会把未知状态当作成功。',
        elapsedLabel: '12s',
        result: '',
        canRetry: true,
        canRefreshStatus: true,
        canOpenDetails: true
      }
    });

    expect(wrapper.text()).toContain('暂时无法确认最终状态');
    expect(wrapper.text()).toContain('已用时 12s');
    const buttons = wrapper.findAll('button.btn-subtle');
    await buttons[0].trigger('click');
    await buttons[1].trigger('click');
    await buttons[2].trigger('click');
    expect(wrapper.emitted('retry')).toHaveLength(1);
    expect(wrapper.emitted('refreshStatus')).toHaveLength(1);
    expect(wrapper.emitted('openDetails')).toHaveLength(1);
  });

  it('does not offer developer details when the current task has none', () => {
    const wrapper = mount(TaskStatusCard, {
      props: {
        phase: 'idle',
        title: '可以开始新任务',
        detail: '描述你希望 Agent 完成的事情。',
        elapsedLabel: '',
        result: '',
        canRetry: false,
        canRefreshStatus: false,
        canOpenDetails: false
      }
    });

    expect(wrapper.text()).not.toContain('开发者详情');
  });

  it('forwards successful login while keeping the parent-owned draft explanation', async () => {
    const wrapper = mount(TaskLoginGate, {
      props: { hasDraft: true },
      global: {
        stubs: {
          LoginPanel: { template: '<button type="button" @click="$emit(\'authenticated\')">登录</button>' }
        }
      }
    });

    expect(wrapper.text()).toContain('你已输入的任务会保留');
    await wrapper.get('button').trigger('click');
    expect(wrapper.emitted('authenticated')).toHaveLength(1);
  });

  it('exposes the developer-details state to assistive technology', async () => {
    const wrapper = mount(DeveloperDetailsToggle, { props: { open: false, summary: '正在处理任务 · 2s' } });

    expect(wrapper.get('button').attributes('aria-expanded')).toBe('false');
    await wrapper.get('button').trigger('click');
    expect(wrapper.emitted('toggle')).toHaveLength(1);
  });
});
