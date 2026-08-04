<script setup lang="ts">
/**
 * NodeCard —— 一个 Agent 节点。
 * 正面:kind 徽标 + 标题 + 副文;背面(X-Ray):源码定位 + 代码片段。
 * 点击整卡翻转进入 X-Ray,不遮挡主视图(吸收现有 timeline/code 视图)。
 * 状态:is-live(光梭触达,霓虹+glitch)/ is-done / is-failed 由父级按回放驱动。
 *
 * 翻转用 GSAP rotationY 驱动(而非 CSS transition):在父级带 transform+preserve-3d
 * 的结构下,CSS 3D transition 不稳定;gsap 直接写 inline matrix,可靠。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import gsap from 'gsap';
import type { CanvasNode, NodeStatus } from '../types';

const props = defineProps<{
  node: CanvasNode;
  isXray?: boolean;
  fastGlitch?: boolean;
}>();
const emit = defineEmits<{ (e: 'xray', id: string): void }>();

const flipRef = ref<HTMLElement | null>(null);
const comboRef = ref<HTMLElement | null>(null);

const KIND_BADGE: Record<string, string> = {
  question: 'QUESTION', answer: 'ANSWER', model: 'MODEL', thought: 'THOUGHT',
  action: 'ACTION', observation: 'OBSERVATION', observe: 'OBSERVE', plan: 'PLAN',
  act: 'ACT', reflect: 'REFLECT', goal: 'GOAL', step: 'STEP', complete: 'COMPLETE',
  execute: 'EXECUTE', replan: 'REPLAN', aggregate: 'AGGREGATE', attempt: 'ATTEMPT',
  decompose: 'DECOMPOSE', worker: 'WORKER', tool: 'TOOL', skill: 'SKILL', final: 'FINAL'
};

const badge = computed(() => KIND_BADGE[props.node.kind] ?? props.node.kind.toUpperCase());
const statusClass = computed(() => {
  const s = props.node.status ?? 'idle';
  return s === 'idle' ? '' : `is-${s as NodeStatus}`;
});

function reduced() {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

const DURATION = 520;
let currentDeg = 0;
let rafId = 0;

/** back.out 阻尼回弹(Cyber 机械咬合感):t→1 + overshoot。s 越大弹性越强。 */
function backOut(t: number, s = 1.7) {
  return 1 + (s + 1) * Math.pow(t - 1, 3) + s * Math.pow(t - 1, 2);
}

/** 手写 rAF 补间:每帧直接写 rotateY(Xdeg)。
 *  不用 CSS transition(本结构下 transform transition 走不到终点)、不用 gsap(rotationY 丢 3D)。 */
function applyFlip(toXray: boolean) {
  const el = flipRef.value;
  if (!el) return;
  const target = toXray ? 180 : 0;
  if (reduced()) {
    currentDeg = target;
    el.style.transform = `rotateY(${target}deg)`;
    return;
  }
  cancelAnimationFrame(rafId);
  const start = currentDeg;
  const t0 = performance.now();
  const tick = (now: number) => {
    const p = Math.min(1, (now - t0) / DURATION);
    // 翻转用 back.out:到 180 时带轻微回弹(机械咬合)
    currentDeg = start + (target - start) * backOut(p, 1.7);
    el.style.transform = `rotateY(${currentDeg}deg)`;
    if (p < 1) rafId = requestAnimationFrame(tick);
  };
  rafId = requestAnimationFrame(tick);
}

onMounted(() => {
  currentDeg = props.isXray ? 180 : 0;
  if (flipRef.value) flipRef.value.style.transform = `rotateY(${currentDeg}deg)`;
});

onUnmounted(() => cancelAnimationFrame(rafId));

watch(() => props.isXray, (v) => applyFlip(v));

/** 连击徽章 ×N:combo≥2 且未完成 → 入场(back.out 弹出);完成 → 退场(上飘缩小透明) */
watch(
  () => [props.node.combo ?? 0, props.node.status] as const,
  ([combo, status]) => {
    const el = comboRef.value;
    if (!el) return;
    if (reduced()) {
      // reduced-motion:静态显示(reduced-motion 下不动画,但 combo≥2 仍需可见,不能 display:none)
      if (combo >= 2 && status !== 'done') gsap.set(el, { display: 'inline-flex', y: 0, scale: 1, opacity: 1, clearProps: 'transform' });
      else gsap.set(el, { display: 'none' });
      return;
    }
    if (combo >= 2 && status !== 'done') {
      gsap.set(el, { display: 'inline-flex' });
      gsap.fromTo(el, { y: 6, scale: 0, opacity: 0 }, { y: 0, scale: 1, opacity: 1, duration: 0.34, ease: 'back.out(1.7)', overwrite: true });
    } else if (status === 'done') {
      // 突破重试循环:徽章上飘消散(配合光梭飞出)
      gsap.to(el, { y: -20, scale: 0, opacity: 0, duration: 0.3, ease: 'power2.in', overwrite: true, onComplete: () => { gsap.set(el, { display: 'none' }); } });
    }
  },
  { flush: 'post' }
);

function onClick() { emit('xray', props.node.id); }
</script>

<template>
  <div
    class="bp-node"
    :class="isXray && 'x-ray'"
    :style="{ left: `${node.x * 10}px`, top: `${node.y * 6.2}px` }"
    :title="`查看 ${node.label} 的执行细节`"
    tabindex="0"
    @click="onClick"
    @keydown.enter.prevent="onClick"
    @keydown.space.prevent="onClick"
    role="button"
  >
    <div class="bp-node-card" :class="[statusClass, fastGlitch && 'is-overflow']">
      <span ref="comboRef" class="bp-combo-badge" :data-c="node.combo ?? 0" aria-hidden="true">×{{ Math.max(1, node.combo ?? 1) }}</span>
      <span class="bp-port in" aria-hidden="true" />
      <span class="bp-port out" aria-hidden="true" />
      <div ref="flipRef" class="bp-node-flip">
        <!-- 正面 -->
        <div class="bp-face bp-front">
          <span class="bp-badge">{{ badge }}</span>
          <span class="bp-node-label">{{ node.label }}</span>
          <span v-if="node.detail" class="bp-node-meta">{{ node.detail }}</span>
        </div>
        <!-- 背面 X-Ray -->
        <div class="bp-face bp-back">
          <span class="bp-xray-head">// harness</span>
          <span v-if="node.loc" class="bp-xray-loc">{{ node.loc }}</span>
          <pre v-if="node.code" class="bp-xray-code">{{ node.code }}</pre>
          <div v-if="node.log && node.log.length" class="bp-xray-log" role="log" aria-live="polite">
            <span v-for="(line, idx) in node.log" :key="idx" class="bp-xray-log-line">{{ line }}</span>
          </div>
        </div>
      </div>
      <span class="bp-xray-hint">{{ isXray ? '点击返回' : 'X-Ray · 点击翻转' }}</span>
    </div>
  </div>
</template>
