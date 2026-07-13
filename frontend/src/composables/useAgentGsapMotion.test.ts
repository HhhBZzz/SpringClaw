// @vitest-environment happy-dom
import { defineComponent, h, ref } from 'vue';
import { mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';

const motionSpies = vi.hoisted(() => {
  const fromTargets: unknown[] = [];
  const setTargets: unknown[] = [];
  const timeline = {
    from(target: unknown) {
      fromTargets.push(target);
      return timeline;
    }
  };

  return { fromTargets, setTargets, timeline };
});

vi.mock('gsap', () => ({
  gsap: {
    defaults: vi.fn(),
    killTweensOf: vi.fn(),
    matchMedia: vi.fn(() => ({
      add: (_conditions: unknown, setup: (context: { conditions: Record<string, boolean> }) => unknown) => {
        setup({ conditions: { isDesktop: true, isMobile: false, reduceMotion: false } });
      },
      revert: vi.fn()
    })),
    set: vi.fn((target: unknown) => motionSpies.setTargets.push(target)),
    timeline: vi.fn(() => motionSpies.timeline)
  }
}));

import { useAgentGsapMotion } from './useAgentGsapMotion';

describe('useAgentGsapMotion', () => {
  afterEach(() => {
    motionSpies.fromTargets.length = 0;
    motionSpies.setTargets.length = 0;
  });

  it('does not animate an absent inspector in the default collapsed workspace', () => {
    const Harness = defineComponent({
      setup() {
        const root = ref<HTMLElement | null>(null);
        const chatLog = ref<HTMLElement | null>(null);
        useAgentGsapMotion({ root, chatLog });

        return () => h('div', { ref: root }, [
          h('nav', { class: 'studio-nav' }),
          h('header', { class: 'studio-heading' }),
          h('div', { class: 'runtime-mode-switch' }),
          h('section', { ref: chatLog, class: 'stitch-chat' }),
          h('footer', { class: 'stitch-composer' })
        ]);
      }
    });

    mount(Harness);

    expect(motionSpies.setTargets.flat()).not.toContain('.runtime-inspector');
    expect(motionSpies.fromTargets.flat()).not.toContain('.runtime-inspector');
  });
});
