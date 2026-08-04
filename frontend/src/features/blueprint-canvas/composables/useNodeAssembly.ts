/**
 * useNodeAssembly —— ASSEMBLY 模式:Drag & Drop 磁吸入模装配
 *
 * 进入装配模式后,每个 .bp-node 变为 GSAP Draggable:
 *   - liveSnap 栅格磁吸(设计坐标 grid 单位,拖动中实时吸附)
 *   - onPress 抓取放大、onRelease back.out 弹簧回弹(物理弹簧反馈)
 * 装配位置是"ephemeral"(由 Draggable transform 持有);离开模式即复位回拓扑坐标。
 *
 * 互斥:装配模式停回放、不自动 play;切范式时 detach→Flip→reattach。
 */
import gsap from 'gsap';
import { Draggable } from 'gsap/Draggable';
import type { Ref } from 'vue';

gsap.registerPlugin(Draggable);

export interface UseNodeAssemblyOptions {
  container: Ref<HTMLElement | null>;
  /** 设计坐标(0..100)栅格单位,默认 4 → 40px / 24.8px */
  grid?: number;
}

export function useNodeAssembly(options: UseNodeAssemblyOptions) {
  let instances: Draggable[] = [];
  const grid = options.grid ?? 4;

  function reduced() {
    return typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }

  /** 挂载 Draggable 到当前所有 .bp-node */
  function attach() {
    const container = options.container.value;
    if (!container) return;
    const els = container.querySelectorAll<HTMLElement>('.bp-node');
    const bounds = container.querySelector<HTMLElement>('.bp-design');
    const gx = grid * 10; // 设计 x(0..100)→ px(*10)
    const gy = grid * 6.2; // 设计 y(0..100)→ px(*6.2)
    instances = Draggable.create(els, {
      type: 'x,y',
      bounds: bounds ?? undefined,
      zIndexBoost: true,
      // 栅格磁吸:拖动中 transform 平移量吸附到 grid 倍数
      liveSnap: { x: (v: number) => Math.round(v / gx) * gx, y: (v: number) => Math.round(v / gy) * gy },
      onPress() {
        if (reduced()) return;
        const card = (this.target as HTMLElement).querySelector<HTMLElement>('.bp-node-card');
        if (card) gsap.to(card, { scale: 1.06, duration: 0.16, ease: 'power4.out', overwrite: 'auto' });
      },
      onRelease() {
        const card = (this.target as HTMLElement).querySelector<HTMLElement>('.bp-node-card');
        if (!card) return;
        if (reduced()) { gsap.set(card, { scale: 1 }); return; }
        // back.out(1.7) 弹簧:轻微压扁后弹回,物理"入模"反馈
        gsap.fromTo(card, { scale: 0.84 }, { scale: 1, duration: 0.52, ease: 'back.out(1.7)', overwrite: 'auto' });
      }
    });
  }

  /** 卸载 Draggable 并复位拖拽产生的 transform(节点回到拓扑坐标) */
  function detach() {
    instances.forEach((d) => d.kill());
    instances = [];
    const container = options.container.value;
    if (container) {
      // 外层 .bp-node 无 baseline transform(由 left/top 定位),清拖拽位移安全
      container.querySelectorAll<HTMLElement>('.bp-node').forEach((el) => {
        gsap.set(el, { clearProps: 'transform' });
      });
    }
  }

  function isAttached() {
    return instances.length > 0;
  }

  return { attach, detach, isAttached };
}
