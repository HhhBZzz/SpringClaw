/**
 * useParadigmFlip —— 范式切换的流体位移(GSAP Flip)
 *
 * 关键约束:节点必须以 **slot index** 作 v-for key(而非 node.id),这样切范式时
 * 同一 DOM 元素从旧坐标滑向新坐标,Flip 才能做"无缝流体位移"。
 * 按 id key 会让 Vue 全量替换元素 → Flip 退化为淡入淡出。
 *
 * 用法:
 *   const stageRef = ref<HTMLElement|null>(null);
 *   const { flipTo } = useParadigmFlip({ container: stageRef });
 *   await flipTo(() => { paradigm.value = next; });   // 内部已处理 nextTick
 */
import { nextTick, type Ref } from 'vue';
import gsap from 'gsap';
import { Flip } from 'gsap/Flip';

gsap.registerPlugin(Flip);

export interface UseParadigmFlipOptions {
  container: Ref<HTMLElement | null>;
  nodeSelector?: string;
}

export function useParadigmFlip(options: UseParadigmFlipOptions) {
  const nodeSelector = options.nodeSelector ?? '.bp-node';
  let active = false;

  function reduced() {
    return typeof window !== 'undefined'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }

  /**
   * 记录当前节点状态 → 应用变更(切范式) → Flip.from 让节点流体位移到新拓扑。
   * onEnter/onLeave 处理节点数随范式变化(3..6)时的进出场。
   */
  async function flipTo(apply: () => void): Promise<void> {
    if (active) return;
    const container = options.container.value;
    if (!container) { apply(); return; }
    active = true;
    const targets = container.querySelectorAll(nodeSelector);
    try {
      const state = Flip.getState(targets);
      apply();
      await nextTick();
      if (!reduced()) {
        Flip.from(state, {
          absolute: true,
          nested: false,
          scale: true,
          duration: 0.6,
          ease: 'power4.out',
          stagger: 0.02,
          prune: true,
          onEnter: (els) =>
            gsap.from(els, { opacity: 0, scale: 0.72, duration: 0.42, ease: 'power4.out', overwrite: 'auto' }),
          onLeave: (els) =>
            gsap.to(els, { opacity: 0, scale: 0.72, duration: 0.3, ease: 'power2.in', overwrite: 'auto' }),
          onComplete: () => {
            // best-effort 清 inline transform,避免污染 X-Ray
            try { gsap.set(targets, { clearProps: 'transform,scale,opacity' }); } catch { /* noop */ }
          }
        });
      }
      // 固定延时释放锁——不依赖 onComplete(在 transform:scale 父级下不可靠),防永久卡死
      await new Promise((r) => setTimeout(r, reduced() ? 60 : 640));
    } finally {
      active = false;
    }
  }

  return { flipTo };
}
